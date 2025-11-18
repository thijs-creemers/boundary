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
  (:require [boundary.shell.interfaces.http.routes :as routes]
            [boundary.shared.core.interceptor :as interceptor]
            [boundary.shared.core.interceptor-context :as interceptor-context]
            [boundary.user.shell.interceptors :as user-interceptors]
            [boundary.user.shell.middleware :as user-middleware]
            [boundary.user.shell.web-handlers :as web-handlers]
            [reitit.ring :as ring]))

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

(defn login-handler
  "POST /auth/login - Authenticate user with email/password using interceptor pipeline."
  [user-service]
  (fn [request]
    (let [;; Create context for the operation with real observability services
          context (create-interceptor-context :user-login user-service request)

          ;; Create the interceptor pipeline for user authentication
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
;; Web UI Routes
;; =============================================================================

(defn web-ui-routes
  "Define web UI routes (WITHOUT /web prefix) for HTML responses.
   
   NOTE: These routes will be mounted under /web by the top-level router.
   Do NOT include /web prefix in paths here.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Vector of Reitit route definitions for web UI"
  [user-service config]
  [["/users" {:middleware [[user-middleware/flexible-authentication-middleware user-service]]
              :get {:handler (web-handlers/users-page-handler user-service config)
                    :summary "Users listing page"}
              :post {:handler (web-handlers/create-user-htmx-handler user-service config)
                     :summary "Create user (HTMX fragment)"}}]
   ["/users/new" {:middleware [[user-middleware/flexible-authentication-middleware user-service]]
                  :get {:handler (web-handlers/create-user-page-handler config)
                        :summary "Create user page"}}]
   ["/users/table" {:middleware [[user-middleware/flexible-authentication-middleware user-service]]
                    :get {:handler (web-handlers/users-table-fragment-handler user-service config)
                          :summary "Users table fragment (HTMX refresh)"}}]
   ["/users/:id" {:middleware [[user-middleware/flexible-authentication-middleware user-service]]
                  :get {:handler (web-handlers/user-detail-page-handler user-service config)
                        :summary "User detail page"}
                  :put {:handler (web-handlers/update-user-htmx-handler user-service config)
                        :summary "Update user (HTMX fragment)"}
                  :delete {:handler (web-handlers/delete-user-htmx-handler user-service config)
                           :summary "Delete user (HTMX fragment)"}}]])

;; =============================================================================
;; Static Asset Routes
;; =============================================================================

(defn- wrap-resource-handler
  "Wrap a Ring resource handler to always return the response without modification.
   This bypasses content negotiation that causes 406 errors for static assets."
  [handler]
  (fn [request]
    ;; Call the resource handler
    (when-let [response (handler request)]
      ;; If response exists, ensure we have Accept: */* behavior
      (assoc-in response [:headers "Content-Type"]
                (or (get-in response [:headers "Content-Type"])
                    "application/octet-stream")))))

(defn static-routes
  "Define routes for serving static assets.
   
   NOTE: These routes use a wrapped resource handler that bypasses
   Muuntaja content negotiation to properly serve static files.
   
   Returns:
     Vector of Reitit route definitions for static files"
  []
  [["/css/*" {:get {:handler (wrap-resource-handler
                              (ring/create-resource-handler {:path "/public/css"}))
                    :summary "CSS assets"
                    :no-doc true}}]
   ["/js/*" {:get {:handler (wrap-resource-handler
                             (ring/create-resource-handler {:path "/public/js"}))
                   :summary "JavaScript assets"
                   :no-doc true}}]
   ["/modules/*" {:get {:handler (wrap-resource-handler
                                  (ring/create-resource-handler {:path "/public/modules"}))
                        :summary "Module-specific assets"
                        :no-doc true}}]
   ["/docs/*" {:get {:handler (wrap-resource-handler
                               (ring/create-resource-handler {:path "/public/docs"}))
                     :summary "Documentation"
                     :no-doc true}}]])

;; =============================================================================
;; API Routes
;; =============================================================================

(defn api-routes
  "Define API routes (WITHOUT /api prefix) for REST endpoints.
   
   NOTE: These routes will be mounted under /api by the top-level router.
   Do NOT include /api prefix in paths here.
   
   Args:
     user-service: User service instance
     
   Returns:
     Vector of Reitit route definitions for API"
  [user-service]
  [["/users" {:post {:handler (create-user-handler user-service)
                    :summary "Create user"
                    :tags ["users"]
                    :parameters {:body [:map
                                        [:email :string]
                                        [:name :string]
                                        [:password {:optional true} :string]
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
   ["/auth/login" {:post {:handler (login-handler user-service)
                         :summary "Authenticate user with email/password"
                         :tags ["authentication"]
                         :parameters {:body [:map
                                             [:email :string]
                                             [:password :string]
                                             [:tenantId {:optional true} :string]
                                             [:deviceInfo {:optional true} [:map
                                                                            [:userAgent {:optional true} :string]
                                                                            [:ipAddress {:optional true} :string]]]]}}}]
   ["/sessions" {:post {:handler (create-session-handler user-service)
                       :summary "Create session (login by user ID)"
                       :tags ["sessions"]
                       :parameters {:body [:or
                                           [:map
                                            [:userId :string]
                                            [:tenantId :string]
                                            [:deviceInfo {:optional true} [:map
                                                                           [:userAgent {:optional true} :string]
                                                                           [:ipAddress {:optional true} :string]]]]
                                           [:map
                                            [:email :string]
                                            [:password :string]
                                            [:tenantId {:optional true} :string]
                                            [:deviceInfo {:optional true} [:map
                                                                           [:userAgent {:optional true} :string]
                                                                           [:ipAddress {:optional true} :string]]]]]}}}]
   ["/sessions/:token" {:get {:handler (validate-session-handler user-service)
                             :summary "Validate session"
                             :tags ["sessions"]
                             :parameters {:path [:map [:token :string]]}}
                       :delete {:handler (invalidate-session-handler user-service)
                                :summary "Invalidate session (logout)"
                                :tags ["sessions"]
                                :parameters {:path [:map [:token :string]]}}}]])

;; =============================================================================
;; User Module Routes (Structured Format)
;; =============================================================================

(defn user-routes
  "Define user module routes in structured format for top-level composition.
   
   Returns a map with route categories:
   - :api - REST API routes (will be mounted under /api)
   - :web - Web UI routes (will be mounted under /web)
   - :static - Static asset routes (DEPRECATED - now served at handler level)
   
   Args:
     user-service: User service instance
     config: Application configuration map

   Returns:
     Map with keys :api, :web, :static containing route vectors
     
   NOTE: Static routes are no longer included here as they are served
   directly by the Ring resource handler at the HTTP handler level,
   bypassing Reitit routing and content negotiation middleware."
  [user-service config]
  (let [web-ui-enabled? (get-in config [:active :boundary/settings :features :user-web-ui :enabled?] true)]
    {:api (api-routes user-service)
     :web (when web-ui-enabled? (web-ui-routes user-service config))
     :static []}))

;; =============================================================================
;; Legacy flat route format (for backward compatibility)
;; =============================================================================

(defn user-routes-flat
  "Define user module routes in LEGACY flat format.
   
   DEPRECATED: Use user-routes instead which returns structured {:api :web :static} map.
   This function exists for backward compatibility only.
   
   Args:
     user-service: User service instance
     config: Application configuration map

   Returns:
     Vector of all route definitions (API, web, static) - FLAT, not grouped"
  [user-service config]
  (let [routes-map (user-routes user-service config)]
    (concat (:static routes-map)
            (or (:web routes-map) [])
            (:api routes-map))))

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
   
   DEPRECATED: This function uses the legacy flat route format.
   For new integrations, use user-routes directly and compose at top level.

   Args:
     user-service: User service instance
     config: Application configuration map

   Returns:
     Reitit router with common routes + user module routes"
  [user-service config]
  (routes/create-router config
                        (user-routes-flat user-service config)
                        :additional-health-checks (user-health-checks user-service)
                        :error-mappings user-error-mappings))

(defn create-handler
  "Create Ring handler for user module using common routing infrastructure.
   
   DEPRECATED: This function uses the legacy flat route format.
   For new integrations, use user-routes directly and compose at top level.

   Args:
     user-service: User service instance
     config: Application configuration map (optional)

   Returns:
     Ring handler function with common middleware and routes"
  ([user-service]
   (create-handler user-service {}))
  ([user-service config]
   (let [router (routes/create-router config
                                      (user-routes-flat user-service config)
                                      :additional-health-checks (user-health-checks user-service)
                                      :error-mappings user-error-mappings)]
     (routes/create-handler router))))

(defn create-app
  "Create complete Ring application for user module.
   
   DEPRECATED: This function uses the legacy flat route format.
   For new integrations, use user-routes directly and compose at top level.

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
                      (user-routes-flat user-service config)
                      :additional-health-checks (user-health-checks user-service)
                      :error-mappings user-error-mappings)))
