(ns boundary.user.shell.http
  "HTTP routing and handlers for user module - provides structured route definitions.

   This namespace implements the REST API and Web UI for user and session management.
   
   Route Structure:
   ----------------
   The module exports routes in a structured format {:api [...] :web [...] :static [...]}
   for composition by the top-level router, which applies appropriate prefixes:
   
   API Endpoints (mounted under /api):
   - POST   /api/users           - Create user
   - GET    /api/users/:id       - Get user by ID
   - GET    /api/users           - List users (with filters)
   - PUT    /api/users/:id       - Update user
   - DELETE /api/users/:id       - Soft delete user
   - POST   /api/sessions        - Create session (login)
   - GET    /api/sessions/:token - Validate session
   - DELETE /api/sessions/:token - Invalidate session (logout)
   - POST   /api/auth/login      - Authenticate with email/password

   Web UI Endpoints (mounted under /web):
   - GET    /web/users           - Users listing page
   - GET    /web/users/new       - Create user page
   - GET    /web/users/:id       - User detail page
   - POST   /web/users           - Create user (HTMX fragment)
   - PUT    /web/users/:id       - Update user (HTMX fragment)
   - DELETE /web/users/:id       - Delete user (HTMX fragment)
   
   Static Assets (mounted at root):
   - GET    /css/*                - CSS assets
   - GET    /js/*                 - JavaScript assets
   - GET    /modules/*            - Module-specific assets
   - GET    /docs/*               - Documentation

   All observability is handled automatically by interceptors."
  (:require [boundary.shared.core.interceptor :as interceptor]
            [boundary.shared.core.interceptor-context :as interceptor-context]
            [boundary.user.shell.http-interceptors]
            [boundary.user.shell.interceptors :as user-interceptors]
            [boundary.user.shell.middleware :as user-middleware]
            [boundary.user.shell.web-handlers :as web-handlers]))

;; =============================================================================
;; User-Specific Error Mappings
;; =============================================================================

(def user-error-mappings
  "User module specific error type mappings for RFC 7807 problem details."
  {:user-exists               [409 "User Already Exists"]
   :user-not-found            [404 "User Not Found"]
   :session-not-found         [404 "Session Not Found"]
   :deletion-not-allowed      [403 "Deletion Not Allowed"]
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
;; User Module Routes (Normalized Format)
;; =============================================================================

(defn create-user-handler
  "Create a new user using interceptor pipeline.

   This version demonstrates the interceptor-based approach that eliminates
   manual observability boilerplate while providing comprehensive tracking."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-create user-service request)

          ;; Create the interceptor pipeline for user creation
          pipeline       (user-interceptors/create-user-creation-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn get-user-handler
  "GET /api/users/:id - Get user by ID using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-get user-service request)

          ;; Create the interceptor pipeline for user get
          pipeline       (user-interceptors/create-user-get-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn list-users-handler
  "GET /api/users - List users using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-list user-service request)

          ;; Create the interceptor pipeline for user list
          pipeline       (user-interceptors/create-user-list-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn update-user-handler
  "PUT /api/users/:id - Update user using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-update user-service request)

          ;; Create the interceptor pipeline for user update
          pipeline       (user-interceptors/create-user-update-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn delete-user-handler
  "DELETE /api/users/:id - Delete user using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-delete user-service request)

          ;; Create the interceptor pipeline for user delete
          pipeline       (user-interceptors/create-user-delete-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

;; =============================================================================
;; User Module Routes (Normalized Format)
;; =============================================================================

(defn create-session-handler
  "POST /api/sessions - Create session using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :session-create user-service request)

          ;; Create the interceptor pipeline for session creation
          pipeline       (user-interceptors/create-session-creation-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn login-handler
  "POST /auth/login - Authenticate user with email/password using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :user-login user-service request)

          ;; Create the interceptor pipeline for user authentication
          pipeline       (user-interceptors/create-session-creation-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn validate-session-handler
  "GET /api/sessions/:token - Validate session using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :session-validate user-service request)

          ;; Create the interceptor pipeline for session validation
          pipeline       (user-interceptors/create-session-validation-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

(defn invalidate-session-handler
  "DELETE /api/sessions/:token - Invalidate session using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context        (create-interceptor-context :session-invalidate user-service request)

          ;; Create the interceptor pipeline for session invalidation
          pipeline       (user-interceptors/create-session-invalidation-pipeline :http)

          ;; Execute the pipeline
          result-context (interceptor/run-pipeline context pipeline)]

      ;; Return the response from context
      (:response result-context))))

;; =============================================================================
;; User Module Routes (Normalized Format)
;; =============================================================================

(defn normalized-api-routes
  "Define API routes in normalized format.
   
   Args:
     user-service: User service instance
     
   Returns:
     Vector of normalized route maps"
  [user-service]
  (let [auth-middleware (user-middleware/flexible-authentication-middleware user-service)]
    [{:path    "/users"
      :meta    {:middleware [auth-middleware]}
      :methods {:post {:handler      (create-user-handler user-service)
                       :interceptors ['boundary.user.shell.http-interceptors/require-authenticated
                                      'boundary.user.shell.http-interceptors/require-admin
                                      'boundary.user.shell.http-interceptors/log-action]
                       :summary      "Create user"
                       :tags         ["users"]
                       :parameters   {:body [:map
                                             [:email :string]
                                             [:name :string]
                                             [:password {:optional true} :string]
                                             [:role [:enum "admin" "user" "viewer"]]
                                             [:active {:optional true} :boolean]]}}
                :get  {:handler    (list-users-handler user-service)
                       :summary    "List users with pagination and filters"
                       :tags       ["users"]
                       :parameters {:query [:map
                                            [:limit {:optional true} :int]
                                            [:offset {:optional true} :int]
                                            [:role {:optional true} [:enum "admin" "user" "viewer"]]
                                            [:active {:optional true} :boolean]]}}}}
     {:path    "/users/:id"
      :meta    {:middleware [auth-middleware]}
      :methods {:get    {:handler    (get-user-handler user-service)
                         :summary    "Get user by ID"
                         :tags       ["users"]
                         :parameters {:path [:map [:id :string]]}}
                :put    {:handler    (update-user-handler user-service)
                         :summary    "Update user"
                         :tags       ["users"]
                         :parameters {:path [:map [:id :string]]
                                      :body [:map
                                             [:name {:optional true} :string]
                                             [:role {:optional true} [:enum "admin" "user" "viewer"]]
                                             [:active {:optional true} :boolean]]}}
                :delete {:handler    (delete-user-handler user-service)
                         :summary    "Soft delete user"
                         :tags       ["users"]
                         :parameters {:path [:map [:id :string]]}}}}
     {:path    "/auth/login"
      :methods {:post {:handler    (login-handler user-service)
                       :summary    "Authenticate user with email/password"
                       :tags       ["authentication"]
                       :parameters {:body [:map
                                           [:email :string]
                                           [:password :string]
                                           [:deviceInfo {:optional true} [:map
                                                                          [:userAgent {:optional true} :string]
                                                                          [:ipAddress {:optional true} :string]]]]}}}}
     {:path    "/sessions"
      :methods {:post {:handler    (create-session-handler user-service)
                       :summary    "Create session (login by user ID)"
                       :tags       ["sessions"]
                       :parameters {:body [:or
                                           [:map
                                            [:userId :string]
                                            [:deviceInfo {:optional true} [:map
                                                                           [:userAgent {:optional true} :string]
                                                                           [:ipAddress {:optional true} :string]]]]
                                           [:map
                                            [:email :string]
                                            [:password :string]
                                            [:deviceInfo {:optional true} [:map
                                                                           [:userAgent {:optional true} :string]
                                                                           [:ipAddress {:optional true} :string]]]]]}}}}
     {:path    "/sessions/:token"
      :methods {:get    {:handler    (validate-session-handler user-service)
                         :summary    "Validate session"
                         :tags       ["sessions"]
                         :parameters {:path [:map [:token :string]]}}
                :delete {:handler    (invalidate-session-handler user-service)
                         :summary    "Invalidate session (logout)"
                         :tags       ["sessions"]
                         :parameters {:path [:map [:token :string]]}}}}]))

(defn normalized-web-routes
  "Define web UI routes in normalized format (WITHOUT /web prefix).
   
   NOTE: These routes will be mounted under /web by the top-level router.
   Do NOT include /web prefix in paths here.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Vector of normalized route maps"
  [user-service config]
  (let [auth-middleware (user-middleware/flexible-authentication-middleware user-service)]
    [{:path    "/register"
      :meta    {:no-doc true}
      :methods {:get  {:handler (web-handlers/register-page-handler config)
                       :summary "Self-service registration page"}
                :post {:handler (web-handlers/register-submit-handler user-service config)
                       :summary "Submit registration form"}}}
     {:path    "/login"
      :meta    {:no-doc true}
      :methods {:get  {:handler (web-handlers/login-page-handler config)
                       :summary "Login page"}
                :post {:handler (web-handlers/login-submit-handler user-service config)
                       :summary "Submit login form"}}}
     {:path    "/logout"
      :meta    {:no-doc true}
      :methods {:post {:middleware [auth-middleware]
                       :handler    (web-handlers/logout-handler user-service config)
                       :summary    "Logout current user"}}}
     {:path    "/users"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get  {:handler (web-handlers/users-page-handler user-service config)
                       :summary "Users listing page"}
                :post {:handler (web-handlers/create-user-htmx-handler user-service config)
                       :summary "Create user (HTMX fragment)"}}}
     {:path    "/users/bulk"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:post {:handler (web-handlers/bulk-update-users-htmx-handler user-service config)
                       :summary "Bulk user operations (HTMX fragment)"}}}
     {:path    "/users/new"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/create-user-page-handler config)
                      :summary "Create user page"}}}
     {:path    "/users/table"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/users-table-fragment-handler user-service config)
                      :summary "Users table fragment (HTMX refresh)"}}}
     {:path    "/users/:id/hard-delete"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:post {:handler (web-handlers/hard-delete-user-handler user-service config)
                       :summary "Permanently delete user (admin only)"}}}
     {:path    "/users/:id/sessions"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/user-sessions-page-handler user-service config)
                      :summary "User sessions management page"}}}
     {:path    "/users/:id/sessions/revoke-all"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:post {:handler (web-handlers/revoke-all-sessions-handler user-service config)
                       :summary "Revoke all user sessions"}}}
     {:path    "/users/:id"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get    {:handler (web-handlers/user-detail-page-handler user-service config)
                         :summary "User detail page"}
                :put    {:handler (web-handlers/update-user-htmx-handler user-service config)
                         :summary "Update user (HTMX fragment)"}
                :delete {:handler (web-handlers/delete-user-htmx-handler user-service config)
                         :summary "Delete user (HTMX fragment)"}}}
     {:path    "/sessions/:token/revoke"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:post {:handler (web-handlers/revoke-session-handler user-service config)
                       :summary "Revoke specific session"}}}
     {:path    "/audit"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/audit-page-handler user-service config)
                      :summary "Audit trail page"}}}
     {:path    "/audit/table"
      :meta    {:no-doc     true
                :middleware [auth-middleware]}
      :methods {:get {:handler (web-handlers/audit-table-fragment-handler user-service config)
                      :summary "Audit table fragment (HTMX refresh)"}}}]))

(defn user-routes-normalized
  "Define user module routes in normalized format for top-level composition.
   
   Returns a map with route categories:
   - :api - REST API routes (will be mounted under /api)
   - :web - Web UI routes (will be mounted under /web)
   - :static - Static asset routes (empty - served at handler level)
   
   Args:
     user-service: User service instance
     config: Application configuration map

   Returns:
     Map with keys :api, :web, :static containing normalized route vectors"
  [user-service config]
  (let [web-ui-enabled? (get-in config [:active :boundary/settings :features :user-web-ui :enabled?] true)]
    {:api    (normalized-api-routes user-service)
     :web    (when web-ui-enabled? (normalized-web-routes user-service config))
     :static []}))


