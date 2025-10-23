(ns boundary.user.http
  "HTTP routing and handlers for user module using Reitit.
   
   This namespace implements the REST API for user and session management,
   providing a clean HTTP interface to the user service layer.
   
   Endpoints:
   - POST   /api/users           - Create user
   - GET    /api/users/:id       - Get user by ID  
   - GET    /api/users           - List users (with filters)
   - PUT    /api/users/:id       - Update user
   - DELETE /api/users/:id       - Soft delete user
   - POST   /api/sessions        - Create session (login)
   - GET    /api/sessions/:token - Validate session
   - DELETE /api/sessions/:token - Invalidate session (logout)
   - GET    /health              - Health check"
  (:require [clojure.tools.logging :as log]
            [muuntaja.core :as m]
            [reitit.coercion.malli :as malli]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [boundary.shared.utils.type-conversion :as type-conversion]
            [boundary.user.schema :as schema])
  (:import (java.util UUID)))

;; =============================================================================
;; Middleware - Correlation ID
;; =============================================================================

(defn wrap-correlation-id
      "Middleware to add correlation ID to requests and responses."
  [handler]
  (fn [request]
    (let [correlation-id  (or (get-in request [:headers "x-request-id"])
                              (str (UUID/randomUUID)))
          request-with-id (assoc request :correlation-id correlation-id)
          response        (handler request-with-id)]
      (if response
        (assoc-in response [:headers "X-Request-Id"] correlation-id)
        response))))

;; =============================================================================
;; Middleware - Request Logging
;; =============================================================================

(defn wrap-request-logging
      "Middleware for structured request/response logging."
  [handler]
  (fn [request]
    (let [start-time (System/currentTimeMillis)
          {:keys [request-method uri correlation-id]} request]
      (log/info "HTTP request started"
                {:method         request-method
                 :uri            uri
                 :correlation-id correlation-id})
      (let [response (handler request)
            duration (- (System/currentTimeMillis) start-time)]
        (log/info "HTTP request completed"
                  {:method         request-method
                   :uri            uri
                   :status         (:status response)
                   :duration-ms    duration
                   :correlation-id correlation-id})
        response))))

;; =============================================================================
;; Middleware - Error Handling (RFC 7807 Problem Details)
;; =============================================================================

(defn exception->problem
      "Convert exception to RFC 7807 problem details."
  [ex correlation-id uri]
  (let [ex-data (ex-data ex)
        ex-type (:type ex-data)
        [status title] (case ex-type
                         :validation-error [400 "Validation Error"]
                         :user-exists [409 "User Already Exists"]
                         :user-not-found [404 "User Not Found"]
                         :auth-failed [401 "Authentication Failed"]
                         :forbidden [403 "Forbidden"]
                         :business-rule-violation [400 "Business Rule Violation"]
                         :deletion-not-allowed [403 "Deletion Not Allowed"]
                         :hard-deletion-not-allowed [403 "Hard Deletion Not Allowed"]
                         [500 "Internal Server Error"])]
    {:status  status
     :headers {"Content-Type" "application/problem+json"}
     :body    {:type          (str "https://boundary.example.com/problems/" (name (or ex-type :internal-error)))
               :title         title
               :status        status
               :detail        (.getMessage ex)
               :instance      uri
               :correlationId correlation-id
               :errors        (or (:errors ex-data) {})}}))

(defn wrap-exception-handling
      "Middleware to catch exceptions and return RFC 7807 problem details."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception ex
        (log/error "Request failed with exception"
                   {:uri            (:uri request)
                    :method         (:request-method request)
                    :correlation-id (:correlation-id request)
                    :error          (.getMessage ex)
                    :ex-data        (ex-data ex)})
        (exception->problem ex (:correlation-id request) (:uri request))))))

;; =============================================================================
;; User Handlers
;; =============================================================================

#_(defn create-user-handler
    "POST /api/users - Create a new user."
    [user-service]
    (fn [{{:keys [body]} :parameters}]
      (log/info "Creating user" {:email (:email body)})
      (let [user-data (-> body
                          (assoc :tenant-id (type-conversion/string->uuid (:tenantId body)))
                          (dissoc :tenantId))
            created-user (.create-user user-service user-data)
            response-data (schema/user-specific-kebab->camel created-user)]
        {:status 201
         :body response-data})))

(defn create-user-handler
      "POST /api/users - Create a new user."
  [user-service]
  (fn [{{:keys [body]} :parameters}]
    (try
      (log/info "Creating user" {:email (:email body)})
      (let [user-data {:email (:email body)
                       :name (:name body)
                       :password (:password body)
                       :role (keyword (:role body))
                       :tenant-id (type-conversion/string->uuid (:tenantId body))
                       :active (get body :active true)}
            created-user (.create-user user-service user-data)]
        {:status 201
         :body {:message "user created"
                :id (str (:id created-user))
                :email (:email created-user)}})
      (catch Exception e
        (log/error "Error creating user" {:error (.getMessage e) :data (ex-data e)})
        {:status 400
         :body {:error "Failed to create user"
                :message (.getMessage e)
                :details (str (ex-data e))}}))))

(defn get-user-handler
  "GET /api/users/:id - Get user by ID."
  [user-service]
  (fn [{{:keys [path]} :parameters}]
    (let [user-id (type-conversion/string->uuid (:id path))
          user (.find-user-by-id user-service user-id)]
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
          result (.find-users-by-tenant user-service tenant-id options)
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
          current-user (.find-user-by-id user-service user-id)]
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
              result (.update-user user-service updated-user)]
          {:status 200
           :body (schema/user-specific-kebab->camel result)})))))

(defn delete-user-handler
  "DELETE /api/users/:id - Soft delete user."
  [user-service]
  (fn [{{:keys [path]} :parameters}]
    (let [user-id (type-conversion/string->uuid (:id path))]
      (.soft-delete-user user-service user-id)
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
          session (.create-session user-service session-data)
          response-data {:id (type-conversion/uuid->string (:id session))
                         :userId (type-conversion/uuid->string (:user-id session))
                         :tenantId (type-conversion/uuid->string (:tenant-id session))
                         :sessionToken (:session-token session)
                         :expiresAt (type-conversion/instant->string (:expires-at session))
                         :createdAt (type-conversion/instant->string (:created-at session))}]
      {:status 201
       :body response-data})))

(defn validate-session-handler
  "GET /api/sessions/:token - Validate session."
  [user-service]
  (fn [{{:keys [path]} :parameters}]
    (let [session-token (:token path)
          session (.find-session-by-token user-service session-token)]
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
      (.invalidate-session user-service session-token)
      {:status 204})))

;; =============================================================================
;; Health Check
;; =============================================================================

(defn health-check-handler
      "GET /health - Health check endpoint."
  []
  (fn [_request]
    {:status 200
     :body   {:status    "ok"
              :service   "boundary"
              :version   "0.1.0"
              :timestamp (str (java.time.Instant/now))}}))

;; =============================================================================
;; Router Configuration
;; =============================================================================

(defn create-handler
      "Create Ring handler with Reitit router.

       Args:
         user-service: User service instance

       Returns:
         Ring handler function"
  [user-service]
  (ring/ring-handler
    (ring/router
      [["" {}
        ["/health" {:get {:handler (health-check-handler)
                          :summary "Health check"}}]
        ["/api/test" {:get {:handler (fn [_] {:status 200 :body {:message "test works"}})
                            :summary "Test endpoint"}}]
        ["/api/users" {:post {:handler    (create-user-handler user-service)
                              :summary    "Create user"
                              :tags       ["users"]
                              :parameters {:body [:map
                                                  [:email :string]
                                                  [:name :string]
                                                  [:password :string]
                                                  [:role [:enum "admin" "user" "viewer"]]
                                                  [:tenantId :string]
                                                  [:active {:optional true} :boolean]]}}}]
        #_["/api/users" {:post {:handler (fn [_] {:status 201 :body {:message "user created"}})
                                :summary "Create user"}}]]]
      {:data {:coercion   malli/coercion
              :muuntaja   m/instance
              :middleware [wrap-correlation-id
                           wrap-request-logging
                           wrap-exception-handling
                           parameters/parameters-middleware
                           muuntaja/format-negotiate-middleware
                           muuntaja/format-response-middleware
                           muuntaja/format-request-middleware
                           coercion/coerce-request-middleware
                           coercion/coerce-response-middleware]}})

    (ring/create-default-handler
      {:not-found (fn [_]
                    {:status  404
                     :headers {"Content-Type" "application/problem+json"}
                     :body    {:type   "https://boundary.example.com/problems/not-found"
                               :title  "Not Found"
                               :status 404
                               :detail "The requested resource was not found"}})})))
