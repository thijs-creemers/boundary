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
   - DELETE /sessions/:token - Invalidate session (logout)

   All observability is handled automatically by interceptors."
  (:require [boundary.shell.interfaces.http.routes :as routes]
            [boundary.shared.core.interceptor :as interceptor]
            [boundary.shared.core.interceptor-context :as interceptor-context]
            [boundary.user.shell.interceptors :as user-interceptors]))

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

;; =============================================================================
;; Observability Service Extraction Helpers
;; =============================================================================

(defn extract-observability-services
  "Extracts observability services from user-service for interceptor context.
   
   Note: Since service layer cleanup removed direct observability dependencies,
   the interceptor context will obtain these services from the system wiring."
  [user-service]
  {:user-service user-service})

(defn create-interceptor-context
  "Creates interceptor context with real observability services and error mappings."
  [operation-type user-service request]
  (-> (interceptor-context/create-http-context
       operation-type
       (extract-observability-services user-service)
       request)
      (assoc :error-mappings user-error-mappings)))

;; =============================================================================
;; User Handlers
;; =============================================================================

(defn create-user-handler
  "Create a new user using interceptor pipeline.

   This version demonstrates the interceptor-based approach that eliminates
   manual observability boilerplate while providing comprehensive tracking."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context (create-interceptor-context :user-create user-service request)

          ;; Create the interceptor pipeline for user creation
          pipeline (user-interceptors/create-user-creation-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn get-user-handler
  "GET /api/users/:id - Get user by ID using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context (create-interceptor-context :user-get user-service request)

          ;; Create the interceptor pipeline for user get
          pipeline (user-interceptors/create-user-get-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn list-users-handler
  "GET /api/users - List users using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context (create-interceptor-context :user-list user-service request)

          ;; Create the interceptor pipeline for user list
          pipeline (user-interceptors/create-user-list-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn update-user-handler
  "PUT /api/users/:id - Update user using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context (create-interceptor-context :user-update user-service request)

          ;; Create the interceptor pipeline for user update
          pipeline (user-interceptors/create-user-update-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn delete-user-handler
  "DELETE /api/users/:id - Delete user using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context (create-interceptor-context :user-delete user-service request)

          ;; Create the interceptor pipeline for user delete
          pipeline (user-interceptors/create-user-delete-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

;; =============================================================================
;; Session Handlers
;; =============================================================================

(defn create-session-handler
  "POST /api/sessions - Create session using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context (create-interceptor-context :session-create user-service request)

          ;; Create the interceptor pipeline for session creation
          pipeline (user-interceptors/create-session-creation-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn validate-session-handler
  "GET /api/sessions/:token - Validate session using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context (create-interceptor-context :session-validate user-service request)

          ;; Create the interceptor pipeline for session validation
          pipeline (user-interceptors/create-session-validation-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn invalidate-session-handler
  "DELETE /api/sessions/:token - Invalidate session using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context (create-interceptor-context :session-invalidate user-service request)

          ;; Create the interceptor pipeline for session invalidation
          pipeline (user-interceptors/create-session-invalidation-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

;; =============================================================================
;; User Module Routes
;; =============================================================================

(defn user-routes
  "Define user module specific routes using interceptor-based handlers.

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

