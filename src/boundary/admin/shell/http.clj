(ns boundary.admin.shell.http
  "HTTP routes and handlers for the admin interface.

   This namespace provides the web UI for the admin panel, including:
   - Entity list pages with search, sort, and pagination
   - Entity detail/edit pages with forms
   - Create and update handlers with validation
   - Delete and bulk delete operations
   - HTMX fragment handlers for dynamic updates

   All routes require authentication and admin role.
   Routes follow normalized format for consistent interceptor application."
  (:require
   [boundary.admin.ports :as ports]
   [boundary.admin.core.ui :as admin-ui]
   [boundary.admin.core.permissions :as permissions]
   [boundary.shared.ui.core.components :as ui-components]
   [boundary.shared.ui.core.validation :as ui-validation]
   [boundary.platform.core.http.problem-details :as problem-details]
   [boundary.user.shell.middleware :as user-middleware]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [ring.util.response :as ring-response])
  (:import [java.util UUID]))

;; =============================================================================
;; Method Override Middleware
;; =============================================================================

(defn wrap-method-override
  "Middleware to support HTTP method override via _method parameter.
   
   HTML forms can only send GET and POST requests. This middleware allows
   forms to override the POST method by including a _method parameter:
   
   Example:
     <form method=\"POST\">
       <input type=\"hidden\" name=\"_method\" value=\"PUT\">
       ...
     </form>
   
   The middleware will change the request method from POST to PUT.
   
   Note: This middleware checks both :form-params (parsed by Reitit) and
   :params (fallback) to ensure compatibility."
  [handler]
  (fn [request]
    (if (= :post (:request-method request))
      (let [;; Try form-params first (parsed by Reitit), fallback to params
            method (or (get-in request [:form-params "_method"])
                       (get-in request [:params "_method"]))]
        (if method
          (let [override-method (keyword (str/lower-case method))]
            (handler (assoc request :request-method override-method)))
          (handler request)))
      (handler request))))

;; =============================================================================
;; Error Mappings - Admin-Specific RFC 7807 Problem Details
;; =============================================================================

(def admin-error-mappings
  "Error type mappings for admin-specific errors.

   Extends base error mappings with admin-specific error types:
   - :table-not-found - Entity/table doesn't exist in database
   - :entity-not-allowed - Entity not in allowlist
   - :invalid-entity-data - Validation failed on entity data"
  {:table-not-found
   {:status 404
    :type "https://boundary.app/errors/table-not-found"
    :title "Table Not Found"
    :detail-fn (fn [ex-data] (str "Table '" (:table-name ex-data) "' does not exist"))}

   :entity-not-allowed
   {:status 403
    :type "https://boundary.app/errors/entity-not-allowed"
    :title "Entity Not Allowed"
    :detail-fn (fn [ex-data] (str "Entity '" (:entity-name ex-data) "' is not accessible"))}

   :invalid-entity-data
   {:status 422
    :type "https://boundary.app/errors/invalid-entity-data"
    :title "Invalid Entity Data"
    :detail-fn (fn [ex-data] "Entity data failed validation")
    :errors-fn (fn [ex-data] (:errors ex-data))}})

(def combined-error-mappings
  "Merged error mappings: base + admin-specific"
  (merge problem-details/default-error-mappings admin-error-mappings))

;; =============================================================================
;; Query Parameter Parsing
;; =============================================================================

(defn parse-advanced-filters
  "Parse nested filter parameters from query string (Week 2).
   
   Expected formats:
   - filters[field][op]=operator
   - filters[field][value]=single-value
   - filters[field][values][]=multi-value-1
   - filters[field][values][]=multi-value-2
   - filters[field][min]=min-value
   - filters[field][max]=max-value
   
   Args:
     params: Ring query-params map
   
   Returns:
     Map of field-name -> filter-spec
     {:field-name {:op :operator :value val}
      :other-field {:op :between :min 10 :max 100}}
   
   Example:
     (parse-advanced-filters {\"filters[created-at][op]\" \"gte\"
                              \"filters[created-at][value]\" \"2024-01-01\"})
     => {:created-at {:op :gte :value \"2024-01-01\"}}"
  [params]
  (let [;; Find all params that start with "filters["
        filter-param-pattern #"^filters\[([^\]]+)\]\[([^\]]+)\](?:\[\])?$"
        filter-entries (for [[k v] params
                             :let [match (re-matches filter-param-pattern k)]
                             :when match]
                         (let [[_ field-name filter-key] match]
                           [(keyword field-name) (keyword filter-key) v]))]

    ;; Group by field name and build filter specs
    (reduce
     (fn [acc [field-name filter-key value]]
       (update acc field-name
               (fn [existing]
                 (let [current (or existing {})]
                   (case filter-key
                     :op (assoc current :op (keyword value))
                     :value (assoc current :value value)
                     :values (update current :values (fnil conj []) value)
                     :min (assoc current :min value)
                     :max (assoc current :max value)
                     current)))))
     {}
     filter-entries)))

(defn parse-query-params
  "Parse query parameters into admin service options.

   Extracts and normalizes:
   - Pagination: page, page-size, limit, offset
   - Sorting: sort, sort-dir
   - Search: search (text search across search-fields)
   - Filters: Any other params become field filters

   Args:
     params: Ring query-params map (all string values)

   Returns:
     Options map with normalized keys and parsed values

   Examples:
     (parse-query-params {page 2 page-size 25 search john})
     => {:page 2 :page-size 25 :search john}

     (parse-query-params {sort email sort-dir desc role admin})
     => {:sort :email :sort-dir :desc :filters {:role admin}}"
  [params]
  (let [page (when-let [p (get params "page")] (parse-long p))
        page-size (when-let [ps (get params "page-size")] (parse-long ps))
        limit (when-let [l (get params "limit")] (parse-long l))
        offset (when-let [o (get params "offset")] (parse-long o))
        sort (when-let [s (get params "sort")] (keyword s))
        ; Accept both "dir" and "sort-dir" for backward compatibility
        sort-dir (when-let [sd (or (get params "dir") (get params "sort-dir"))] (keyword sd))
        search (get params "search")
        add-filter-field (get params "add_filter_field")
        remove-filter-field (get params "remove_filter")

        ; Check for advanced filters (Week 2 format: filters[field][op]=...)
        advanced-filters (parse-advanced-filters params)

        ; Backward compatibility: Simple filters (Week 1 format: role=admin)
        ; Any params not in reserved keys and not part of advanced filters become simple filters
        reserved-keys #{"page" "page-size" "limit" "offset" "sort" "sort-dir" "dir" "search" "add_filter_field" "remove_filter"}
        advanced-filter-keys (set (filter #(str/starts-with? % "filters[") (keys params)))
        simple-filter-params (apply dissoc params (concat reserved-keys advanced-filter-keys))
        simple-filters (when (seq simple-filter-params)
                         (into {} (map (fn [[k v]] [(keyword k) {:op :eq :value v}])) simple-filter-params))

        ; Merge advanced and simple filters (advanced takes precedence)
        all-filters (merge simple-filters advanced-filters)

        ; Handle add/remove filter actions
        filters (cond
                  ; Adding a new filter - initialize with default operator
                  (and add-filter-field (not (str/blank? add-filter-field)))
                  (assoc all-filters (keyword add-filter-field) {:op :eq :value ""})

                  ; Removing a filter
                  (and remove-filter-field (not (str/blank? remove-filter-field)))
                  (dissoc all-filters (keyword remove-filter-field))

                  ; Default: use parsed filters
                  :else
                  all-filters)]

    (cond-> {}
      page (assoc :page page)
      page-size (assoc :page-size page-size)
      limit (assoc :limit limit)
      offset (assoc :offset offset)
      sort (assoc :sort sort)
      sort-dir (assoc :sort-dir sort-dir)
      search (assoc :search search)
      (seq filters) (assoc :filters filters))))

(defn build-query-string
  "Build query string from options map.

   Args:
     opts: Options map with keys like :page, :search, :sort, etc.

   Returns:
     Query string (without leading ?)

   Example:
     (build-query-string {:page 2 :search john :sort :email})
     => page=2&search=john&sort=email"
  [opts]
  (let [params (cond-> []
                 (:page opts) (conj (str "page=" (:page opts)))
                 (:page-size opts) (conj (str "page-size=" (:page-size opts)))
                 (:search opts) (conj (str "search=" (:search opts)))
                 (:sort opts) (conj (str "sort=" (name (:sort opts))))
                 (:sort-dir opts) (conj (str "sort-dir=" (name (:sort-dir opts)))))]
    (str/join "&" params)))

;; =============================================================================
;; Form Data Parsing
;; =============================================================================

(defn parse-form-params
  "Parse form parameters into entity data map.

   Converts string form values to appropriate types based on field config.

   Args:
     params: Ring form-params map (all string values)
     entity-config: Entity configuration with field metadata

   Returns:
     Entity data map with typed values

   Examples:
     (parse-form-params {name John active true} entity-config)
     => {:name John :active true}

     (parse-form-params {price 19.99 quantity 5} entity-config)
     => {:price 19.99 :quantity 5}"
  [params entity-config]
  (reduce-kv
   (fn [acc field-name value]
     (let [field-keyword (keyword field-name)
           field-config (get-in entity-config [:fields field-keyword])
           field-type (:type field-config :string)

            ; Convert string value to appropriate type
           typed-value (cond
                          ; Empty strings become nil
                         (str/blank? value) nil

                          ; Boolean checkbox values - checkboxes send "on" or "true"
                         (= field-type :boolean)
                         (contains? #{"on" "true" "1"} value)

                          ; Integer values
                         (= field-type :int)
                         (parse-long value)

                         ; Decimal values
                         (= field-type :decimal)
                         (bigdec value)

                         ; UUID values
                         (= field-type :uuid)
                         (UUID/fromString value)

                         ; Default: keep as string
                         :else value)]

       (if typed-value
         (assoc acc field-keyword typed-value)
         acc)))
   {}
   params))

;; =============================================================================
;; Handler Helpers
;; =============================================================================

(defn get-current-user
  "Extract authenticated user from request.

   The authentication middleware sets [:user {...}] with the full user entity
   (including :id, :role, :email, :name, etc.).

   Args:
     request: Ring request map

   Returns:
     Full user entity map, or nil if not authenticated"
  [request]
  (:user request))

(defn require-admin-user!
  "Assert current user is an admin, throw if not.

   Args:
     request: Ring request map

   Returns:
     User entity map if admin

   Throws:
     ExceptionInfo with :type :forbidden if not admin"
  [request]
  (let [user (get-current-user request)]
    (permissions/assert-can-access-admin! user)
    user))

(defn get-entity-name
  "Extract entity name from path parameters.

   Args:
     request: Ring request map

   Returns:
     Entity name as keyword"
  [request]
  (keyword (get-in request [:path-params :entity])))

(defn get-entity-id
  "Extract entity ID from path parameters.

   Args:
     request: Ring request map

   Returns:
     Entity ID as UUID"
  [request]
  (UUID/fromString (get-in request [:path-params :id])))

(defn html-response
  "Create HTML response with standard headers.
   
   Converts Hiccup data to HTML string if needed.

   Args:
     html: Hiccup data structure or HTML string

   Returns:
     Ring response map"
  [html]
  (let [body-content (if (string? html)
                       html
                       (ui-components/render-html html))]
    (-> (ring-response/response body-content)
        (ring-response/content-type "text/html; charset=utf-8"))))

(defn htmx-fragment-response
  "Create HTMX fragment response.

   Args:
     html: Hiccup HTML string for fragment

   Returns:
     Ring response map with HTMX headers"
  [html]
  (-> (html-response html)
      (ring-response/header "HX-Trigger" "entityListUpdated")))

(defn redirect-to-entity-list
  "Redirect to entity list page.

   Args:
     entity-name: Entity name keyword
     flash-message: Optional flash message map

   Returns:
     Ring redirect response"
  ([entity-name]
   (redirect-to-entity-list entity-name nil))
  ([entity-name flash-message]
   (let [location (str "/web/admin/" (name entity-name))]
     (cond-> (ring-response/redirect location)
       flash-message (assoc :flash flash-message)))))

;; =============================================================================
;; Admin Home Handler
;; =============================================================================

(defn admin-home-handler
  "Handler for admin home page - shows dashboard or first entity.

   Week 1: Simple redirect to first entity in list
   Week 2+: Dashboard with stats, recent activity, quick actions"
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          _ (log/info "After require-admin-user!" {:user-email (:email user)})
          entities (ports/list-available-entities schema-provider)]

      (if (seq entities)
        ; Redirect to first entity
        (do
          (log/info "Redirecting to first entity" {:entity (first entities)})
          (ring-response/redirect (str "/web/admin/" (name (first entities)))))

        ; No entities configured
        (html-response
         (admin-ui/admin-layout
          [:div.empty-state
           [:h2 "No Entities Configured"]
           [:p "Add entities to the :boundary/admin :entity-discovery :allowlist in your config."]]
          {:user user
           :current-entity nil
           :entities []
           :entity-configs {}}))))))

;; =============================================================================
;; Entity List Handlers
;; =============================================================================

(defn entity-list-handler
  "Handler for entity list page with table, search, pagination.

   Supports:
   - Text search across configured search-fields
   - Column sorting (ascending/descending)
   - Pagination (page-based)
   - Field filters"
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          entity-name (get-entity-name request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          options (parse-query-params (:query-params request))

          ; Get entity data
          result (ports/list-entities admin-service entity-name options)
          records (:records result)
          total-count (:total-count result)

          ; Merge pagination info from result into options for UI
          ; This ensures UI shows the actual page-size used (from config defaults)
          ; Also ensure sort/dir are present (use defaults if not in options)
          table-query (merge {:sort (or (:sort options) (:default-sort entity-config) :id)
                              :dir (or (:dir options) (:sort-dir options) :asc)}
                             options
                             {:page-size (:page-size result)
                              :page (:page-number result)})

          ; Get all available entities for sidebar
          entities (ports/list-available-entities schema-provider)
          entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)

          ; Get permissions
          permissions (permissions/get-entity-permissions user entity-name entity-config)

          ; Flash message from redirects
          flash (:flash request)]

      (html-response
       (admin-ui/admin-layout
        (admin-ui/entity-list-page entity-name records entity-config table-query total-count permissions options)
        {:user user
         :current-entity entity-name
         :entities entities
         :entity-configs entity-configs
         :flash flash})))))

(defn entity-table-fragment-handler
  "HTMX handler for entity table fragment.

   Returns just the table HTML for dynamic updates.
   Used when sorting, filtering, or paginating without full page reload."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          entity-name (get-entity-name request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          options (parse-query-params (:query-params request))

          ; Get entity data
          result (ports/list-entities admin-service entity-name options)
          records (:records result)
          total-count (:total-count result)

          ; Merge pagination info from result into options for UI
          table-query (merge {:sort (or (:sort options) (:default-sort entity-config) :id)
                              :dir (or (:dir options) (:sort-dir options) :asc)}
                             options
                             {:page-size (:page-size result)
                              :page (:page-number result)})

          ; Get permissions
          permissions (permissions/get-entity-permissions user entity-name entity-config)]

      (htmx-fragment-response
       [:div#filter-table-container
        ;; Filter builder (will be updated by HTMX)
        (admin-ui/render-filter-builder entity-name entity-config (:filters options))
        ;; Table (will also be updated by HTMX)
        (admin-ui/entity-table entity-name records entity-config table-query total-count permissions (:filters options))]))))

;; =============================================================================
;; Entity Detail/Edit Handlers
;; =============================================================================

(defn entity-detail-handler
  "Handler for entity detail/edit page.

   Shows form for editing existing entity."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          entity-name (get-entity-name request)
          id (get-entity-id request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (permissions/assert-can-edit-entity! user entity-name entity-config)

          ; Get entity record
          record (ports/get-entity admin-service entity-name id)

          ; Verify record exists
          _ (when-not record
              (throw (ex-info "Entity not found"
                              {:type :not-found
                               :entity-name entity-name
                               :id id})))

          ; Get all available entities for sidebar
          entities (ports/list-available-entities schema-provider)
          entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)

          ; Get permissions
          permissions (permissions/get-entity-permissions user entity-name entity-config)]

      (html-response
       (admin-ui/admin-layout
        (admin-ui/entity-detail-page entity-name entity-config record {} permissions {})
        {:user user
         :current-entity entity-name
         :entities entities
         :entity-configs entity-configs})))))

(defn new-entity-handler
  "Handler for new entity creation form.

   Shows empty form for creating new entity."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          entity-name (get-entity-name request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (permissions/assert-can-create-entity! user entity-name entity-config)

          ; Get all available entities for sidebar
          entities (ports/list-available-entities schema-provider)
          entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)

          ; Get permissions
          permissions (permissions/get-entity-permissions user entity-name entity-config)]

      (html-response
       (admin-ui/admin-layout
        (admin-ui/entity-detail-page entity-name entity-config nil {} permissions {})
        {:user user
         :current-entity entity-name
         :entities entities
         :entity-configs entity-configs})))))

;; =============================================================================
;; Create/Update Handlers
;; =============================================================================

(defn create-entity-handler
  "Handler for creating new entity.

   Validates form data, creates entity, redirects to list with flash message."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          entity-name (get-entity-name request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (permissions/assert-can-create-entity! user entity-name entity-config)

          ; Parse form data - support both :form-params (GET/POST) and :params/:body-params (PUT/PATCH)
          raw-params (or (:form-params request)
                         (:body-params request)
                         (:params request)
                         {})
          form-data (parse-form-params raw-params entity-config)

          ; Validate data
          validation-result (ports/validate-entity-data admin-service entity-name form-data)]

      (if (:valid? validation-result)
        ; Create entity and return list page
        (let [created-entity (ports/create-entity admin-service entity-name form-data)

              ; Fetch list page data
              entities (ports/list-available-entities schema-provider)
              entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)

              ; Get entity list with default options
              result (ports/list-entities admin-service entity-name {})
              records (:records result)
              total-count (:total-count result)
              table-query {:page-size (:page-size result)
                           :page (:page-number result)}

              permissions (permissions/get-entity-permissions user entity-name entity-config)]

          ; Return list page HTML with success message
          (html-response
           (admin-ui/admin-layout
            (admin-ui/entity-list-page entity-name records entity-config table-query total-count permissions {})
            {:user user
             :current-entity entity-name
             :entities entities
             :entity-configs entity-configs
             :flash {:type :success
                     :message (str (:label entity-config) " created successfully")}})))

        ; Validation errors - re-render form
        (let [entities (ports/list-available-entities schema-provider)
              entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)
              permissions (permissions/get-entity-permissions user entity-name entity-config)
              errors (ui-validation/explain->field-errors (:errors validation-result))]

          (html-response
           (admin-ui/admin-layout
            (admin-ui/entity-detail-page entity-name entity-config form-data errors permissions {})
            {:user user
             :current-entity entity-name
             :entities entities
             :entity-configs entity-configs
             :flash {:type :error
                     :message "Please fix the errors below"}})))))))

(defn update-entity-handler
  "Handler for updating existing entity.

   Validates form data, updates entity, redirects to list with flash message."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          entity-name (get-entity-name request)
          id (get-entity-id request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (permissions/assert-can-edit-entity! user entity-name entity-config)

          ; Parse form data - for PUT requests, params might be in :params or :body-params
          raw-params (or (:form-params request)
                         (:body-params request)
                         (:params request)
                         {})

          form-data (parse-form-params raw-params entity-config)

          ; Validate data
          validation-result (ports/validate-entity-data admin-service entity-name form-data)]

      (if (:valid? validation-result)
        ; Update entity and redirect
        (let [updated-entity (ports/update-entity admin-service entity-name id form-data)

              ; Fetch list page data
              entities (ports/list-available-entities schema-provider)
              entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)

              ; Get entity list with default options
              result (ports/list-entities admin-service entity-name {})
              records (:records result)
              total-count (:total-count result)
              table-query {:page-size (:page-size result)
                           :page (:page-number result)}

              permissions (permissions/get-entity-permissions user entity-name entity-config)]

          ; Return list page HTML with success message
          (html-response
           (admin-ui/admin-layout
            (admin-ui/entity-list-page entity-name records entity-config table-query total-count permissions {})
            {:user user
             :current-entity entity-name
             :entities entities
             :entity-configs entity-configs
             :flash {:type :success
                     :message (str (:label entity-config) " updated successfully")}})))

        ; Validation errors - re-render form
        (let [entities (ports/list-available-entities schema-provider)
              entity-configs (into {} (map (fn [e] [e (ports/get-entity-config schema-provider e)])) entities)
              permissions (permissions/get-entity-permissions user entity-name entity-config)
              errors (ui-validation/explain->field-errors (:errors validation-result))
              ; Merge form data with original record for display
              record (merge (ports/get-entity admin-service entity-name id) form-data)]

          (html-response
           (admin-ui/admin-layout
            (admin-ui/entity-detail-page entity-name entity-config record errors permissions {})
            {:user user
             :current-entity entity-name
             :entities entities
             :entity-configs entity-configs
             :flash {:type :error
                     :message "Please fix the errors below"}})))))))

;; =============================================================================
;; Delete Handlers
;; =============================================================================

(defn delete-entity-handler
  "Handler for deleting entity.

   Soft or hard delete based on entity schema configuration.
   Returns HTMX fragment triggering table refresh."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          entity-name (get-entity-name request)
          id (get-entity-id request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (permissions/assert-can-delete-entity! user entity-name entity-config)

          ; Delete entity (soft or hard based on schema)
          deleted? (ports/delete-entity admin-service entity-name id)]

      (if deleted?
        ; Success - trigger table refresh
        (-> (ring-response/response "")
            (ring-response/status 200)
            (ring-response/header "HX-Trigger" "entityDeleted"))

        ; Failed to delete
        (-> (ring-response/response "Failed to delete entity")
            (ring-response/status 500))))))

(defn bulk-delete-handler
  "Handler for bulk deleting multiple entities.

   Expects form with 'ids[]' parameter containing entity IDs.
   Returns HTMX fragment triggering table refresh."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          entity-name (get-entity-name request)

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)

          ; Check permissions
          _ (permissions/assert-can-delete-entity! user entity-name entity-config)

          ; Extract IDs from form params (checkboxes named 'record-ids')
          id-strings (get-in request [:form-params "record-ids"])
          ids (when id-strings
                (mapv #(UUID/fromString %) (if (string? id-strings) [id-strings] id-strings)))

          ; Bulk delete
          result (when (and ids (seq ids))
                   (ports/bulk-delete-entities admin-service entity-name ids))
          success-count (or (:success-count result) 0)
          failed-count (or (:failed-count result) 0)]

      ; Return updated table regardless of success/failure
      (let [; Fetch updated list
            list-result (ports/list-entities admin-service entity-name {})
            records (:records list-result)
            total-count (:total-count list-result)
            table-query {:page-size (:page-size list-result)
                         :page (:page-number list-result)}
            permissions (permissions/get-entity-permissions user entity-name entity-config)

            ; Create flash message
            flash-msg (if (zero? failed-count)
                        {:type :success
                         :message (str "Successfully deleted " success-count " " (:label entity-config))}
                        {:type :warning
                         :message (str "Deleted " success-count ", failed " failed-count)})]

         ; Return table HTML fragment
        (htmx-fragment-response
         (admin-ui/entity-table entity-name records entity-config table-query total-count permissions {} flash-msg))))))

;; =============================================================================
;; Inline Editing Handlers (Week 2)
;; =============================================================================

(defn parse-field-value
  "Parse a single field value from string to appropriate type.

   Helper function for inline editing - extracts type conversion logic
   from parse-form-params.

   Args:
     value: String value from form
     field-config: Field configuration map

   Returns:
     Typed value or nil"
  [value field-config]
  (let [field-type (:type field-config :string)]
    (cond
      ; Empty strings become nil
      (str/blank? value) nil

      ; Boolean checkbox values
      (= field-type :boolean)
      (contains? #{"on" "true" "1"} value)

      ; Integer values
      (= field-type :int)
      (parse-long value)

      ; Decimal values
      (= field-type :decimal)
      (bigdec value)

      ; UUID values
      (= field-type :uuid)
      (UUID/fromString value)

      ; Default: keep as string
      :else value)))

(defn inline-edit-widget-handler
  "Handler for GET /:entity/:id/:field/edit - returns inline edit form.

   Returns HTMX fragment with form widget for editing a single field."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          entity-name (get-entity-name request)
          id (get-entity-id request)
          field (keyword (get-in request [:path-params :field]))

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          field-config (get-in entity-config [:fields field])

          ; Check permissions
          _ (permissions/assert-can-edit-entity! user entity-name entity-config)

          ; Verify field exists and is not readonly
          _ (when-not field-config
              (throw (ex-info "Field not found"
                              {:type :not-found
                               :field field})))

          readonly-fields (set (:readonly-fields entity-config))
          _ (when (contains? readonly-fields field)
              (throw (ex-info "Cannot edit readonly field"
                              {:type :forbidden
                               :field field})))

          ; Get current record
          record (ports/get-entity admin-service entity-name id)
          current-value (get record field)]

      ; Return inline edit form fragment
      (html-response
       (admin-ui/render-inline-edit-form entity-name id field current-value field-config)))))

(defn update-field-handler
  "Handler for PATCH /:entity/:id/:field - updates single field.

   Validates and updates single field, returns updated cell HTML."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          entity-name (get-entity-name request)
          id (get-entity-id request)
          field (keyword (get-in request [:path-params :field]))

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          field-config (get-in entity-config [:fields field])

          ; Check permissions
          _ (permissions/assert-can-edit-entity! user entity-name entity-config)

          ; Get new value from form
          raw-params (or (:form-params request)
                         (:body-params request)
                         (:params request)
                         {})

          ; Parse the field value using the same logic as full form parsing
          field-value (get raw-params (name field))
          parsed-value (parse-field-value field-value field-config)]

      (try
        ; Update single field
        (let [updated-record (ports/update-entity-field admin-service entity-name id field parsed-value)
              new-value (get updated-record field)]

          ; Return updated cell HTML with success indicator
          (-> (html-response
               (admin-ui/render-inline-edit-cell entity-name id field new-value field-config))
              (ring-response/header "HX-Trigger" "entityUpdated")))

        (catch Exception e
          (let [error-data (ex-data e)]
            (if (= (:type error-data) :validation-error)
              ; Return inline form with error message
              (html-response
               (admin-ui/render-inline-edit-form-with-error
                entity-name id field parsed-value field-config
                (get-in error-data [:errors field] ["Validation failed"])))
              ; Re-throw other errors
              (throw e))))))))

(defn cancel-inline-edit-handler
  "Handler for GET /:entity/:id/:field/cancel - cancels inline edit.

   Returns the original cell HTML without changes."
  [admin-service schema-provider config]
  (fn [request]
    (let [user (require-admin-user! request)
          entity-name (get-entity-name request)
          id (get-entity-id request)
          field (keyword (get-in request [:path-params :field]))

          ; Verify entity is accessible
          _ (when-not (ports/validate-entity-exists schema-provider entity-name)
              (throw (ex-info "Entity not allowed"
                              {:type :entity-not-allowed
                               :entity-name entity-name})))

          entity-config (ports/get-entity-config schema-provider entity-name)
          field-config (get-in entity-config [:fields field])

          ; Get current record
          record (ports/get-entity admin-service entity-name id)
          current-value (get record field)]

      ; Return original cell HTML
      (html-response
       (admin-ui/render-inline-edit-cell entity-name id field current-value field-config)))))

;; =============================================================================
;; Route Definitions
;; =============================================================================

(defn normalized-web-routes
  "Normalized web routes for admin interface.

   All routes require authentication and admin role.
   Routes use flexible-authentication-middleware for session or token auth."
  [admin-service schema-provider config user-service]
  (let [;; Reuse existing auth middleware from user module
        auth-middleware (user-middleware/flexible-authentication-middleware user-service)]

    [{:path "/"
      :meta {:middleware [auth-middleware]}
      :methods {:get {:handler (admin-home-handler admin-service schema-provider config)
                      :summary "Admin home page"}}}

      ;; More specific routes first (to avoid matching /:id patterns)
     {:path "/:entity/new"
      :meta {:middleware [auth-middleware]}
      :methods {:get {:handler (new-entity-handler admin-service schema-provider config)
                      :summary "Create form page"}}}

     {:path "/:entity/table"
      :meta {:middleware [auth-middleware]}
      :methods {:get {:handler (entity-table-fragment-handler admin-service schema-provider config)
                      :summary "HTMX table fragment"}}}

     {:path "/:entity/bulk-delete"
      :meta {:middleware [auth-middleware]}
      :methods {:post {:handler (bulk-delete-handler admin-service schema-provider config)
                       :summary "Bulk delete entities"}}}

      ;; Inline editing routes (Week 2)
     {:path "/:entity/:id/:field/edit"
      :meta {:middleware [auth-middleware]}
      :methods {:get {:handler (inline-edit-widget-handler admin-service schema-provider config)
                      :summary "Get inline edit form for field"}}}

     {:path "/:entity/:id/:field/cancel"
      :meta {:middleware [auth-middleware]}
      :methods {:get {:handler (cancel-inline-edit-handler admin-service schema-provider config)
                      :summary "Cancel inline edit"}}}

     {:path "/:entity/:id/:field"
      :meta {:middleware [auth-middleware]}
      :methods {:patch {:handler (update-field-handler admin-service schema-provider config)
                        :summary "Update single field (inline edit)"}}}

      ;; General routes with path params last
     {:path "/:entity"
      :meta {:middleware [auth-middleware]}
      :methods {:get {:handler (entity-list-handler admin-service schema-provider config)
                      :summary "Entity list page"}
                :post {:handler (create-entity-handler admin-service schema-provider config)
                       :summary "Create entity"}}}

     {:path "/:entity/:id"
      :meta {:middleware [auth-middleware]}
      :methods {:get {:handler (entity-detail-handler admin-service schema-provider config)
                      :summary "Entity detail/edit page"}
                :put {:handler (update-entity-handler admin-service schema-provider config)
                      :summary "Update entity"}
                :delete {:handler (delete-entity-handler admin-service schema-provider config)
                         :summary "Delete entity"}}}]))

(defn admin-routes-normalized
  "Normalized admin routes grouped by category.

   Week 1: Only web routes (server-rendered HTML)
   Week 2+: Add API routes for JSON responses

   Returns:
     Map with :api, :web, :static route vectors"
  [admin-service schema-provider config user-service]
  {:api []  ; Week 2+: JSON API endpoints
   :web (normalized-web-routes admin-service schema-provider config user-service)
   :static []})  ; Week 2+: Admin-specific static assets
