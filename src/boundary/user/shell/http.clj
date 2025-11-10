(ns boundary.user.shell.http
  "HTTP routing and handlers for user module using common routing infrastructure.

   This namespace implements the REST API for user and session management,
   providing a clean HTTP interface to the user service layer.

   Endpoints:
   - POST   /users           - Create user
   - GET    /users/:id       - Get user by ID
   - GET    /users           - List users (with filters)
   - PUT    /users/:id       - Update user
   - DELETE /users/:id       - Soft delete user
   - POST   /sessions        - Create session (login)
   - GET    /sessions/:token - Validate session
   - DELETE /sessions/:token - Invalidate session (logout)"
  (:require [clojure.tools.logging :as log]
            [boundary.shell.interfaces.http.routes :as routes]
            [boundary.shared.core.utils.type-conversion :as type-conversion]
            [boundary.user.schema :as schema]
            [boundary.user.ports :as ports]
            [boundary.error-reporting.core :as error-reporting]))

;; =============================================================================
;; User-Specific Error Mappings
;; =============================================================================

(def user-error-mappings
  "User module specific error type mappings for RFC 7807 problem details."
  {:user-exists [409 "User Already Exists"]
   :user-not-found [404 "User Not Found"]
   :session-not-found [404 "Session Not Found"]
   :deletion-not-allowed [403 "Deletion Not Allowed"]
   :hard-deletion-not-allowed [403 "Hard Deletion Not Allowed"]})

;; =============================================================================
;; Error Reporting Helpers
;; =============================================================================

(defn add-http-breadcrumb
  "Add HTTP request breadcrumb to error context if available."
  [request operation details]
  (when-let [error-context (:error-context request)]
    (error-reporting/add-breadcrumb error-context
                                    (str "HTTP " (name (:request-method request)) " " operation)
                                    "http"
                                    :info
                                    (merge {:method (:request-method request)
                                            :uri (:uri request)
                                            :operation operation}
                                           details))))

(defn add-user-operation-breadcrumb
  "Add user operation breadcrumb to error context if available."
  [request operation target details]
  (when-let [error-context (:error-context request)]
    (error-reporting/track-user-action error-context operation target)
    (error-reporting/add-breadcrumb error-context
                                    (str "User operation: " operation " on " target)
                                    "user"
                                    :info
                                    details)))

(defn add-validation-breadcrumb
  "Add validation error breadcrumb to error context if available."
  [request operation errors]
  (when-let [error-context (:error-context request)]
    (error-reporting/add-breadcrumb error-context
                                    (str "Validation failed for " operation)
                                    "validation"
                                    :warning
                                    {:operation operation
                                     :error-count (count errors)
                                     :errors (mapv #(select-keys % [:field :code :message]) errors)})))

;; =============================================================================
;; User Handlers
;; =============================================================================

(defn create-user-handler
  "POST /api/users - Create a new user."
  [user-service]
  (fn [{{:keys [body]} :parameters :as request}]
    (add-http-breadcrumb request "create-user" {:email (:email body)
                                                :tenant-id (:tenantId body)})
    (log/info "Creating user" {:email (:email body)})
    (try
      (let [user-data {:email (:email body)
                       :name (:name body)
                       :password (:password body)
                       :role (keyword (:role body))
                       :tenant-id (type-conversion/string->uuid (:tenantId body))
                       :active (get body :active true)}]
        (add-user-operation-breadcrumb request "create" "user"
                                       {:email (:email user-data)
                                        :role (:role user-data)
                                        :tenant-id (:tenant-id user-data)})
        (let [created-user (ports/register-user user-service user-data)]
          (add-http-breadcrumb request "create-user-success"
                               {:user-id (:id created-user)
                                :email (:email created-user)})
          {:status 201
           :body (schema/user-specific-kebab->camel created-user)}))
      (catch Exception ex
        (add-http-breadcrumb request "create-user-error"
                             {:error-type (or (:type (ex-data ex)) "unknown")
                              :error-message (.getMessage ex)})
        (throw ex)))))

(defn get-user-handler
  "GET /api/users/:id - Get user by ID."
  [user-service]
  (fn [{{:keys [path]} :parameters :as request}]
    (add-http-breadcrumb request "get-user" {:user-id (:id path)})
    (try
      (let [user-id (type-conversion/string->uuid (:id path))]
        (add-user-operation-breadcrumb request "get" "user" {:user-id user-id})
        (let [user (ports/get-user-by-id user-service user-id)]
          (if user
            (do
              (add-http-breadcrumb request "get-user-success"
                                   {:user-id (:id user)
                                    :email (:email user)
                                    :active (:active user)})
              {:status 200
               :body (schema/user-specific-kebab->camel user)})
            (do
              (add-http-breadcrumb request "get-user-not-found" {:user-id (:id path)})
              (throw (ex-info "User not found"
                              {:type :user-not-found
                               :user-id (:id path)}))))))
      (catch Exception ex
        (add-http-breadcrumb request "get-user-error"
                             {:error-type (or (:type (ex-data ex)) "unknown")
                              :error-message (.getMessage ex)
                              :user-id (:id path)})
        (throw ex)))))

(defn list-users-handler
  "GET /api/users - List users with pagination and filters."
  [user-service]
  (fn [{{:keys [query]} :parameters :as request}]
    (add-http-breadcrumb request "list-users" {:tenant-id (:tenantId query)
                                               :limit (:limit query)
                                               :offset (:offset query)})
    (try
      (let [tenant-id (type-conversion/string->uuid (:tenantId query))
            options {:limit (or (:limit query) 20)
                     :offset (or (:offset query) 0)
                     :filter-role (when (:role query) (keyword (:role query)))
                     :filter-active (:active query)}]
        (add-user-operation-breadcrumb request "list" "users"
                                       {:tenant-id tenant-id
                                        :filters options})
        (let [result (ports/list-users-by-tenant user-service tenant-id options)
              users (map schema/user-specific-kebab->camel (:users result))]
          (add-http-breadcrumb request "list-users-success"
                               {:user-count (count users)
                                :total-count (:total-count result)
                                :tenant-id tenant-id})
          {:status 200
           :body {:users users
                  :totalCount (or (:total-count result) 0)
                  :limit (:limit options)
                  :offset (:offset options)}}))
      (catch Exception ex
        (add-http-breadcrumb request "list-users-error"
                             {:error-type (or (:type (ex-data ex)) "unknown")
                              :error-message (.getMessage ex)
                              :tenant-id (:tenantId query)})
        (throw ex)))))

(defn update-user-handler
  "PUT /api/users/:id - Update user."
  [user-service]
  (fn [{{:keys [path body]} :parameters :as request}]
    (add-http-breadcrumb request "update-user" {:user-id (:id path)
                                                :updates (keys body)})
    (try
      (let [user-id (type-conversion/string->uuid (:id path))]
        (add-user-operation-breadcrumb request "get" "user-for-update" {:user-id user-id})
        (let [current-user (ports/get-user-by-id user-service user-id)]
          (if-not current-user
            (do
              (add-http-breadcrumb request "update-user-not-found" {:user-id (:id path)})
              (throw (ex-info "User not found"
                              {:type :user-not-found
                               :user-id (:id path)})))
            (let [updated-user (merge current-user
                                      (when (:name body) {:name (:name body)})
                                      (when (:role body) {:role (keyword (:role body))})
                                      (when (some? (:active body)) {:active (:active body)}))]
              (add-user-operation-breadcrumb request "update" "user"
                                             {:user-id user-id
                                              :changes (keys body)
                                              :role-change (when (:role body)
                                                             {:from (:role current-user)
                                                              :to (keyword (:role body))})})
              (let [result (ports/update-user-profile user-service updated-user)]
                (add-http-breadcrumb request "update-user-success"
                                     {:user-id (:id result)
                                      :email (:email result)
                                      :updated-fields (keys body)})
                {:status 200
                 :body (schema/user-specific-kebab->camel result)})))))
      (catch Exception ex
        (add-http-breadcrumb request "update-user-error"
                             {:error-type (or (:type (ex-data ex)) "unknown")
                              :error-message (.getMessage ex)
                              :user-id (:id path)})
        (throw ex)))))

(defn delete-user-handler
  "DELETE /api/users/:id - Soft delete user."
  [user-service]
  (fn [{{:keys [path]} :parameters :as request}]
    (add-http-breadcrumb request "delete-user" {:user-id (:id path)})
    (try
      (let [user-id (type-conversion/string->uuid (:id path))]
        (add-user-operation-breadcrumb request "delete" "user" {:user-id user-id})
        (ports/deactivate-user user-service user-id)
        (add-http-breadcrumb request "delete-user-success" {:user-id (:id path)})
        {:status 204})
      (catch Exception ex
        (add-http-breadcrumb request "delete-user-error"
                             {:error-type (or (:type (ex-data ex)) "unknown")
                              :error-message (.getMessage ex)
                              :user-id (:id path)})
        (throw ex)))))

;; =============================================================================
;; Session Handlers
;; =============================================================================

(defn create-session-handler
  "POST /api/sessions - Create session."
  [user-service]
  (fn [{{:keys [body]} :parameters :as request}]
    (add-http-breadcrumb request "create-session" {:user-id (:userId body)
                                                   :tenant-id (:tenantId body)})
    (log/info "Creating session" {:user-id (:userId body)})
    (try
      (let [session-data {:user-id (type-conversion/string->uuid (:userId body))
                          :tenant-id (type-conversion/string->uuid (:tenantId body))
                          :user-agent (get-in body [:deviceInfo :userAgent])
                          :ip-address (get-in body [:deviceInfo :ipAddress])}]
        (add-user-operation-breadcrumb request "authenticate" "user"
                                       {:user-id (:user-id session-data)
                                        :tenant-id (:tenant-id session-data)
                                        :user-agent (:user-agent session-data)})
        (let [session (ports/authenticate-user user-service session-data)]
          (add-http-breadcrumb request "create-session-success"
                               {:user-id (:user-id session)
                                :session-token (str (take 8 (:token session)) "...")
                                :expires-at (:expires-at session)})
          {:status 201
           :body (schema/user-specific-kebab->camel session)}))
      (catch Exception ex
        (add-http-breadcrumb request "create-session-error"
                             {:error-type (or (:type (ex-data ex)) "unknown")
                              :error-message (.getMessage ex)
                              :user-id (:userId body)})
        (throw ex)))))

(defn validate-session-handler
  "GET /api/sessions/:token - Validate session."
  [user-service]
  (fn [{{:keys [path]} :parameters :as request}]
    (let [session-token (:token path)
          masked-token (str (take 8 session-token) "...")]
      (add-http-breadcrumb request "validate-session" {:session-token masked-token})
      (try
        (add-user-operation-breadcrumb request "validate" "session"
                                       {:session-token masked-token})
        (let [session (ports/validate-session user-service session-token)]
          (if session
            (do
              (add-http-breadcrumb request "validate-session-success"
                                   {:user-id (:user-id session)
                                    :session-token masked-token
                                    :valid true})
              {:status 200
               :body {:valid true
                      :userId (type-conversion/uuid->string (:user-id session))
                      :tenantId (type-conversion/uuid->string (:tenant-id session))
                      :expiresAt (type-conversion/instant->string (:expires-at session))}})
            (do
              (add-http-breadcrumb request "validate-session-invalid"
                                   {:session-token masked-token
                                    :valid false})
              (throw (ex-info "Session not found or expired"
                              {:type :session-not-found
                               :valid false
                               :token session-token})))))
        (catch Exception ex
          (add-http-breadcrumb request "validate-session-error"
                               {:error-type (or (:type (ex-data ex)) "unknown")
                                :error-message (.getMessage ex)
                                :session-token masked-token})
          (throw ex))))))

(defn invalidate-session-handler
  "DELETE /api/sessions/:token - Invalidate session."
  [user-service]
  (fn [{{:keys [path]} :parameters :as request}]
    (let [session-token (:token path)
          masked-token (str (take 8 session-token) "...")]
      (add-http-breadcrumb request "invalidate-session" {:session-token masked-token})
      (try
        (add-user-operation-breadcrumb request "logout" "session"
                                       {:session-token masked-token})
        (ports/logout-user user-service session-token)
        (add-http-breadcrumb request "invalidate-session-success"
                             {:session-token masked-token})
        {:status 204}
        (catch Exception ex
          (add-http-breadcrumb request "invalidate-session-error"
                               {:error-type (or (:type (ex-data ex)) "unknown")
                                :error-message (.getMessage ex)
                                :session-token masked-token})
          (throw ex))))))

;; =============================================================================
;; User Module Routes
;; =============================================================================

(defn user-routes
  "Define user module specific routes.

   Args:
     user-service: User service instance

   Returns:
     Vector of Reitit route definitions for user module"
  [user-service]
  [["/users" {:post {:handler (create-user-handler user-service)
                     :summary "Create user"
                     :tags ["users"]
                     :parameters {:body [:map
                                         [:email :string]
                                         [:name :string]
                                         [:password :string]
                                         [:role [:enum "admin" "user" "viewer"]]
                                         [:tenantId :string]
                                         [:active {:optional true} :boolean]]}}
              :get {:handler (list-users-handler user-service)
                    :summary "List users with pagination and filters"
                    :tags ["users"]
                    :parameters {:query [:map
                                         [:tenantId :string]
                                         [:limit {:optional true} :int]
                                         [:offset {:optional true} :int]
                                         [:role {:optional true} [:enum "admin" "user" "viewer"]]
                                         [:active {:optional true} :boolean]]}}}]
   ["/users/:id" {:get {:handler (get-user-handler user-service)
                        :summary "Get user by ID"
                        :tags ["users"]
                        :parameters {:path [:map [:id :string]]}}
                  :put {:handler (update-user-handler user-service)
                        :summary "Update user"
                        :tags ["users"]
                        :parameters {:path [:map [:id :string]]
                                     :body [:map
                                            [:name {:optional true} :string]
                                            [:role {:optional true} [:enum "admin" "user" "viewer"]]
                                            [:active {:optional true} :boolean]]}}
                  :delete {:handler (delete-user-handler user-service)
                           :summary "Soft delete user"
                           :tags ["users"]
                           :parameters {:path [:map [:id :string]]}}}]
   ["/sessions" {:post {:handler (create-session-handler user-service)
                        :summary "Create session (login)"
                        :tags ["sessions"]
                        :parameters {:body [:map
                                            [:userId :string]
                                            [:tenantId :string]
                                            [:deviceInfo {:optional true} [:map
                                                                           [:userAgent {:optional true} :string]
                                                                           [:ipAddress {:optional true} :string]]]]}}}]
   ["/sessions/:token" {:get {:handler (validate-session-handler user-service)
                              :summary "Validate session"
                              :tags ["sessions"]
                              :parameters {:path [:map [:token :string]]}}
                        :delete {:handler (invalidate-session-handler user-service)
                                 :summary "Invalidate session (logout)"
                                 :tags ["sessions"]
                                 :parameters {:path [:map [:token :string]]}}}]])

(defn user-health-checks
  "Additional health checks specific to user module.

   Args:
     user-service: User service instance

   Returns:
     Map of health check functions"
  [_user-service]
  {:database (fn []
               ;; Simple check to verify database connectivity
               ;; Could be enhanced to check specific user tables
               {:status :healthy
                :details "User database connection is healthy"})
   :user-service (fn []
                   ;; Check if user service is responsive
                   {:status :healthy
                    :details "User service is responding"})})

;; =============================================================================
;; Router Configuration
;; =============================================================================

(defn create-router
  "Create Reitit router for user module using common routing infrastructure.

   Args:
     user-service: User service instance
     config: Application configuration map

   Returns:
     Reitit router with common routes + user module routes"
  [user-service config]
  (routes/create-router config
                        (user-routes user-service)
                        :additional-health-checks (user-health-checks user-service)
                        :error-mappings user-error-mappings))

(defn create-handler
  "Create Ring handler for user module using common routing infrastructure.

   Args:
     user-service: User service instance
     config: Application configuration map (optional)

   Returns:
     Ring handler function with common middleware and routes"
  ([user-service]
   (create-handler user-service {}))
  ([user-service config]
   (let [router (routes/create-router config
                                      (user-routes user-service)
                                      :additional-health-checks (user-health-checks user-service)
                                      :error-mappings user-error-mappings)]
     (routes/create-handler router))))

(defn create-app
  "Create complete Ring application for user module.

   This is a convenience function that creates a complete application
   with all common routes, middleware, and user-specific functionality.

   Args:
     user-service: User service instance
     config: Application configuration map (optional)

   Returns:
     Complete Ring application function"
  ([user-service]
   (create-app user-service {}))
  ([user-service config]
   (routes/create-app config
                      (user-routes user-service)
                      :additional-health-checks (user-health-checks user-service)
                      :error-mappings user-error-mappings)))

