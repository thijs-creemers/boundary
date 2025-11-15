(ns boundary.user.shell.interceptors
  "User-specific interceptors for the user creation pipeline.
   
   These interceptors handle user domain-specific concerns like:
   - User input validation (required fields, types)
   - User data transformation (normalization, type conversion)
   - User business logic invocation (register-user service call)
   - User-specific response formatting"
  (:require [boundary.shared.core.interceptor-context :as ctx]
            [boundary.shared.core.interceptors :as interceptors]
            [boundary.shared.core.utils.type-conversion :as type-conv]
            [boundary.user.ports :as ports]
            [boundary.user.schema :as schema]))

(def validate-user-creation-input
  "Validates required fields for user creation.
   
   HTTP: Validates request body has email, name, role, tenantId
   CLI: Validates opts has email, name, role, tenant-id
   
   On validation error, adds validation details to context and throws."
  {:name ::validate-user-creation-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  input-data (case interface-type
                               :http (get-in context [:request :parameters :body])
                               :cli (:opts context))]

              ;; Required fields by interface
              (let [required-fields (case interface-type
                                      :http [:email :name :role :tenantId]
                                      :cli [:email :name :role :tenant-id])
                    missing-fields (remove #(get input-data %) required-fields)]

                (if (seq missing-fields)
                  ;; Validation failed
                  (let [validation-error {:type :validation-error
                                          :message (format "Missing required fields: %s"
                                                           (clojure.string/join ", " missing-fields))
                                          :missing-fields missing-fields
                                          :provided-fields (keys input-data)
                                          :interface-type interface-type}]
                    (-> context
                        (ctx/add-validation-error :missing-required-fields validation-error)
                        (ctx/fail-with-exception (ex-info (:message validation-error) validation-error))))

                  ;; Validation passed
                  (ctx/add-breadcrumb context :validation :user-create-input-valid
                                      {:required-fields required-fields
                                       :provided-fields (keys input-data)})))))})

(def transform-user-creation-data
  "Transforms and normalizes user creation input data.
   
   HTTP: Converts camelCase to kebab-case, transforms tenantId string to UUID
   CLI: Normalizes data types, ensures consistent field names
   
   Adds the normalized user-data to context for downstream interceptors."
  {:name ::transform-user-creation-data
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  raw-input (case interface-type
                              :http (get-in context [:request :parameters :body])
                              :cli (:opts context))

                  ;; Transform based on interface
                  user-data (case interface-type
                              :http {:email (:email raw-input)
                                     :name (:name raw-input)
                                     :password (:password raw-input)
                                     :role (keyword (:role raw-input))
                                     :tenant-id (type-conv/string->uuid (:tenantId raw-input))
                                     :active (get raw-input :active true)}

                              :cli {:email (:email raw-input)
                                    :name (:name raw-input)
                                    :role (keyword (:role raw-input))
                                    :tenant-id (:tenant-id raw-input) ; Already UUID from CLI parsing
                                    :active (:active raw-input)})]

              (-> context
                  (assoc :user-data user-data)
                  (ctx/add-breadcrumb :transformation :user-data-normalized
                                      {:interface-type interface-type
                                       :email (:email user-data)
                                       :role (:role user-data)
                                       :tenant-id (:tenant-id user-data)
                                       :active (:active user-data)}))))})

(def invoke-user-registration
  "Invokes the core user registration business logic.
   
   Calls ports/register-user with the normalized user-data.
   Adds the created user result to context for response formatting."
  {:name ::invoke-user-registration
   :enter (fn [context]
            (let [user-data (:user-data context)
                  service (ctx/get-service context)]

              ;; Add operation start breadcrumb
              (let [updated-context (ctx/add-breadcrumb context :operation :user-registration-start
                                                        {:email (:email user-data)
                                                         :role (:role user-data)
                                                         :tenant-id (:tenant-id user-data)})]

                ;; Call the core service
                (let [created-user (ports/register-user service user-data)]

                  ;; Add success breadcrumb and result
                  (-> updated-context
                      (assoc :created-user created-user)
                      (ctx/add-breadcrumb :operation :user-registration-success
                                          {:user-id (:id created-user)
                                           :email (:email created-user)
                                           :role (:role created-user)}))))))})

(def format-user-creation-response
  "Formats the successful user creation response based on interface type.
   
   HTTP: Returns 201 status with camelCase JSON body
   CLI: Returns success status with user data for formatting"
  {:name ::format-user-creation-response
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  created-user (:created-user context)]

              (case interface-type
                :http (assoc context :response
                             {:status 201
                              :body (schema/user-specific-kebab->camel created-user)})

                :cli (assoc context :response
                            {:status 0
                             :entity-type :user
                             :data created-user}))))})

;; Pipeline assembly functions

(defn create-user-creation-pipeline
  "Creates the complete user creation interceptor pipeline.
   
   Pipeline stages:
   1. Context setup (universal)
   2. Logging start (universal)  
   3. Input validation (user-specific)
   4. Data transformation (user-specific)
   5. Business logic invocation (user-specific)
   6. Response formatting (user-specific)
   7. Effects dispatch (universal)
   8. Logging completion (universal)
   9. Metrics completion (universal)
   10. Response shaping (universal)
   
   Args:
     interface-type: :http or :cli
     
   Returns:
     Vector of interceptors for the pipeline"
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-user-creation-input
           transform-user-creation-data
           invoke-user-registration
           format-user-creation-response)

    :cli (interceptors/create-cli-pipeline
          validate-user-creation-input
          transform-user-creation-data
          invoke-user-registration
          format-user-creation-response)))

;; =============================================================================
;; User Get Operation Interceptors
;; =============================================================================

(def validate-user-id-input
  "Validates user ID input for get operations."
  {:name ::validate-user-id-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  user-id (case interface-type
                            :http (get-in context [:request :parameters :path :id])
                            :cli (get-in context [:opts :user-id]))]

              (if user-id
                (-> context
                    (assoc :user-id user-id)
                    (ctx/add-breadcrumb :validation :user-id-valid {:user-id user-id}))

                (let [validation-error {:type :validation-error
                                        :message "User ID is required"
                                        :interface-type interface-type}]
                  (-> context
                      (ctx/add-validation-error :missing-user-id validation-error)
                      (ctx/fail-with-exception (ex-info (:message validation-error) validation-error)))))))})

(def transform-user-id
  "Transforms user ID to UUID for internal use."
  {:name ::transform-user-id
   :enter (fn [context]
            (let [user-id-str (:user-id context)
                  user-id-uuid (if (string? user-id-str)
                                 (type-conv/string->uuid user-id-str)
                                 user-id-str)]

              (-> context
                  (assoc :user-id user-id-uuid)
                  (ctx/add-breadcrumb :transformation :user-id-converted
                                      {:user-id user-id-uuid}))))})

(def fetch-user-by-id
  "Fetches user by ID from the service."
  {:name ::fetch-user-by-id
   :enter (fn [context]
            (let [user-id (:user-id context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :user-fetch-start
                                                      {:user-id user-id})
                  user (ports/get-user-by-id service user-id)]

              (if user
                (-> updated-context
                    (assoc :user user)
                    (ctx/add-breadcrumb :operation :user-fetch-success
                                        {:user-id (:id user)
                                         :email (:email user)
                                         :active (:active user)}))

                (let [not-found-error {:type :user-not-found
                                       :message "User not found"
                                       :user-id user-id}]
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :user-fetch-not-found
                                          {:user-id user-id})
                      (ctx/fail-with-exception (ex-info (:message not-found-error) not-found-error)))))))})

(def format-user-response
  "Formats single user response based on interface type."
  {:name ::format-user-response
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  user (:user context)]

              (case interface-type
                :http (assoc context :response
                             {:status 200
                              :body (schema/user-specific-kebab->camel user)})

                :cli (assoc context :response
                            {:status 0
                             :entity-type :user
                             :data user}))))})

(defn create-user-get-pipeline
  "Creates pipeline for getting a single user by ID."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-user-id-input
           transform-user-id
           fetch-user-by-id
           format-user-response)

    :cli (interceptors/create-cli-pipeline
          validate-user-id-input
          transform-user-id
          fetch-user-by-id
          format-user-response)))

;; ==============================================================================
;; LIST USERS OPERATION INTERCEPTORS
;; ==============================================================================

(def validate-list-users-input
  "Validates input parameters for listing users.
   
   HTTP: Validates query parameters (:tenantId, :limit, :offset)
   CLI: Validates opts (:tenant-id, :limit, :offset)"
  {:name ::validate-list-users-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  input-data (case interface-type
                               :http (get-in context [:request :parameters :query])
                               :cli (:opts context))
                  ;; Extract parameters based on interface
                  tenant-id-str (case interface-type
                                  :http (:tenantId input-data)
                                  :cli (:tenant-id input-data))
                  limit (or (:limit input-data) 20)
                  offset (or (:offset input-data) 0)]

              (cond
                ;; Tenant ID is required
                (nil? tenant-id-str)
                (ctx/fail-with-exception context
                                         (ex-info "Tenant ID is required"
                                                  {:type :validation-error
                                                   :field (case interface-type
                                                            :http :tenantId
                                                            :cli :tenant-id)
                                                   :message "Tenant ID is required for user listing"
                                                   :interface-type interface-type}))

                ;; Validate limit bounds
                (or (not (int? limit)) (< limit 1) (> limit 100))
                (ctx/fail-with-exception context
                                         (ex-info "Invalid limit parameter"
                                                  {:type :validation-error
                                                   :field :limit
                                                   :message "Limit must be between 1 and 100"
                                                   :interface-type interface-type}))

                ;; Validate offset bounds
                (or (not (int? offset)) (< offset 0))
                (ctx/fail-with-exception context
                                         (ex-info "Invalid offset parameter"
                                                  {:type :validation-error
                                                   :field :offset
                                                   :message "Offset must be non-negative"
                                                   :interface-type interface-type}))

                ;; All validations passed
                :else
                (-> context
                    (assoc :raw-query-params input-data)
                    (ctx/add-breadcrumb :operation :list-users-validation-success
                                        {:tenant-id tenant-id-str
                                         :limit limit
                                         :offset offset
                                         :interface-type interface-type})))))})

(def transform-list-users-params
  "Transforms and normalizes list users parameters.
   
   HTTP: Converts tenantId string to UUID, extracts query params
   CLI: Uses tenant-id as-is, extracts options from opts"
  {:name ::transform-list-users-params
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  query-params (:raw-query-params context)
                  ;; Transform parameters based on interface
                  tenant-id (case interface-type
                              :http (type-conv/string->uuid (:tenantId query-params))
                              :cli (:tenant-id query-params)) ;; Already string from CLI parsing
                  options {:limit (or (:limit query-params) 20)
                           :offset (or (:offset query-params) 0)
                           :filter-role (when (:role query-params)
                                          (keyword (:role query-params)))
                           :filter-active (:active query-params)}]

              (-> context
                  (assoc :tenant-id tenant-id
                         :list-options options)
                  (ctx/add-breadcrumb :operation :list-users-transform-complete
                                      {:tenant-id tenant-id
                                       :options options
                                       :interface-type interface-type}))))})

(def fetch-users-by-tenant
  "Fetches users by tenant with options."
  {:name ::fetch-users-by-tenant
   :enter (fn [context]
            (let [tenant-id (:tenant-id context)
                  options (:list-options context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :user-list-start
                                                      {:tenant-id tenant-id
                                                       :options options})]

              (try
                (let [result (ports/list-users-by-tenant service tenant-id options)
                      users (:users result)
                      total-count (:total-count result)]

                  (-> updated-context
                      (assoc :users users
                             :total-count total-count)
                      (ctx/add-breadcrumb :operation :user-list-success
                                          {:user-count (count users)
                                           :total-count total-count
                                           :tenant-id tenant-id})))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :user-list-error
                                          {:tenant-id tenant-id
                                           :error-type (or (:type (ex-data ex)) "unknown")
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-users-list-response
  "Formats the list of users for response."
  {:name ::format-users-list-response
   :enter (fn [context]
            (let [users (:users context)
                  total-count (:total-count context)
                  options (:list-options context)
                  interface-type (:interface-type context)]

              (case interface-type
                :http
                (let [formatted-users (map schema/user-specific-kebab->camel users)
                      response {:status 200
                                :body {:users formatted-users
                                       :totalCount (or total-count 0)
                                       :limit (:limit options)
                                       :offset (:offset options)}}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-list-formatted
                                          {:user-count (count formatted-users)
                                           :response-format :json})))

                :cli
                (let [formatted-users (map #(select-keys % [:id :email :name :role :active]) users)
                      response {:status :success
                                :data {:users formatted-users
                                       :total-count (or total-count 0)
                                       :limit (:limit options)
                                       :offset (:offset options)}
                                :message (str "Found " (count formatted-users) " users")}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-list-formatted
                                          {:user-count (count formatted-users)
                                           :response-format :cli}))))))})

(defn create-user-list-pipeline
  "Creates pipeline for listing users by tenant."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-list-users-input
           transform-list-users-params
           fetch-users-by-tenant
           format-users-list-response)

    :cli (interceptors/create-cli-pipeline
          validate-list-users-input
          transform-list-users-params
          fetch-users-by-tenant
          format-users-list-response)))

;; ==============================================================================
;; UPDATE USER OPERATION INTERCEPTORS
;; ==============================================================================

(def validate-user-update-input
  "Validates input for updating a user.
   
   HTTP: Validates path has id and body has update fields  
   CLI: Validates opts has id and at least one update field"
  {:name ::validate-user-update-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  [user-id-str update-data] (case interface-type
                                              :http [(get-in context [:request :parameters :path :id])
                                                     (get-in context [:request :parameters :body])]
                                              :cli [(:id (:opts context))
                                                    (select-keys (:opts context) [:name :role :active])])]

              (cond
                ;; User ID is required
                (nil? user-id-str)
                (ctx/fail-with-exception context
                                         (ex-info "User ID is required"
                                                  {:type :validation-error
                                                   :field (case interface-type :http :id :cli :id)
                                                   :message (case interface-type
                                                              :http "User ID is required in path"
                                                              :cli "User ID is required (--id)")
                                                   :interface-type interface-type}))

                ;; Body/opts must contain at least one update field
                (or (nil? update-data) (empty? update-data))
                (ctx/fail-with-exception context
                                         (ex-info "Update data is required"
                                                  {:type :validation-error
                                                   :field (case interface-type :http :body :cli :opts)
                                                   :message (case interface-type
                                                              :http "At least one field must be provided for update"
                                                              :cli "At least one of --name, --role, or --active is required")
                                                   :interface-type interface-type}))

                ;; Validate allowed update fields
                (not-every? #{:name :role :active} (keys update-data))
                (ctx/fail-with-exception context
                                         (ex-info "Invalid update fields"
                                                  {:type :validation-error
                                                   :field (case interface-type :http :body :cli :opts)
                                                   :message "Only name, role, and active fields can be updated"
                                                   :allowed-fields [:name :role :active]
                                                   :provided-fields (keys update-data)
                                                   :interface-type interface-type}))

                ;; All validations passed
                :else
                (-> context
                    (assoc :raw-user-id user-id-str
                           :raw-update-data update-data)
                    (ctx/add-breadcrumb :operation :user-update-validation-success
                                        {:user-id user-id-str
                                         :update-fields (keys update-data)
                                         :interface-type interface-type})))))})

(def transform-user-update-data
  "Transforms and normalizes user update data.
   
   HTTP: Converts userId string to UUID, processes body data
   CLI: Uses provided UUID directly, processes opts data"
  {:name ::transform-user-update-data
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  user-id-str (:raw-user-id context)
                  update-data (:raw-update-data context)
                  ;; Transform user ID (CLI already provides UUID, HTTP provides string)
                  user-id (case interface-type
                            :http (type-conv/string->uuid user-id-str)
                            :cli user-id-str) ; Already a UUID from CLI parsing
                  ;; Transform update data (same structure for both interfaces)
                  transformed-updates (cond-> {}
                                        (:name update-data) (assoc :name (:name update-data))
                                        (:role update-data) (assoc :role (case interface-type
                                                                           :http (keyword (:role update-data))
                                                                           :cli (:role update-data))) ; Already a keyword from CLI parsing
                                        (some? (:active update-data)) (assoc :active (:active update-data)))]

              (-> context
                  (assoc :user-id user-id
                         :update-data transformed-updates)
                  (ctx/add-breadcrumb :operation :user-update-transform-complete
                                      {:user-id user-id
                                       :updates transformed-updates
                                       :interface-type interface-type}))))})

(def fetch-current-user-for-update
  "Fetches current user data for update validation."
  {:name ::fetch-current-user-for-update
   :enter (fn [context]
            (let [user-id (:user-id context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :user-fetch-for-update-start
                                                      {:user-id user-id})]

              (let [current-user (ports/get-user-by-id service user-id)]
                (if current-user
                  (-> updated-context
                      (assoc :current-user current-user)
                      (ctx/add-breadcrumb :operation :user-fetch-for-update-success
                                          {:user-id user-id
                                           :current-role (:role current-user)
                                           :current-active (:active current-user)}))

                  (let [not-found-error {:type :user-not-found
                                         :message "User not found"
                                         :user-id user-id}]
                    (-> updated-context
                        (ctx/add-breadcrumb :operation :user-fetch-for-update-not-found
                                            {:user-id user-id})
                        (ctx/fail-with-exception (ex-info (:message not-found-error) not-found-error))))))))})

(def apply-user-updates
  "Applies updates to user and persists changes."
  {:name ::apply-user-updates
   :enter (fn [context]
            (let [current-user (:current-user context)
                  update-data (:update-data context)
                  service (ctx/get-service context)
                  updated-user (merge current-user update-data)
                  updated-context (ctx/add-breadcrumb context :operation :user-update-start
                                                      {:user-id (:id current-user)
                                                       :changes update-data})]

              (try
                (let [result (ports/update-user-profile service updated-user)]
                  (-> updated-context
                      (assoc :updated-user result)
                      (ctx/add-breadcrumb :operation :user-update-success
                                          {:user-id (:id result)
                                           :updated-fields (keys update-data)
                                           :role-change (when (:role update-data)
                                                          {:from (:role current-user)
                                                           :to (:role result)})})))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :user-update-error
                                          {:user-id (:id current-user)
                                           :error-type (or (:type (ex-data ex)) "unknown")
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-user-update-response
  "Formats the updated user for response."
  {:name ::format-user-update-response
   :enter (fn [context]
            (let [updated-user (:updated-user context)
                  interface-type (:interface-type context)]

              (case interface-type
                :http
                (let [response {:status 200
                                :body (schema/user-specific-kebab->camel updated-user)}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-update-formatted
                                          {:user-id (:id updated-user)
                                           :response-format :json})))

                :cli
                (let [response {:status :success
                                :data (select-keys updated-user [:id :email :name :role :active])
                                :message (str "User " (:email updated-user) " updated successfully")}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-update-formatted
                                          {:user-id (:id updated-user)
                                           :response-format :cli}))))))})

(defn create-user-update-pipeline
  "Creates pipeline for updating a user."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-user-update-input
           transform-user-update-data
           fetch-current-user-for-update
           apply-user-updates
           format-user-update-response)

    :cli (interceptors/create-cli-pipeline
          validate-user-update-input
          transform-user-update-data
          fetch-current-user-for-update
          apply-user-updates
          format-user-update-response)))

;; ==============================================================================
;; DELETE USER OPERATION INTERCEPTORS
;; ==============================================================================

(def validate-user-delete-input
  "Validates input for deleting (deactivating) a user.
   
   HTTP: Validates path has id parameter
   CLI: Validates opts has id parameter"
  {:name ::validate-user-delete-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  user-id-str (case interface-type
                                :http (get-in context [:request :parameters :path :id])
                                :cli (:id (:opts context)))]

              (if (nil? user-id-str)
                (ctx/fail-with-exception context
                                         (ex-info "User ID is required"
                                                  {:type :validation-error
                                                   :field :id
                                                   :message (case interface-type
                                                              :http "User ID is required in path"
                                                              :cli "User ID is required (--id)")
                                                   :interface-type interface-type}))

                (-> context
                    (assoc :raw-user-id user-id-str)
                    (ctx/add-breadcrumb :operation :user-delete-validation-success
                                        {:user-id user-id-str
                                         :interface-type interface-type})))))})

(def transform-user-delete-id
  "Transforms user ID for deletion.
   
   HTTP: Converts string ID to UUID
   CLI: Uses UUID directly (already parsed)"
  {:name ::transform-user-delete-id
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  user-id-str (:raw-user-id context)
                  user-id (case interface-type
                            :http (type-conv/string->uuid user-id-str)
                            :cli user-id-str)] ; Already a UUID from CLI parsing

              (-> context
                  (assoc :user-id user-id)
                  (ctx/add-breadcrumb :operation :user-delete-transform-complete
                                      {:user-id user-id
                                       :interface-type interface-type}))))})

(def deactivate-user-account
  "Deactivates (soft deletes) the user account."
  {:name ::deactivate-user-account
   :enter (fn [context]
            (let [user-id (:user-id context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :user-deactivate-start
                                                      {:user-id user-id})]

              (try
                (let [result (ports/deactivate-user service user-id)]
                  (-> updated-context
                      (assoc :deactivation-result result)
                      (ctx/add-breadcrumb :operation :user-deactivate-success
                                          {:user-id user-id
                                           :success result})))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :user-deactivate-error
                                          {:user-id user-id
                                           :error-type (or (:type (ex-data ex)) "unknown")
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-user-delete-response
  "Formats the deletion response."
  {:name ::format-user-delete-response
   :enter (fn [context]
            (let [user-id (:user-id context)
                  interface-type (:interface-type context)]

              (case interface-type
                :http
                (let [response {:status 204}] ; No content for successful deletion
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-delete-formatted
                                          {:user-id user-id
                                           :response-format :json})))

                :cli
                (let [response {:status :success
                                :data {:user-id user-id}
                                :message "User deactivated successfully"}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :user-delete-formatted
                                          {:user-id user-id
                                           :response-format :cli}))))))})

(defn create-user-delete-pipeline
  "Creates pipeline for deleting (deactivating) a user."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-user-delete-input
           transform-user-delete-id
           deactivate-user-account
           format-user-delete-response)

    :cli (interceptors/create-cli-pipeline
          validate-user-delete-input
          transform-user-delete-id
          deactivate-user-account
          format-user-delete-response)))

;; ==============================================================================
;; SESSION MANAGEMENT INTERCEPTORS
;; ==============================================================================

;; -----------------------------------------------------------------------------
;; CREATE SESSION (LOGIN) INTERCEPTORS
;; -----------------------------------------------------------------------------

(def validate-session-creation-input
  "Validates input for creating a session (login).
   
   HTTP: Validates body has userId and tenantId
   CLI: Validates opts has user-id and tenant-id (or email/password for authentication)"
  {:name ::validate-session-creation-input
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  input-data (case interface-type
                               :http (get-in context [:request :parameters :body])
                               :cli (:opts context))
                  ;; Extract required fields based on interface
                  [user-id-str tenant-id-str] (case interface-type
                                                :http [(:userId input-data) (:tenantId input-data)]
                                                :cli [(:user-id input-data) (:tenant-id input-data)])]

              (cond
                ;; User ID is required (HTTP specific - CLI might use email/password instead)
                (and (= interface-type :http) (nil? user-id-str))
                (ctx/fail-with-exception context
                                         (ex-info "User ID is required"
                                                  {:type :validation-error
                                                   :field :userId
                                                   :message "User ID is required for session creation"
                                                   :interface-type interface-type}))

                ;; For CLI, check if we have either user-id or email/password
                (and (= interface-type :cli)
                     (nil? user-id-str)
                     (or (nil? (:email input-data)) (nil? (:password input-data))))
                (ctx/fail-with-exception context
                                         (ex-info "User ID or email/password is required"
                                                  {:type :validation-error
                                                   :field [:user-id :email :password]
                                                   :message "Either user-id or email/password combination is required"
                                                   :interface-type interface-type}))

                ;; Tenant ID is required for HTTP but optional for CLI (can be inferred)
                (and (= interface-type :http) (nil? tenant-id-str))
                (ctx/fail-with-exception context
                                         (ex-info "Tenant ID is required"
                                                  {:type :validation-error
                                                   :field :tenantId
                                                   :message "Tenant ID is required for session creation"
                                                   :interface-type interface-type}))

                ;; All validations passed
                :else
                (-> context
                    (assoc :raw-session-data input-data)
                    (ctx/add-breadcrumb :operation :session-create-validation-success
                                        {:user-id user-id-str
                                         :tenant-id tenant-id-str
                                         :has-email (some? (:email input-data))
                                         :interface-type interface-type})))))})

(def transform-session-creation-data
  "Transforms and normalizes session creation data.
   
   HTTP: Converts userId and tenantId strings to UUIDs, extracts device info
   CLI: Uses provided data, handles both user-id and email/password flows"
  {:name ::transform-session-creation-data
   :enter (fn [context]
            (let [interface-type (ctx/get-interface-type context)
                  body (:raw-session-data context)

                  session-data (case interface-type
                                 :http {:user-id (type-conv/string->uuid (:userId body))
                                        :tenant-id (type-conv/string->uuid (:tenantId body))
                                        :user-agent (get-in body [:deviceInfo :userAgent])
                                        :ip-address (get-in body [:deviceInfo :ipAddress])}

                                 :cli (cond-> {}
                                        (:user-id body) (assoc :user-id (:user-id body))
                                        (:tenant-id body) (assoc :tenant-id (:tenant-id body))
                                        (:email body) (assoc :email (:email body))
                                        (:password body) (assoc :password (:password body))
                                        true (assoc :user-agent "CLI"
                                                    :ip-address "127.0.0.1")))]

              (-> context
                  (assoc :session-data session-data)
                  (ctx/add-breadcrumb :operation :session-create-transform-complete
                                      {:user-id (:user-id session-data)
                                       :tenant-id (:tenant-id session-data)
                                       :has-email (some? (:email session-data))
                                       :user-agent (:user-agent session-data)
                                       :interface-type interface-type}))))})

(def authenticate-user-session
  "Authenticates user and creates session."
  {:name ::authenticate-user-session
   :enter (fn [context]
            (let [session-data (:session-data context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :user-authenticate-start
                                                      {:user-id (:user-id session-data)
                                                       :tenant-id (:tenant-id session-data)})]

              (try
                (let [session (ports/authenticate-user service session-data)
                      masked-token (str (take 8 (:token session)) "...")]

                  (-> updated-context
                      (assoc :session session)
                      (ctx/add-breadcrumb :operation :user-authenticate-success
                                          {:user-id (:user-id session)
                                           :session-token masked-token
                                           :expires-at (:expires-at session)})))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :user-authenticate-error
                                          {:user-id (:user-id session-data)
                                           :error-type (or (:type (ex-data ex)) "unknown")
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-session-creation-response
  "Formats the created session for response."
  {:name ::format-session-creation-response
   :enter (fn [context]
            (let [session (:session context)
                  interface-type (:interface-type context)]

              (case interface-type
                :http
                (let [response {:status 201
                                :body (schema/user-specific-kebab->camel session)}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :session-create-formatted
                                          {:user-id (:user-id session)
                                           :response-format :json})))

                :cli
                (let [response {:status :success
                                :data (select-keys session [:user-id :tenant-id :token :expires-at])
                                :message "Session created successfully"}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :session-create-formatted
                                          {:user-id (:user-id session)
                                           :response-format :cli}))))))})

(defn create-session-creation-pipeline
  "Creates pipeline for creating a session (login)."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-session-creation-input
           transform-session-creation-data
           authenticate-user-session
           format-session-creation-response)

    :cli (interceptors/create-cli-pipeline
          validate-session-creation-input
          transform-session-creation-data
          authenticate-user-session
          format-session-creation-response)))

;; -----------------------------------------------------------------------------
;; VALIDATE SESSION INTERCEPTORS
;; -----------------------------------------------------------------------------

(def validate-session-token-input
  "Validates session token input."
  {:name ::validate-session-token-input
   :enter (fn [context]
            (let [path-params (get-in context [:request :parameters :path])
                  session-token (:token path-params)]

              (if (nil? session-token)
                (ctx/fail-with-exception context
                                         (ex-info "Session token is required"
                                                  {:type :validation-error
                                                   :field :token
                                                   :message "Session token is required in path"}))

                (-> context
                    (assoc :session-token session-token
                           :masked-token (str (take 8 session-token) "..."))
                    (ctx/add-breadcrumb :operation :session-validate-input-success
                                        {:session-token (str (take 8 session-token) "...")})))))})

(def validate-user-session
  "Validates the session token."
  {:name ::validate-user-session
   :enter (fn [context]
            (let [session-token (:session-token context)
                  masked-token (:masked-token context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :session-validate-start
                                                      {:session-token masked-token})]

              (try
                (let [session (ports/validate-session service session-token)]
                  (if session
                    (-> updated-context
                        (assoc :session session
                               :session-valid true)
                        (ctx/add-breadcrumb :operation :session-validate-success
                                            {:user-id (:user-id session)
                                             :session-token masked-token
                                             :valid true}))

                    (-> updated-context
                        (assoc :session-valid false)
                        (ctx/add-breadcrumb :operation :session-validate-invalid
                                            {:session-token masked-token
                                             :valid false})
                        (ctx/fail-with-exception (ex-info "Session not found or expired"
                                                          {:type :session-not-found
                                                           :valid false
                                                           :token session-token})))))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :session-validate-error
                                          {:session-token masked-token
                                           :error-type (or (:type (ex-data ex)) "unknown")
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-session-validation-response
  "Formats the session validation response."
  {:name ::format-session-validation-response
   :enter (fn [context]
            (let [session (:session context)
                  interface-type (:interface-type context)]

              (case interface-type
                :http
                (let [response {:status 200
                                :body {:valid true
                                       :userId (type-conv/uuid->string (:user-id session))
                                       :tenantId (type-conv/uuid->string (:tenant-id session))
                                       :expiresAt (type-conv/instant->string (:expires-at session))}}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :session-validate-formatted
                                          {:user-id (:user-id session)
                                           :response-format :json})))

                :cli
                (let [response {:status :success
                                :data {:valid true
                                       :user-id (:user-id session)
                                       :tenant-id (:tenant-id session)
                                       :expires-at (:expires-at session)}
                                :message "Session is valid"}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :session-validate-formatted
                                          {:user-id (:user-id session)
                                           :response-format :cli}))))))})

(defn create-session-validation-pipeline
  "Creates pipeline for validating a session."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-session-token-input
           validate-user-session
           format-session-validation-response)

    :cli (interceptors/create-cli-pipeline
          validate-session-token-input
          validate-user-session
          format-session-validation-response)))

;; -----------------------------------------------------------------------------
;; INVALIDATE SESSION (LOGOUT) INTERCEPTORS
;; -----------------------------------------------------------------------------

(def invalidate-user-session
  "Invalidates (logs out) the session."
  {:name ::invalidate-user-session
   :enter (fn [context]
            (let [session-token (:session-token context)
                  masked-token (:masked-token context)
                  service (ctx/get-service context)
                  updated-context (ctx/add-breadcrumb context :operation :session-logout-start
                                                      {:session-token masked-token})]

              (try
                (let [result (ports/logout-user service session-token)]
                  (-> updated-context
                      (assoc :logout-result result)
                      (ctx/add-breadcrumb :operation :session-logout-success
                                          {:session-token masked-token})))

                (catch Exception ex
                  (-> updated-context
                      (ctx/add-breadcrumb :operation :session-logout-error
                                          {:session-token masked-token
                                           :error-type (or (:type (ex-data ex)) "unknown")
                                           :error-message (.getMessage ex)})
                      (ctx/fail-with-exception ex))))))})

(def format-session-invalidation-response
  "Formats the session invalidation response."
  {:name ::format-session-invalidation-response
   :enter (fn [context]
            (let [masked-token (:masked-token context)
                  interface-type (:interface-type context)]

              (case interface-type
                :http
                (let [response {:status 204}] ; No content for successful logout
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :session-logout-formatted
                                          {:session-token masked-token
                                           :response-format :json})))

                :cli
                (let [response {:status :success
                                :data {:session-token masked-token}
                                :message "Session invalidated successfully"}]
                  (-> context
                      (assoc :response response)
                      (ctx/add-breadcrumb :operation :session-logout-formatted
                                          {:session-token masked-token
                                           :response-format :cli}))))))})

(defn create-session-invalidation-pipeline
  "Creates pipeline for invalidating a session (logout)."
  [interface-type]
  (case interface-type
    :http (interceptors/create-http-pipeline
           validate-session-token-input
           invalidate-user-session
           format-session-invalidation-response)

    :cli (interceptors/create-cli-pipeline
          validate-session-token-input
          invalidate-user-session
          format-session-invalidation-response)))