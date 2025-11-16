(ns boundary.user.shell.middleware
  "Authentication and authorization middleware for HTTP endpoints.
   
   This middleware provides:
   - JWT token validation
   - Session-based authentication
   - Role-based authorization
   - Request context enrichment with user information"
  (:require [boundary.user.shell.auth :as auth-shell]
            [boundary.user.ports :as ports]
            [clojure.tools.logging :as log]
            [clojure.string :as str]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn extract-bearer-token
  "Extracts Bearer token from Authorization header.
   
   Args:
     request: HTTP request map
     
   Returns:
     String token or nil if not found"
  [request]
  (when-let [auth-header (get-in request [:headers "authorization"])]
    (when (str/starts-with? auth-header "Bearer ")
      (str/trim (subs auth-header 7)))))

(defn extract-session-token
  "Extracts session token from request headers or cookies.
   
   Looks for token in:
   1. X-Session-Token header
   2. session-token cookie
   
   Args:
     request: HTTP request map
     
   Returns:
     String token or nil if not found"
  [request]
  (or
   ;; Check X-Session-Token header
   (get-in request [:headers "x-session-token"])
   ;; Check session-token cookie
   (get-in request [:cookies "session-token" :value])))

(defn create-unauthorized-response
  "Creates standardized 401 Unauthorized response.
   
   Args:
     message: Error message string
     reason: Keyword reason code
     
   Returns:
     Ring response map"
  [message reason]
  {:status 401
   :headers {"Content-Type" "application/json"}
   :body {:type "authentication-required"
          :title "Authentication Required"
          :status 401
          :detail message
          :reason reason}})

(defn create-forbidden-response
  "Creates standardized 403 Forbidden response.
   
   Args:
     message: Error message string
     reason: Keyword reason code
     
   Returns:
     Ring response map"
  [message reason]
  {:status 403
   :headers {"Content-Type" "application/json"}
   :body {:type "access-forbidden"
          :title "Access Forbidden"
          :status 403
          :detail message
          :reason reason}})

;; =============================================================================
;; JWT Authentication Middleware
;; =============================================================================

(defn jwt-authentication-middleware
  "Middleware that validates JWT tokens from Authorization header.
   
   Extracts Bearer token, validates it, and adds user information to request.
   On validation failure, returns 401 response.
   
   Args:
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  [handler]
  (fn [request]
    (if-let [token (extract-bearer-token request)]
      (try
        (let [jwt-claims (auth-shell/validate-jwt-token token)]
          (if jwt-claims
            ;; Token valid - add user info to request
            (let [enriched-request (assoc request
                                          :user {:id (:user-id jwt-claims)
                                                 :email (:email jwt-claims)
                                                 :role (:role jwt-claims)
                                                 :tenant-id (:tenant-id jwt-claims)}
                                          :auth-type :jwt)]
              (handler enriched-request))
            ;; Token invalid
            (create-unauthorized-response "Invalid JWT token" :invalid-jwt)))
        (catch Exception ex
          (log/warn ex "JWT validation failed")
          (create-unauthorized-response "JWT validation failed" :jwt-validation-error)))

      ;; No token provided
      (create-unauthorized-response "JWT token required" :missing-jwt))))

;; =============================================================================
;; Session Authentication Middleware
;; =============================================================================

(defn session-authentication-middleware
  "Middleware that validates session tokens and updates session access time.
   
   Extracts session token, validates it through user service, and adds user
   information to request. On validation failure, returns 401 response.
   
   Args:
     user-service: User service instance for session validation
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  [user-service handler]
  (fn [request]
    (if-let [session-token (extract-session-token request)]
      (try
        (if-let [session (ports/validate-session user-service session-token)]
          ;; Session valid - add user info to request
          (let [enriched-request (assoc request
                                        :user {:id (:user-id session)
                                               :tenant-id (:tenant-id session)}
                                        :session session
                                        :auth-type :session)]
            (handler enriched-request))
          ;; Session invalid or expired
          (create-unauthorized-response "Invalid or expired session" :invalid-session))
        (catch Exception ex
          (log/warn ex "Session validation failed" {:session-token (str (take 8 session-token) "...")})
          (create-unauthorized-response "Session validation failed" :session-validation-error)))

      ;; No session token provided
      (create-unauthorized-response "Session token required" :missing-session))))

;; =============================================================================
;; Flexible Authentication Middleware
;; =============================================================================

(defn flexible-authentication-middleware
  "Middleware that accepts either JWT or session token authentication.
   
   Tries JWT first (from Authorization header), then falls back to session token.
   Adds user information to request on successful authentication.
   
   Args:
     user-service: User service instance for session validation
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  [user-service handler]
  (fn [request]
    (cond
      ;; Try JWT authentication first
      (extract-bearer-token request)
      ((jwt-authentication-middleware handler) request)

      ;; Fall back to session authentication
      (extract-session-token request)
      ((session-authentication-middleware user-service handler) request)

      ;; No authentication provided
      :else
      (create-unauthorized-response "Authentication required" :no-credentials))))

;; =============================================================================
;; Authorization Middleware
;; =============================================================================

(defn require-role-middleware
  "Middleware that requires user to have specific role(s).
   
   Must be used after authentication middleware that sets :user in request.
   
   Args:
     required-roles: Set of required roles (keywords)
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  [required-roles handler]
  (fn [request]
    (if-let [user (:user request)]
      (let [user-role (:role user)]
        (if (contains? required-roles user-role)
          ;; User has required role
          (handler request)
          ;; User lacks required role
          (create-forbidden-response
           (format "Role '%s' required. User has role '%s'"
                   (str/join ", " (map name required-roles))
                   (name user-role))
           :insufficient-role)))

      ;; No user in request (authentication middleware not run?)
      (create-unauthorized-response "Authentication required" :missing-user))))

(defn require-admin-middleware
  "Middleware that requires admin role.
   
   Args:
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  [handler]
  (require-role-middleware #{:admin} handler))

(defn require-tenant-access-middleware
  "Middleware that ensures user can only access resources in their tenant.
   
   Compares user's tenant-id with :tenant-id path/query parameter.
   Must be used after authentication middleware.
   
   Args:
     handler: Next handler in the chain
     
   Returns:
     Wrapped handler function"
  [handler]
  (fn [request]
    (if-let [user (:user request)]
      (let [user-tenant-id (:tenant-id user)
            requested-tenant-id (or
                                 (get-in request [:parameters :path :tenantId])
                                 (get-in request [:parameters :query :tenantId]))]
        (if (or (nil? requested-tenant-id)
                (= user-tenant-id requested-tenant-id))
          ;; Tenant access allowed
          (handler request)
          ;; Tenant access denied
          (create-forbidden-response
           "Access denied to resources outside your tenant"
           :cross-tenant-access-denied)))

      ;; No user in request
      (create-unauthorized-response "Authentication required" :missing-user))))

;; =============================================================================
;; Utility Functions for Route Protection
;; =============================================================================

(defn protect-with-jwt
  "Wraps handler with JWT authentication.
   
   Args:
     handler: Handler to protect
     
   Returns:
     Protected handler"
  [handler]
  (jwt-authentication-middleware handler))

(defn protect-with-session
  "Wraps handler with session authentication.
   
   Args:
     user-service: User service for session validation
     handler: Handler to protect
     
   Returns:
     Protected handler"
  [user-service handler]
  (session-authentication-middleware user-service handler))

(defn protect-with-flexible-auth
  "Wraps handler with flexible authentication (JWT or session).
   
   Args:
     user-service: User service for session validation
     handler: Handler to protect
     
   Returns:
     Protected handler"
  [user-service handler]
  (flexible-authentication-middleware user-service handler))

(defn protect-admin-only
  "Wraps handler with admin-only access control.
   
   Args:
     user-service: User service for session validation
     handler: Handler to protect
     
   Returns:
     Protected handler with authentication and admin authorization"
  [user-service handler]
  (-> handler
      require-admin-middleware
      (flexible-authentication-middleware user-service)))

(defn protect-tenant-scoped
  "Wraps handler with tenant-scoped access control.
   
   Args:
     user-service: User service for session validation
     handler: Handler to protect
     
   Returns:
     Protected handler with authentication and tenant authorization"
  [user-service handler]
  (-> handler
      require-tenant-access-middleware
      (flexible-authentication-middleware user-service)))