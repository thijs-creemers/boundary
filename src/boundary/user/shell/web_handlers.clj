(ns boundary.user.shell.web-handlers
  "Web UI handlers for user module.

   This namespace implements Ring handlers that return HTML responses
   for user-related web pages, either full pages or HTMX partial updates.

   Handler Categories:
   - Page Handlers: Return full HTML pages for initial browser requests
   - HTMX Handlers: Return HTML fragments for dynamic updates

   All handlers use the shared UI components and user shell services."
  (:require [boundary.shared.core.utils.type-conversion :as tc]
            [boundary.shared.ui.core.components :as ui]
            [boundary.shared.ui.core.layout :as layout]
            [boundary.shared.ui.core.validation :as validation]
            [boundary.user.core.ui :as user-ui]
            [boundary.user.ports :as user-ports]
            [boundary.user.schema :as user-schema]
            [malli.core :as m]
            [ring.util.response :as response])
  (:import (java.util UUID)))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- resolve-tenant-id
  "Resolve tenant ID from request headers or use configured default.
   
   Args:
     request: Ring request map
     config: Application configuration map
     
   Returns:
     Tenant ID string or UUID"
  [request config]
  (or (get-in request [:headers "x-tenant-id"])
    (get-in config [:active :boundary/settings :default-tenant-id])
    "default"))

(defn- validate-request-data
  "Validate request data against schema with transformation.
   
   Args:
     schema: Malli schema
     data: Data to validate (typically string form params)
     
   Returns:
     [valid? errors transformed-data] tuple where errors is field-keyed map or nil"
  [schema data]
  (let [transformed (m/decode schema data user-schema/user-request-transformer)
        valid?      (m/validate schema transformed)]
    (if valid?
      [true nil transformed]
      (let [explain      (m/explain schema transformed)
            field-errors (validation/explain->field-errors explain)]
        [false field-errors transformed]))))

(defn- html-response
  "Create HTML response map.
   
   Args:
     html: HTML string or Hiccup structure
     status: HTTP status code (optional, defaults to 200)
     headers: Additional headers map (optional)
     
   Returns:
     Ring response map"
  ([html]
   (html-response html 200))
  ([html status]
   (html-response html status {}))
  ([html status extra-headers]
   {:status  status
    :headers (merge {"Content-Type" "text/html; charset=utf-8"} extra-headers)
    :body    (if (string? html) html (ui/render-html html))}))

;; =============================================================================
;; Page Handlers (Full HTML)
;; =============================================================================

(defn users-page-handler
  "Handler for the users listing page (GET /web/users).
   
   Fetches users from the user service and renders them in a table.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (try
      (let [tenant-id    (resolve-tenant-id request config)
            users-result (user-ports/list-users-by-tenant user-service tenant-id {:limit  100
                                                                                  :offset 0})
            page-opts    {:user  (get request :user)
                          :flash (get request :flash)}]
        (html-response (user-ui/users-page (:users users-result) page-opts)))
      (catch Exception e
        (html-response
          (layout/page-layout "Error"
            (ui/error-message (.getMessage e)))
          500)))))

(defn user-detail-page-handler
  "Handler for individual user detail page (GET /web/users/:id).
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (try
      (let [user-id     (get-in request [:path-params :id])
            user-result (user-ports/get-user-by-id user-service (UUID/fromString user-id))
            page-opts   {:user  (get request :user)
                         :flash (get request :flash)}]
        (if user-result
          (html-response (user-ui/user-detail-page user-result page-opts))
          (html-response
            (layout/error-layout 404 "User Not Found"
              "The requested user could not be found.")
            404)))
      (catch IllegalArgumentException _
        (html-response
          (layout/error-layout 400 "Invalid User ID"
            "User ID must be a valid UUID.")
          400))
      (catch Exception e
        (html-response
          (layout/page-layout "Error"
            (ui/error-message (.getMessage e)))
          500)))))

(defn create-user-page-handler
  "Handler for the create user page (GET /web/users/new).
   
   Args:
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [config]
  (fn [request]
    (let [page-opts {:user  (get request :user)
                     :flash (get request :flash)}]
      (html-response (user-ui/create-user-page {} {} page-opts)))))

;; =============================================================================
;; Authentication Handlers (Login / Logout)
;; =============================================================================

(defn login-page-handler
  "GET /web/login - render login page."
  [config]
  (fn [request]
    (let [page-opts {:user  (get request :user)
                     :flash (get request :flash)}]
      (html-response (user-ui/login-page {} {} page-opts)))))

(defn login-submit-handler
  "POST /web/login - validate credentials, authenticate, set session cookie."
  [user-service config]
  (fn [request]
    (let [form-data     (:form-params request)
          tenant-id     (resolve-tenant-id request config)
          prepared-data {:email      (get form-data "email")
                         :password   (get form-data "password")
                         :remember   (= "on" (get form-data "remember"))
                         :tenant-id  tenant-id
                         :ip-address (:remote-addr request)
                         :user-agent (get-in request [:headers "user-agent"])}
          [valid? validation-errors _]
          (validate-request-data user-schema/LoginRequest prepared-data)]
      (if-not valid?
        ;; Re-render login page with validation errors
        (html-response
          (user-ui/login-page prepared-data validation-errors
            {:user  (get request :user)
             :flash (get request :flash)})
          400)
        (try
          ;; Use IUserService/authenticate-user
          (let [auth-result (user-ports/authenticate-user user-service prepared-data)]
            (if (:authenticated auth-result)
              (let [session       (:session auth-result)
                    session-token (:session-token session)]
                ;; Set session-token cookie; flexible-auth middleware will pick this up.
                (-> (response/redirect "/web/users")
                  (assoc-in [:cookies "session-token"]
                    {:value     session-token
                     :http-only true
                     ;; set :secure true when running behind HTTPS
                     :secure    false
                     :path      "/"})))
              ;; Authentication failed (e.g. wrong password)
              (html-response
                (user-ui/login-page prepared-data
                  {:password ["Invalid email or password"]}
                  {:user  (get request :user)
                   :flash (get request :flash)})
                401)))
          (catch Exception e
            (html-response
              (layout/page-layout "Login error"
                (ui/error-message (.getMessage e)))
              500)))))))

(defn logout-handler
  "POST /web/logout - clear session cookie and redirect to login."
  [user-service _config]
  (fn [request]
    (let [session-token (or (get-in request [:cookies "session-token" :value])
                          (get-in request [:headers "x-session-token"]))]
      (when session-token
        (try
          ;; Best-effort server-side logout
          (user-ports/logout-user user-service session-token)
          (catch Exception _e)))
      (-> (response/redirect "/web/login")
        (assoc :cookies {"session-token"
                         {:value "" :max-age 0 :path "/"}})))))

(defn register-page-handler
  "GET /web/register - render self-service registration page."
  [config]
  (fn [request]
    (let [page-opts {:user  (get request :user)
                     :flash (get request :flash)}]
      (html-response (user-ui/register-page {} {} page-opts)))))

(defn register-submit-handler
  "POST /web/register - validate data, create user account."
  [user-service config]
  (fn [request]
    (let [form-data     (:form-params request)
          tenant-id-val (resolve-tenant-id request config)
          tenant-id     (if (instance? java.util.UUID tenant-id-val)
                          tenant-id-val
                          (tc/string->uuid tenant-id-val))
          _             (when-not tenant-id
                          (throw (ex-info "Invalid or missing tenant-id for registration"
                                   {:type      :validation-error
                                    :reason    :invalid-tenant-id
                                    :tenant-id tenant-id-val})))
          prepared-data {:name      (get form-data "name")
                         :email     (get form-data "email")
                         :password  (get form-data "password")
                         ;; Self-service accounts are always regular users and active
                         :role      :user
                         :active    true
                         :tenant-id tenant-id}
          [valid? validation-errors _]
          (validate-request-data user-schema/CreateUserRequest prepared-data)]
      (if-not valid?
        (html-response
          (user-ui/register-page prepared-data validation-errors
            {:user  (get request :user)
             :flash (get request :flash)})
          400)
        (try
          (let [user-result  (user-ports/register-user user-service prepared-data)
                success-html (user-ui/user-created-success user-result)]
            ;; Show success message; user can proceed to login
            (html-response success-html 201))
          (catch Exception e
            (html-response
              (layout/page-layout "Registration error"
                (ui/error-message (.getMessage e)))
              500)))))))

;; =============================================================================
;; HTMX Fragment Handlers
;; =============================================================================

(defn users-table-fragment-handler
  "Handler for refreshing the users table (GET /web/users/table).
   
   Returns only the table container fragment for HTMX replacement.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (try
      (let [tenant-id    (resolve-tenant-id request config)
            users-result (user-ports/list-users-by-tenant user-service tenant-id {:limit  100
                                                                                  :offset 0})]
        (html-response (user-ui/users-table-fragment (:users users-result))))
      (catch Exception e
        (html-response (ui/error-message (.getMessage e)) 500)))))

(defn create-user-htmx-handler
  "HTMX handler for creating a new user (POST /web/users).
   
   Validates form data and creates user, returning HTML fragment.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (let [form-data     (:form-params request)
          tenant-id-val (resolve-tenant-id request config)
          tenant-id     (if (instance? java.util.UUID tenant-id-val)
                          tenant-id-val
                          (tc/string->uuid tenant-id-val))
          _             (when-not tenant-id
                          (throw (ex-info "Invalid or missing tenant-id for user creation"
                                   {:type      :validation-error
                                    :reason    :invalid-tenant-id
                                    :tenant-id tenant-id-val})))
          ;; Prepare data with kebab-case keyword keys for validation
          prepared-data {:name      (get form-data "name")
                         :email     (get form-data "email")
                         :password  (get form-data "password")
                         :role      (keyword (get form-data "role"))
                         :active    (= "true" (get form-data "active"))
                         :tenant-id tenant-id}
          [valid? validation-errors _] (validate-request-data user-schema/CreateUserRequest prepared-data)]
      (if-not valid?
        (html-response (user-ui/create-user-form prepared-data validation-errors) 400)
        (try
          (let [user-result  (user-ports/register-user user-service prepared-data)
                success-html (user-ui/user-created-success user-result)]
            (html-response success-html 201 {"HX-Trigger" "userCreated"}))
          (catch Exception e
            (html-response (ui/error-message (.getMessage e)) 500)))))))

(defn update-user-htmx-handler
  "HTMX handler for updating a user (PUT /web/users/:id).
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (try
      (let [user-id       (get-in request [:path-params :id])
            form-data     (:form-params request)
            ;; Prepare data with kebab-case keyword keys for validation
            prepared-data {:name   (get form-data "name")
                           :email  (get form-data "email")
                           :role   (when-let [role (get form-data "role")] (keyword role))
                           :active (= "true" (get form-data "active"))}
            [valid? validation-errors _] (validate-request-data user-schema/UpdateUserRequest prepared-data)
            user-data     (assoc prepared-data :id (UUID/fromString user-id))]
        (if-not valid?
          (html-response (user-ui/user-detail-form user-data) 400)
          (try
            (let [user-result  (user-ports/update-user-profile user-service user-data)
                  success-html (user-ui/user-updated-success user-result)]
              (html-response success-html 200 {"HX-Trigger" "userUpdated"}))
            (catch Exception e
              (html-response (ui/error-message (.getMessage e)) 500)))))
      (catch IllegalArgumentException _
        (html-response (ui/error-message "Invalid user ID") 400)))))

(defn delete-user-htmx-handler
  "HTMX handler for deactivating a user (DELETE /web/users/:id).
   
   Performs soft delete via deactivate-user.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (try
      (let [user-id (get-in request [:path-params :id])
            uuid    (UUID/fromString user-id)]
        (user-ports/deactivate-user user-service uuid)
        (html-response (user-ui/user-deleted-success user-id)
          200
          {"HX-Trigger" "userDeleted"}))
      (catch IllegalArgumentException _
        (html-response (ui/error-message "Invalid user ID") 400))
      (catch Exception e
        (html-response (ui/error-message (.getMessage e)) 500)))))
