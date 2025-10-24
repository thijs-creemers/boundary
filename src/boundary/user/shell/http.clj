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
            [boundary.shared.utils.type-conversion :as type-conversion]
            [boundary.user.schema :as schema]
            [boundary.user.ports :as ports]))

;; =============================================================================
;; User-Specific Error Mappings
;; =============================================================================

(def ^:private user-error-mappings
  "User module specific error type mappings for RFC 7807 problem details."
  {:user-exists [409 "User Already Exists"]
   :user-not-found [404 "User Not Found"]
   :session-not-found [404 "Session Not Found"]
   :deletion-not-allowed [403 "Deletion Not Allowed"]
   :hard-deletion-not-allowed [403 "Hard Deletion Not Allowed"]})

;; =============================================================================
;; User Handlers
;; =============================================================================

(defn create-user-handler
  "POST /api/users - Create a new user."
  [user-service]
  (fn [{{:keys [body]} :parameters}]
    (log/info "Creating user" {:email (:email body)})
    (let [user-data {:email (:email body)
                     :name (:name body)
                     :password (:password body)
                     :role (keyword (:role body))
                     :tenant-id (type-conversion/string->uuid (:tenantId body))
                     :active (get body :active true)}
          created-user (ports/create-user user-service user-data)]
      {:status 201
       :body (schema/user-specific-kebab->camel created-user)})))

(defn get-user-handler
  "GET /api/users/:id - Get user by ID."
  [user-service]
  (fn [{{:keys [path]} :parameters}]
    (let [user-id (type-conversion/string->uuid (:id path))
          user (ports/find-user-by-id user-service user-id)]
      (if user
        {:status 200
         :body (schema/user-specific-kebab->camel user)}
        {:status 404
         :body {:type "https://boundary.example.com/problems/not-found"
                :title "User Not Found"
                :status 404
                :detail (str "User with ID " (:id path) " not found")}}))))

(defn list-users-handler
  "GET /api/users - List users with pagination and filters."
  [user-service]
  (fn [{{:keys [query]} :parameters}]
    (let [tenant-id (type-conversion/string->uuid (:tenantId query))
          options {:limit (or (:limit query) 20)
                   :offset (or (:offset query) 0)
                   :filter-role (when (:role query) (keyword (:role query)))
                   :filter-active (:active query)}
          result (ports/find-users-by-tenant user-service tenant-id options)
          users (map schema/user-specific-kebab->camel (:users result))]
      {:status 200
       :body {:users users
              :totalCount (:total-count result)
              :limit (:limit options)
              :offset (:offset options)}})))

(defn update-user-handler
  "PUT /api/users/:id - Update user."
  [user-service]
  (fn [{{:keys [path body]} :parameters}]
    (let [user-id (type-conversion/string->uuid (:id path))
          current-user (ports/find-user-by-id user-service user-id)]
      (if-not current-user
        {:status 404
         :body {:type "https://boundary.example.com/problems/not-found"
                :title "User Not Found"
                :status 404
                :detail (str "User with ID " (:id path) " not found")}}
        (let [updated-user (merge current-user
                                  (when (:name body) {:name (:name body)})
                                  (when (:role body) {:role (keyword (:role body))})
                                  (when (some? (:active body)) {:active (:active body)}))
              result (ports/update-user user-service updated-user)]
          {:status 200
           :body (schema/user-specific-kebab->camel result)})))))

(defn delete-user-handler
  "DELETE /api/users/:id - Soft delete user."
  [user-service]
  (fn [{{:keys [path]} :parameters}]
    (let [user-id (type-conversion/string->uuid (:id path))]
      (ports/soft-delete-user user-service user-id)
      {:status 204})))

;; =============================================================================
;; Session Handlers
;; =============================================================================

(defn create-session-handler
  "POST /api/sessions - Create session."
  [user-service]
  (fn [{{:keys [body]} :parameters}]
    (log/info "Creating session" {:user-id (:userId body)})
    (let [session-data {:user-id (type-conversion/string->uuid (:userId body))
                        :tenant-id (type-conversion/string->uuid (:tenantId body))
                        :user-agent (get-in body [:deviceInfo :userAgent])
                        :ip-address (get-in body [:deviceInfo :ipAddress])}
          session (ports/create-session user-service session-data)]
      {:status 201
       :body (schema/user-specific-kebab->camel session)})))

(defn validate-session-handler
  "GET /api/sessions/:token - Validate session."
  [user-service]
  (fn [{{:keys [path]} :parameters}]
    (let [session-token (:token path)
          session (ports/find-session-by-token user-service session-token)]
      (if session
        {:status 200
         :body {:valid true
                :userId (type-conversion/uuid->string (:user-id session))
                :tenantId (type-conversion/uuid->string (:tenant-id session))
                :expiresAt (type-conversion/instant->string (:expires-at session))}}
        {:status 404
         :body {:valid false
                :type "https://boundary.example.com/problems/not-found"
                :title "Session Not Found"
                :status 404
                :detail "Session not found or expired"}}))))

(defn invalidate-session-handler
  "DELETE /api/sessions/:token - Invalidate session."
  [user-service]
  (fn [{{:keys [path]} :parameters}]
    (let [session-token (:token path)]
      (ports/invalidate-session user-service session-token)
      {:status 204})))

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
  [user-service]
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

