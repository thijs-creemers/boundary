(ns boundary.admin.core.ui
  "Admin UI components using Hiccup for server-side HTML generation.

   This namespace contains pure functions for generating admin interface
   Hiccup structures. Components are reusable and follow the existing
   UI patterns from boundary.shared.ui.core.

   Key responsibilities:
   - Admin layout with entity sidebar navigation
   - Entity list pages with search, sort, pagination
   - Entity detail/edit forms with field widgets
   - HTMX-powered dynamic updates
   - Field rendering based on entity configuration"
  (:require [boundary.shared.ui.core.components :as ui]
            [boundary.shared.ui.core.icons :as icons]
            [boundary.shared.ui.core.layout :as layout]
            [boundary.shared.ui.core.table :as table-ui]
            [boundary.shared.web.table :as web-table]
            [clojure.string :as str]))

;; =============================================================================
;; Admin Layout Components
;; =============================================================================

(defn admin-sidebar
  "Admin sidebar with entity navigation and icons.

   Args:
     entities: Vector of entity name keywords available to user
     entity-configs: Map of entity-name -> entity-config
     current-entity: Currently active entity name (optional)

   Returns:
     Hiccup sidebar structure with entity list"
  [entities entity-configs current-entity]
  [:aside.admin-sidebar
   [:div.admin-sidebar-header
    [:h2 "Admin Panel"]
    [:div.sidebar-controls
     [:button.sidebar-toggle {:type "button"
                              :aria-label "Toggle sidebar"
                              :title "Toggle sidebar (Ctrl+B)"}
      (icons/icon :panel-left {:size 20})]
     [:button.sidebar-pin {:type "button"
                           :aria-label "Pin sidebar"
                           :title "Pin sidebar open"}
      (icons/icon :pin {:size 20})]]]
   [:nav.admin-sidebar-nav
    [:h3 "Entities"]
    [:ul.entity-list
     (for [entity entities]
       (let [entity-config (get entity-configs entity)
             label (:label entity-config (str/capitalize (name entity)))
             icon (:icon entity-config :database)
             is-active? (= entity current-entity)]
         [:li {:class (when is-active? "active")}
          [:a {:href (str "/web/admin/" (name entity))
               :data-label label}
           (icons/icon icon {:size 20})
           [:span.nav-text label]]]))]
    [:h3 "Tools"]
    [:ul.entity-list
     [:li
      [:a {:href "/web/users" :data-label "Users"}
       (icons/icon :users {:size 20})
       [:span.nav-text "Users"]]]
     [:li
      [:a {:href "/web/audit-trail" :data-label "Audit Trail"}
       (icons/icon :clock {:size 20})
       [:span.nav-text "Audit Trail"]]]]]
   [:div.admin-sidebar-footer
    [:a {:href "/web/admin"}
     (icons/icon :home {:size 20})
     [:span.nav-text "Dashboard"]]
    [:a {:href "/web"}
     (icons/icon :external-link {:size 20})
     [:span.nav-text "Main Site"]]]])

(defn admin-shell
  "New admin shell layout with collapsible sidebar (Phase 2).

   Args:
     content: Main content (Hiccup structure)
     opts: Map with :user, :current-entity, :entities, :entity-configs, :flash, :page-title

   Returns:
     Admin shell structure with sidebar and topbar"
  [content opts]
  (let [{:keys [user current-entity entities entity-configs page-title]} opts]
    [:div.admin-shell {:data-sidebar-state "expanded"
                       :data-sidebar-pinned "false"
                       :data-sidebar-open "false"}
     (admin-sidebar entities entity-configs current-entity)
     [:div.admin-overlay]
     [:div.admin-main
       [:header.admin-topbar
        [:button.mobile-menu-toggle {:type "button"
                                     :aria-label "Open menu"}
         (icons/icon :menu {:size 24})]
        [:h1 (or page-title "Admin Dashboard")]
        [:div.admin-topbar-actions
         (icons/theme-toggle-button)
         [:span (str "Welcome, " (:display-name user (:email user)))]]]
      [:main.admin-content
       content]]]))

(defn admin-layout
  "Main admin layout with new shell structure (Phase 2).

   Args:
     content: Main content (Hiccup structure)
     opts: Map with :user, :current-entity, :entities, :entity-configs, :flash

   Returns:
     Complete HTML page structure with new admin shell"
  [content opts]
  (let [{:keys [user current-entity entity-configs flash]} opts
        title (if current-entity
                (str "Admin - " (:label (get entity-configs current-entity)))
                "Admin Dashboard")
        page-title (when current-entity
                     (:label (get entity-configs current-entity)))]
    (layout/page-layout
     title
     (admin-shell content (assoc opts :page-title page-title))
     {:user user 
      :flash flash
      :css ["/css/pico.min.css" "/css/tokens.css" "/css/admin.css" "/css/app.css"]
      :js ["/js/theme.js" "/js/htmx.min.js" "/js/sidebar.js"]})))

(defn admin-home
  "Admin dashboard home page content.

   Args:
     entities: Vector of available entity names
     entity-configs: Map of entity configurations
     stats: Optional map of dashboard statistics

   Returns:
     Hiccup structure for dashboard"
  [entities entity-configs & [stats]]
  [:div.admin-home
   [:h1 "Admin Dashboard"]
   [:p "Manage your application entities."]
   [:div.entity-grid
    (for [entity entities]
      (let [entity-config (get entity-configs entity)
            label (:label entity-config)
            description (:description entity-config)
            icon (:icon entity-config "📄")
            count (get-in stats [entity :count] 0)]
        [:div.entity-card
         [:a {:href (str "/web/admin/" (name entity))}
          [:div.entity-card-icon icon]
          [:div.entity-card-title label]
          (when description
            [:div.entity-card-description description])
          [:div.entity-card-count (str count " records")]]]))]])

;; =============================================================================
;; Entity List Components
;; =============================================================================

(defn entity-search-form
  "Search form for entity list filtering.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     current-search: Current search term (optional)
     current-filters: Current filter values (optional)

   Returns:
     Hiccup search form structure"
  [entity-name entity-config current-search current-filters]
  (let [search-fields (:search-fields entity-config)
        has-search? (seq search-fields)]
    (when has-search?
      [:div.entity-search-form
       [:form {:hx-get (str "/web/admin/" (name entity-name) "/table")
               :hx-target "#entity-table-container"
               :hx-push-url "true"
               :hx-trigger "submit, change from:select delay:300ms"}
        [:div.search-controls
         [:div.search-input
          [:input {:type "text"
                   :name "search"
                   :placeholder (str "Search " (str/join ", " (map name search-fields)) "...")
                   :value (or current-search "")
                   :autofocus true}]
          [:button {:type "submit" :class "button primary"} "Search"]]
         (when (seq current-search)
           [:a.button.secondary {:href (str "/web/admin/" (name entity-name))} "Clear"])]]])))

(defn render-field-value
  "Render field value for display in table or detail view.

   Args:
     field-name: Keyword field name
     value: Field value to render
     field-config: Field configuration map

   Returns:
     Hiccup structure or string for display"
  [field-name value field-config]
  (let [field-type (:type field-config :string)]
    (cond
      (nil? value)
      [:span.null-value "—"]

      (= field-type :boolean)
      [:span {:class (str "badge " (if value "badge-success" "badge-secondary"))}
       (if value "Yes" "No")]

      (= field-type :instant)
      (str value)

      (= field-type :date)
      (str value)

      (= field-type :uuid)
      [:span.uuid-value (str value)]

      (= field-type :enum)
      [:span.enum-badge (str/capitalize (name value))]

      (= field-type :json)
      [:code (str value)]

      (string? value)
      (if (> (count value) 50)
        (str (subs value 0 47) "...")
        value)

      :else
      (str value))))

(defn entity-table-row
  "Generate entity table row.

   Args:
     entity-name: Keyword entity name
     record: Entity record map
     entity-config: Entity configuration map
     permissions: Permission flags for this entity

   Returns:
     Hiccup table row"
  [entity-name record entity-config permissions]
  (let [list-fields (:list-fields entity-config)
        primary-key (:primary-key entity-config :id)
        record-id (get record primary-key)]
    [:tr {:class "entity-row"}
     [:td.row-actions
      [:input {:type "checkbox"
               :name "record-ids"
               :value (str record-id)
               :onclick "event.stopPropagation();"}]]
     (for [field list-fields]
       (let [field-config (get-in entity-config [:fields field])
             value (get record field)]
         [:td {:class (str "field-" (name field))}
          (render-field-value field value field-config)]))
     [:td.row-actions
      (when (:can-edit permissions)
        [:a.button.small.secondary
         {:href (str "/web/admin/" (name entity-name) "/" record-id)}
         "Edit"])
      (when (:can-delete permissions)
        [:button.button.small.danger
         {:hx-delete (str "/web/admin/" (name entity-name) "/" record-id)
          :hx-confirm "Are you sure you want to delete this record?"
          :hx-target "#entity-table-container"}
         "Delete"])]]))

(defn entity-table
  "Generate entity table with sorting and pagination.

   Args:
     entity-name: Keyword entity name
     records: Collection of entity records
     entity-config: Entity configuration map
     table-query: Table query parameters (sort, page, etc.)
     total-count: Total number of records
     permissions: Permission flags
     filters: Optional search filters

   Returns:
     Hiccup table structure"
  [entity-name records entity-config table-query total-count permissions & [filters]]
  (let [{:keys [sort dir page page-size]} table-query
        base-url (str "/web/admin/" (name entity-name) "/table")
        hx-target "#entity-table-container"
        table-params (web-table/table-query->params table-query)
        filter-params (web-table/search-filters->params (or filters {}))
        qs-map (merge table-params filter-params)
        hx-url (str base-url "?" (web-table/encode-query-params qs-map))
        list-fields (:list-fields entity-config)]
    [:div#entity-table-container
     {:hx-get hx-url
      :hx-trigger "entityCreated from:body, entityUpdated from:body, entityDeleted from:body"
      :hx-target hx-target}
     (if (empty? records)
       [:div.empty-state
        [:p "No records found."]
        (when (:can-create permissions)
          [:a.button.primary
           {:href (str "/web/admin/" (name entity-name) "/new")}
           "Create First Record"])]
       [:div.table-wrapper
        [:form#bulk-action-form
         {:hx-post (str "/web/admin/" (name entity-name) "/bulk")
          :hx-target hx-target
          :hx-swap "outerHTML"}
         ;; Preserve table state
         (for [[k v] table-params]
           [:input {:type "hidden" :name k :value v}])
         (for [[k v] filter-params]
           [:input {:type "hidden" :name k :value v}])
         [:div.bulk-actions
          [:button.button.small.secondary
           {:type "submit"
            :name "action"
            :value "delete"
            :disabled "disabled"
            :id "bulk-delete-btn"
            :hx-confirm "Are you sure you want to delete selected records?"}
           "Delete Selected"]]
         [:table.data-table
          [:thead
           [:tr
            [:th
             [:input {:type "checkbox"
                      :id "select-all"
                      :onchange "document.querySelectorAll('input[name=\"record-ids\"]').forEach(cb => cb.checked = this.checked); document.getElementById('bulk-delete-btn').disabled = !this.checked;"}]]
            (for [field list-fields]
              (let [field-config (get-in entity-config [:fields field])
                    sortable? (:sortable field-config true)]
                (if sortable?
                  (table-ui/sortable-th {:label (:label field-config (str/capitalize (name field)))
                                         :field field
                                         :current-sort sort
                                         :current-dir dir
                                         :base-url base-url
                                         :page page
                                         :page-size page-size
                                         :hx-target hx-target
                                         :hx-push-url? true
                                         :extra-params filters})
                  [:th (:label field-config (str/capitalize (name field)))])))
            [:th "Actions"]]]
          [:tbody
           (for [record records]
             (entity-table-row entity-name record entity-config permissions))]]]
        (table-ui/pagination {:table-query table-query
                              :total-count total-count
                              :base-url base-url
                              :hx-target hx-target
                              :extra-params filters})])]))

(defn entity-list-page
  "Complete entity list page with search, table, and actions.

   Args:
     entity-name: Keyword entity name
     records: Collection of entity records
     entity-config: Entity configuration map
     table-query: Table query parameters
     total-count: Total number of records
     permissions: Permission flags
     opts: Optional map with :search, :filters, :flash

   Returns:
     Hiccup page structure"
  [entity-name records entity-config table-query total-count permissions & [opts]]
  (let [{:keys [search filters flash]} opts
        label (:label entity-config)]
    [:div.entity-list-page
     (when flash
       (for [[type message] flash]
         [:div {:class (str "alert alert-" (name type))} message]))
     [:div.page-header
      [:div.page-title
       [:h1 label]
       [:p.page-subtitle (str total-count " records")]]
      [:div.page-actions
       (when (:can-create permissions)
         [:a.button.primary
          {:href (str "/web/admin/" (name entity-name) "/new")}
          "Create New"])]]
     (entity-search-form entity-name entity-config search filters)
     (entity-table entity-name records entity-config table-query total-count permissions filters)]))

;; =============================================================================
;; Entity Detail/Edit Components
;; =============================================================================

(defn render-field-widget
  "Render input widget for field based on its configuration.

   Args:
     field-name: Keyword field name
     value: Current field value
     field-config: Field configuration map
     errors: Optional collection of error messages for this field

   Returns:
     Hiccup form field structure"
  [field-name value field-config errors]
  (let [widget-type (:widget field-config :text-input)
        label (:label field-config (str/capitalize (name field-name)))
        required? (:required field-config false)
        readonly? (:readonly field-config false)
        placeholder (:placeholder field-config)
        help-text (:help-text field-config)
        options (:options field-config)
        field-type (:type field-config :string)
        min (:min field-config)
        max (:max field-config)
        pattern (:pattern field-config)]
    [:div.form-field {:class (when (seq errors) "has-errors")}
     [:label {:for (name field-name)}
      label
      (when required? [:span.required " *"])]
     (cond
       ;; Text input widgets
       (= widget-type :text-input)
       (ui/text-input field-name value
                      (merge {:placeholder placeholder
                              :required required?
                              :readonly readonly?
                              :class "form-control"}
                             (when pattern {:pattern pattern})
                             (when (and (= field-type :string) min) {:minlength min})
                             (when (and (= field-type :string) max) {:maxlength max})))

       (= widget-type :email-input)
       (ui/email-input field-name value
                       {:placeholder placeholder
                        :required required?
                        :readonly readonly?
                        :class "form-control"})

       (= widget-type :password-input)
       (ui/password-input field-name value
                          {:placeholder placeholder
                           :required required?
                           :readonly readonly?
                           :class "form-control"
                           :autocomplete "new-password"})

       (= widget-type :url-input)
       (ui/text-input field-name value
                      {:type "url"
                       :placeholder placeholder
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       (= widget-type :number-input)
       (ui/text-input field-name value
                      (merge {:type "number"
                              :placeholder placeholder
                              :required required?
                              :readonly readonly?
                              :class "form-control"}
                             (when min {:min min})
                             (when max {:max max})))

       ;; Boolean input
       (= widget-type :checkbox)
       [:div.checkbox-wrapper
        (ui/checkbox field-name value {:required required? :disabled readonly?})
        (when help-text
          [:span.help-text help-text])]

       ;; Select/dropdown
       (= widget-type :select)
       (ui/select-field field-name options value
                        {:required required?
                         :disabled readonly?
                         :class "form-control"})

       ;; Textarea
       (= widget-type :textarea)
       (ui/textarea field-name value
                    {:placeholder placeholder
                     :required required?
                     :readonly readonly?
                     :class "form-control"
                     :rows (or (:rows field-config) 4)})

       ;; Date/time inputs
       (= widget-type :date-input)
       (ui/text-input field-name value
                      {:type "date"
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       (= widget-type :datetime-input)
       (ui/text-input field-name value
                      {:type "datetime-local"
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       ;; Color picker
       (= widget-type :color-input)
       (ui/text-input field-name value
                      {:type "color"
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       ;; File upload
       (= widget-type :file-input)
       [:input {:type "file"
                :id (name field-name)
                :name (name field-name)
                :required required?
                :disabled readonly?
                :class "form-control"}]

       ;; Hidden field
       (= widget-type :hidden)
       [:input {:type "hidden"
                :id (name field-name)
                :name (name field-name)
                :value (or value "")}]

       ;; Default fallback
       :else
       (ui/text-input field-name value
                      {:placeholder placeholder
                       :required required?
                       :readonly readonly?
                       :class "form-control"}))

     (when help-text
       [:small.help-text help-text])
     (when (seq errors)
       [:div.field-errors
        (for [error errors]
          [:span.error error])])]))

(defn entity-form
  "Generate entity create/edit form.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     record: Entity record map (nil for create)
     errors: Optional map of field-name -> error messages
     permissions: Permission flags

   Returns:
     Hiccup form structure"
  [entity-name entity-config record errors permissions]
  (let [editable-fields (:editable-fields entity-config)
        primary-key (:primary-key entity-config :id)
        record-id (get record primary-key)
        is-edit? (some? record)
        form-action (if is-edit?
                      (str "/web/admin/" (name entity-name) "/" record-id)
                      (str "/web/admin/" (name entity-name)))
        form-method (if is-edit? "PUT" "POST")
        hx-attr (if is-edit? :hx-put :hx-post)]
    [:form.entity-form
     {hx-attr form-action
      :hx-swap "none"
      :hx-on--after-request "if(event.detail.successful) { htmx.trigger('body', 'entityCreated'); window.location.href = '/web/admin/" (name entity-name) "'; }"}
     ;; No longer need hidden _method field since HTMX sends proper HTTP method
     [:div.form-fields
      (for [field-name editable-fields]
        (let [field-config (get-in entity-config [:fields field-name])
              field-value (get record field-name)
              field-errors (get errors field-name)]
          (render-field-widget field-name field-value field-config field-errors)))]
     [:div.form-actions
      [:button.button.primary {:type "submit"}
       (if is-edit? "Update" "Create")]
      [:a.button.secondary
       {:href (str "/web/admin/" (name entity-name))}
       "Cancel"]]]))

(defn entity-detail-page
  "Entity detail/edit page.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     record: Entity record (nil for create)
     errors: Optional validation errors
     permissions: Permission flags
     opts: Optional map with :flash

   Returns:
     Hiccup page structure"
  [entity-name entity-config record errors permissions & [opts]]
  (let [{:keys [flash]} opts
        label (:label entity-config)
        is-edit? (some? record)
        page-title (if is-edit?
                     (str "Edit " label)
                     (str "Create " label))]
    [:div.entity-detail-page
     (when flash
       (for [[type message] flash]
         [:div {:class (str "alert alert-" (name type))} message]))
     [:div.page-header
      [:div.breadcrumbs
       [:a {:href "/web/admin"} "Admin"]
       " / "
       [:a {:href (str "/web/admin/" (name entity-name))} label]
       " / "
       [:span page-title]]
      [:h1 page-title]]
     (when (seq errors)
       (ui/validation-errors errors))
     (entity-form entity-name entity-config record errors permissions)]))

(defn entity-new-page
  "Entity creation page (convenience wrapper).

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     errors: Optional validation errors
     permissions: Permission flags
     opts: Optional map with :flash

   Returns:
     Hiccup page structure"
  [entity-name entity-config errors permissions & [opts]]
  (entity-detail-page entity-name entity-config nil errors permissions opts))

;; =============================================================================
;; Confirmation Dialog
;; =============================================================================

(defn confirm-delete-dialog
  "Confirmation dialog for delete operations.

   Args:
     entity-name: Keyword entity name
     record-id: ID of record to delete

   Returns:
     Hiccup modal structure"
  [entity-name record-id]
  [:div.modal#confirm-delete-modal
   [:div.modal-content
    [:h3 "Confirm Delete"]
    [:p "Are you sure you want to delete this record? This action cannot be undone."]
    [:div.modal-actions
     [:button.button.danger
      {:hx-delete (str "/web/admin/" (name entity-name) "/" record-id)
       :hx-target "#entity-table-container"}
      "Delete"]
     [:button.button.secondary
      {:onclick "closeModal('confirm-delete-modal')"}
      "Cancel"]]]])

;; =============================================================================
;; Error Pages
;; =============================================================================

(defn admin-forbidden-page
  "403 Forbidden page for admin access denial.

   Args:
     reason: Explanation of why access was denied
     user: Current user (optional)

   Returns:
     Hiccup page structure"
  [reason & [user]]
  (layout/page-layout
   "Access Denied"
   [:div.error-page.admin-forbidden
    [:h1 "403 - Access Denied"]
    [:p.error-message "You do not have permission to access the admin interface."]
    (when reason
      [:p.error-reason reason])
    [:div.error-actions
     (if user
       [:a.button {:href "/"} "Go to Dashboard"]
       [:a.button {:href "/web/login"} "Login"])]]
   {:user user}))

(defn admin-not-found-page
  "404 Not Found page for admin entities.

   Args:
     entity-name: Entity that was not found
     user: Current user

   Returns:
     Hiccup page structure"
  [entity-name user]
  (layout/page-layout
   "Not Found"
   [:div.error-page.admin-not-found
    [:h1 "404 - Entity Not Found"]
    [:p.error-message (str "The entity '" (name entity-name) "' does not exist or is not accessible.")]
    [:div.error-actions
     [:a.button {:href "/web/admin"} "Back to Admin"]]]
   {:user user}))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn build-table-url
  "Build table URL with query parameters.

   Args:
     entity-name: Keyword entity name
     opts: Map with :page, :page-size, :sort, :dir, :search, etc.

   Returns:
     URL string with query parameters"
  [entity-name opts]
  (let [base-url (str "/web/admin/" (name entity-name) "/table")
        params (web-table/encode-query-params opts)]
    (if (seq params)
      (str base-url "?" params)
      base-url)))

(defn format-field-label
  "Format field name as human-readable label.

   Args:
     field-name: Keyword field name

   Returns:
     Capitalized string label"
  [field-name]
  (-> field-name
      name
      (str/replace #"[-_]" " ")
      str/capitalize))

(defn get-field-errors
  "Extract errors for a specific field from validation result.

   Args:
     errors: Validation errors map or vector
     field-name: Keyword field name

   Returns:
     Vector of error messages for the field"
  [errors field-name]
  (cond
    (map? errors)
    (get errors field-name [])

    (vector? errors)
    (filterv #(= field-name (:field %)) errors)

    :else
    []))
