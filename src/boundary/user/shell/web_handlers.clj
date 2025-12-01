(ns boundary.user.shell.web-handlers
  "Web UI handlers for user module.

   This namespace implements Ring handlers that return HTML responses
   for user-related web pages, either full pages or HTMX partial updates.

   Handler Categories:
   - Page Handlers: Return full HTML pages for initial browser requests
   - HTMX Handlers: Return HTML fragments for dynamic updates

   All handlers use the shared UI components and user shell services."
  (:require [boundary.shared.ui.core.components :as ui]
            [boundary.shared.ui.core.layout :as layout]
            [boundary.shared.ui.core.validation :as validation]
            [boundary.shared.web.table :as web-table]
            [boundary.user.core.ui :as user-ui]
            [boundary.user.ports :as user-ports]
            [boundary.user.schema :as user-schema]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [ring.util.response :as response])
  (:import (java.util UUID)))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- safe-return-url
  "Validate and sanitize return URL to prevent open redirect attacks.
   
   Args:
     url: String URL to validate
     default: Default URL if validation fails
     
   Returns:
     Safe local URL string"
  [url default]
  (if (and url
           (string? url)
           (str/starts-with? url "/")
           (not (str/starts-with? url "//")))
    url
    default))

(defn- validate-request-data
  "Validate request data against schema with transformation.
   
   Args:
     schema: Malli schema
     data: Data to validate (typically string form params)
     
   Returns:
     [valid? errors transformed-data] tuple where errors is field-keyed map or nil"
  [schema data]
  (let [transformed (m/decode schema data user-schema/user-request-transformer)
        valid? (m/validate schema transformed)]
    (if valid?
      [true nil transformed]
      (let [explain (m/explain schema transformed)
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
   (let [body-content (if (string? html) html (ui/render-html html))
         response {:status status
                   :headers (merge {"Content-Type" "text/html; charset=utf-8"} extra-headers)
                   :body body-content}]
     ;; Debug logging to check response structure
     (clojure.tools.logging/debug "HTML response created"
                                  {:status status
                                   :body-type (type body-content)
                                   :body-string? (string? body-content)
                                   :body-length (count (str body-content))})
     response)))

(defn- build-user-list-opts
  "Build list-users options from table query and search filters.

   Only active (non-deleted) users are returned."
  [table-query filters]
  (let [base {:limit          (:limit table-query)
              :offset         (:offset table-query)
              :sort-by        (:sort table-query)
              :sort-direction (:dir table-query)
              :filter-active  true}]
    (cond-> base
      (:q filters)
      (assoc :filter-email-contains (:q filters))

      (:role filters)
      (assoc :filter-role (keyword (:role filters))))))

;; =============================================================================
;; Page Handlers (Full HTML)
;; =============================================================================

(defn users-page-handler
  "Handler for the users listing page (GET /web/users).
   
   Fetches users from the user service and renders them in a table, with
   support for shared search filters (q, role, status)."
  [user-service config]
  (fn [request]
    (try
      (let [qp          (:query-params request)
            table-query (web-table/parse-table-query
                         qp
                         {:default-sort      :created-at
                          :default-dir       :desc
                          :default-page-size 20})
            filters     (web-table/parse-search-filters qp)
            list-opts   (build-user-list-opts table-query filters)
            users-result (user-ports/list-users user-service list-opts)
            page-opts    {:user        (get request :user)
                          :flash       (get request :flash)
                          :table-query table-query
                          :filters     filters}]
        (html-response
         (user-ui/users-page
          (:users users-result)
          (:total-count users-result)
          page-opts)))
      (catch Exception e
        (clojure.tools.logging/error e "Error in users-page-handler")
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
      (let [user-id (get-in request [:path-params :id])
            user-result (user-ports/get-user-by-id user-service (UUID/fromString user-id))
            page-opts {:user (get request :user)
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
    (let [page-opts {:user (get request :user)
                     :flash (get request :flash)}]
      (html-response (user-ui/create-user-page {} {} page-opts)))))

;; =============================================================================
;; Authentication Handlers (Login / Logout)
;; =============================================================================

(defn login-page-handler
  "GET /web/login - render login page."
  [config]
  (fn [request]
    (let [return-to (get-in request [:query-params "return-to"])
          ;; Check if we have a remembered email from previous login
          remembered-email (get-in request [:cookies "remembered-email" :value])
          initial-data (if remembered-email
                         {:email remembered-email
                          :remember true}
                         {})
          page-opts {:user (get request :user)
                     :flash (get request :flash)
                     :return-to return-to}]
      (html-response (user-ui/login-page initial-data {} page-opts)))))

(defn login-submit-handler
  "POST /web/login - validate credentials, authenticate, set session cookie."
  [user-service config]
  (fn [request]
    (let [form-data (:form-params request)
          raw-return-to (or (get form-data "return-to")
                            (get-in request [:query-params "return-to"]))
          return-to (safe-return-url raw-return-to "/web/users")
          prepared-data {:email (get form-data "email")
                         :password (get form-data "password")
                         :remember (= "on" (get form-data "remember"))
                         :ip-address (:remote-addr request)
                         :user-agent (get-in request [:headers "user-agent"])}
          [valid? validation-errors validated-data]
          (validate-request-data user-schema/LoginRequest prepared-data)]
      (if-not valid?
        ;; Re-render login page with validation errors, preserving return-to
        (html-response
         (user-ui/login-page prepared-data validation-errors
                             {:user (get request :user)
                              :flash (get request :flash)
                              :return-to return-to})
         400)
        (try
          ;; Use IUserService/authenticate-user with validated data
          (let [auth-result (user-ports/authenticate-user user-service validated-data)]
            (log/info "Login attempt" {:email (:email validated-data)
                                       :authenticated (:authenticated auth-result)
                                       :result-keys (keys auth-result)
                                       :ip-address (:ip-address validated-data)
                                       :user-agent (:user-agent validated-data)})
            (if (:authenticated auth-result)
              (let [session (:session auth-result)
                    session-token (:session-token session)
                    remember? (:remember validated-data)
                      ;; Calculate cookie max-age based on remember-me checkbox
                      ;; 30 days if remember-me is checked, otherwise session cookie (no max-age)
                    cookie-max-age (when remember? (* 30 24 60 60))]
                (log/info "Login successful, setting cookie and redirecting"
                          {:session-token (str (take 16 session-token) "...")
                           :remember? remember?
                           :cookie-max-age cookie-max-age
                           :return-to return-to})
                  ;; Set session-token cookie and redirect to original URL or default
                (-> (response/redirect return-to)
                    (assoc-in [:cookies "session-token"]
                              (cond-> {:value session-token
                                       :http-only true
                                         ;; set :secure true when running behind HTTPS
                                       :secure false
                                       :path "/"}
                                  ;; Only set max-age if remember-me is checked
                                  ;; Without max-age, cookie is a session cookie (expires when browser closes)
                                remember? (assoc :max-age cookie-max-age)))
                      ;; Set or clear the remembered-email cookie
                    (assoc-in [:cookies "remembered-email"]
                              (if remember?
                                  ;; Remember the email for 30 days
                                {:value (:email validated-data)
                                 :http-only false  ; Allow JavaScript to read it if needed
                                 :secure false
                                 :path "/"
                                 :max-age cookie-max-age}
                                  ;; Clear the remembered email by setting expired cookie
                                {:value ""
                                 :max-age 0
                                 :path "/"}))))
              ;; Authentication failed (e.g. wrong password)
              (do
                (log/warn "Login failed" {:email (:email prepared-data)
                                          :reason (:reason auth-result)})
                (html-response
                 (user-ui/login-page prepared-data
                                     {:password ["Invalid email or password"]}
                                     {:user (get request :user)
                                      :flash (get request :flash)
                                      :return-to return-to})
                 401))))
          (catch Exception e
            (log/error e "Login error" {:email (:email prepared-data)})
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
      (spit "/tmp/middleware-debug.log"
            (str "LOGOUT HANDLER CALLED: has-token=" (boolean session-token)
                 " token=" (when session-token (subs session-token 0 (min 20 (count session-token)))) "...\n")
            :append true)
      (when session-token
        (try
          ;; Best-effort server-side logout
          (log/info "Attempting to invalidate session" {:token (subs session-token 0 16)})
          (let [result (user-ports/logout-user user-service session-token)]
            (log/info "Session invalidation result" {:result result})
            (spit "/tmp/middleware-debug.log"
                  (str "LOGOUT RESULT: " result "\n")
                  :append true))
          (catch Exception e
            (log/error e "Error during logout")
            (spit "/tmp/middleware-debug.log"
                  (str "LOGOUT ERROR: " (.getMessage e) "\n")
                  :append true))))
      (-> (response/redirect "/web/login")
          (assoc :cookies {"session-token"
                           {:value "" :max-age 0 :path "/"}})))))

(defn register-page-handler
  "GET /web/register - render self-service registration page."
  [config]
  (fn [request]
    (let [page-opts {:user (get request :user)
                     :flash (get request :flash)}]
      (html-response (user-ui/register-page {} {} page-opts)))))

(defn register-submit-handler
  "POST /web/register - validate data, create user account."
  [user-service config]
  (fn [request]
    (let [form-data (:form-params request)
          prepared-data {:name (get form-data "name")
                         :email (get form-data "email")
                         :password (get form-data "password")
                         ;; Self-service accounts are always regular users and active
                         :role :user
                         :active true}
          [valid? validation-errors _]
          (validate-request-data user-schema/CreateUserRequest prepared-data)]
      (if-not valid?
        (html-response
         (user-ui/register-page prepared-data validation-errors
                                {:user (get request :user)
                                 :flash (get request :flash)})
         400)
        (try
          (let [user-result (user-ports/register-user user-service prepared-data)
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

   Returns only the table container fragment for HTMX replacement."
  [user-service config]
  (fn [request]
    (try
      (let [qp          (:query-params request)
            table-query (web-table/parse-table-query
                         qp
                         {:default-sort      :created-at
                          :default-dir       :desc
                          :default-page-size 20})
            filters     (web-table/parse-search-filters qp)
            list-opts   (build-user-list-opts table-query filters)
            users-result (user-ports/list-users user-service list-opts)]
        (html-response
         (user-ui/users-table-fragment
          (:users users-result)
          table-query
          (:total-count users-result)
          filters)))
      (catch Exception e
        (html-response (ui/error-message (.getMessage e)) 500)))))

(defn bulk-update-users-htmx-handler
  "HTMX handler for bulk user operations (POST /web/users/bulk).

   Supports multiple actions: deactivate, activate, delete, role changes.
   Returns the updated users table fragment using the same query
   parameters and filters as the current view."
  [user-service config]
  (fn [request]
    (try
      (let [form-params (:form-params request)
            ids-param   (get form-params "user-ids")
            action      (get form-params "action")
            ids         (cond
                          (nil? ids-param) []
                          (sequential? ids-param) ids-param
                          :else [ids-param])
            _           (log/info "Bulk user operation" {:action action
                                                         :ids-count (count ids)})
            ;; Merge query params from URL and hidden form fields to
            ;; preserve current filters and table state during bulk ops
            qp          (merge (:query-params request)
                               (select-keys form-params ["q" "role" "status" "sort" "dir" "page" "page-size"]))
            table-query (web-table/parse-table-query
                         qp
                         {:default-sort      :created-at
                          :default-dir       :desc
                          :default-page-size 20})
            filters     (web-table/parse-search-filters qp)]
        (when (or (empty? ids) (str/blank? (str action)))
          (throw (ex-info "No users selected or action missing"
                          {:type :bad-request})))
        ;; Execute bulk operation based on action type
        (case action
          "deactivate"
          (doseq [id-str ids]
            (let [uuid (UUID/fromString id-str)]
              (user-ports/deactivate-user user-service uuid)))

          "activate"
          (doseq [id-str ids]
            (let [uuid (UUID/fromString id-str)
                  user (user-ports/get-user-by-id user-service uuid)]
              (when user
                ;; Reactivate by updating with active=true and clearing deleted_at
                (user-ports/update-user-profile user-service (assoc user :active true :deleted_at nil)))))

          "delete"
          (doseq [id-str ids]
            (let [uuid (UUID/fromString id-str)]
              (user-ports/permanently-delete-user user-service uuid)))

          "role-admin"
          (doseq [id-str ids]
            (let [uuid (UUID/fromString id-str)
                  user (user-ports/get-user-by-id user-service uuid)]
              (when user
                (user-ports/update-user-profile user-service (assoc user :role :admin)))))

          "role-user"
          (doseq [id-str ids]
            (let [uuid (UUID/fromString id-str)
                  user (user-ports/get-user-by-id user-service uuid)]
              (when user
                (user-ports/update-user-profile user-service (assoc user :role :user)))))

          (throw (ex-info "Unsupported bulk action"
                          {:type :bad-request
                           :action action})))
        ;; After bulk operation, reuse users-table-fragment-handler logic
        (let [fragment-handler (users-table-fragment-handler user-service config)
              get-request      {:query-params qp}]
          (fragment-handler get-request)))
      (catch IllegalArgumentException _
        (html-response (ui/error-message "Invalid user ID in bulk operation") 400))

      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (= (:type data) :bad-request)
            (html-response (ui/error-message (.getMessage e)) 400)
            (do
              (log/error e "Bulk user operation failed")
              (html-response (ui/error-message (.getMessage e)) 500)))))

      (catch Exception e
        (log/error e "Unexpected error in bulk-update-users-htmx-handler")
        (html-response (ui/error-message (.getMessage e)) 500)))))

(defn create-user-htmx-handler
  "HTMX handler for creating a new user (POST /web/users).
   
   Validates form data and creates user, redirecting to user detail page on success.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (let [form-data (:form-params request)
          ;; Prepare data with kebab-case keyword keys for validation
          prepared-data {:name (get form-data "name")
                         :email (get form-data "email")
                         :password (get form-data "password")
                         :role (keyword (get form-data "role"))
                         :send-welcome (= "on" (get form-data "send-welcome"))}
          [valid? validation-errors _] (validate-request-data user-schema/CreateUserRequest prepared-data)]
      (if-not valid?
        (html-response (user-ui/create-user-form prepared-data validation-errors) 400)
        (try
          (let [user-result (user-ports/register-user user-service prepared-data)]
            ;; Redirect to user detail page instead of showing success message
            (html-response
             [:div
              [:script "window.location.href = '/web/users/" (:id user-result) "';"]]
             201
             {"HX-Trigger" "userCreated"}))
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
      (let [user-id (get-in request [:path-params :id])
            form-data (:form-params request)
            _ (log/info "Update user form data" {:form-data form-data
                                                 :role-value (get form-data "role")
                                                 :active-value (get form-data "active")})
            ;; Prepare data with kebab-case keyword keys for validation
            ;; Note: Checkbox fields are "on" when checked, absent when unchecked
            prepared-data {:name (get form-data "name")
                           :email (get form-data "email")
                           :role (when-let [role (get form-data "role")] (keyword role))
                           :active (= "on" (get form-data "active"))}
            _ (log/info "Prepared data for update" {:prepared-data prepared-data})
            [valid? validation-errors _] (validate-request-data user-schema/UpdateUserRequest prepared-data)]
        (if-not valid?
          (html-response (user-ui/user-detail-form (assoc prepared-data :id (UUID/fromString user-id))) 400)
          (try
            ;; Fetch existing user and merge updates
            (let [uuid (UUID/fromString user-id)
                  existing-user (user-ports/get-user-by-id user-service uuid)]
              (if-not existing-user
                (html-response (ui/error-message "User not found") 404)
                (let [;; Merge form data into existing user (only update provided fields)
                      user-data (merge existing-user
                                       (select-keys prepared-data [:name :email :role :active]))
                      user-result (user-ports/update-user-profile user-service user-data)]
                  ;; Re-render the form with updated user data and a success indicator
                  (html-response
                   [:div
                    [:div.success-banner {:style "background: #d4edda; color: #155724; padding: 10px; margin-bottom: 15px; border-radius: 4px;"}
                     "✓ User updated successfully"]
                    (user-ui/user-detail-form user-result)]
                   200
                   {"HX-Trigger" "userUpdated"}))))
            (catch Exception e
              (log/error e "Error updating user")
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
            uuid (UUID/fromString user-id)]
        (user-ports/deactivate-user user-service uuid)
        (html-response (user-ui/user-deleted-success user-id)
                       200
                       {"HX-Trigger" "userDeleted"}))
      (catch IllegalArgumentException _
        (html-response (ui/error-message "Invalid user ID") 400))
      (catch Exception e
        (html-response (ui/error-message (.getMessage e)) 500)))))

(defn hard-delete-user-handler
  "Handler for permanently deleting a user (POST /web/users/:id/hard-delete).
   
   ⚠️  IRREVERSIBLE OPERATION - Permanently removes user and all related data.
   Should only be used for GDPR compliance or similar requirements.
   Requires admin role.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (try
      (let [user-id (get-in request [:path-params :id])
            uuid (UUID/fromString user-id)
            current-user (:user request)]
        ;; Check if current user is admin
        (if-not (= :admin (:role current-user))
          (html-response
           (layout/error-layout 403 "Access Denied"
                                "Only administrators can permanently delete users.")
           403)
          (do
            (user-ports/permanently-delete-user user-service uuid)
            (-> (response/redirect "/web/users")
                (assoc :flash {:success "User permanently deleted"})))))
      (catch IllegalArgumentException _
        (html-response (ui/error-message "Invalid user ID") 400))
      (catch Exception e
        (log/error e "Error permanently deleting user")
        (html-response (ui/error-message (.getMessage e)) 500)))))

;; =============================================================================
;; Session Management Handlers
;; =============================================================================

(defn user-sessions-page-handler
  "Handler for user sessions management page (GET /web/users/:id/sessions).
   
   Displays all active sessions for a user with ability to revoke individual
   sessions or all sessions except current.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (try
      (let [user-id (get-in request [:path-params :id])
            _ (log/info "Sessions page handler - user-id:" user-id "path-params:" (:path-params request) "uri:" (:uri request))
            uuid (UUID/fromString user-id)
            user (user-ports/get-user-by-id user-service uuid)
            sessions (user-ports/get-user-sessions user-service uuid)
            _ (log/info "Sessions retrieved:" {:count (count sessions)
                                               :first-session (first sessions)
                                               :session-keys (when (seq sessions) (keys (first sessions)))})
            current-token (get-in request [:cookies "session-token" :value])
            opts {:user (:user request)
                  :flash (:flash request)}]
        (if user
          (html-response (user-ui/user-sessions-page user sessions current-token opts))
          (html-response
           (layout/error-layout 404 "User Not Found" "The requested user could not be found.")
           404)))
      (catch IllegalArgumentException e
        (log/error e "Invalid user ID format - user-id:" (get-in request [:path-params :id]) "path-params:" (:path-params request))
        (html-response (ui/error-message "Invalid user ID") 400))
      (catch Exception e
        (log/error e "Error loading user sessions page")
        (html-response (ui/error-message (.getMessage e)) 500)))))

(defn revoke-session-handler
  "Handler for revoking a single session (POST /web/sessions/:token/revoke).
   
   Invalidates the specified session token, forcing that device to sign in again.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (try
      (let [session-token (get-in request [:path-params :token])
            user-id (get-in request [:form-params "user-id"])]
        (user-ports/logout-user user-service session-token)
        (-> (response/redirect (str "/web/users/" user-id "/sessions"))
            (assoc :flash {:success "Session revoked successfully"})))
      (catch Exception e
        (log/error e "Error revoking session")
        (html-response (ui/error-message (.getMessage e)) 500)))))

(defn revoke-all-sessions-handler
  "Handler for revoking all user sessions (POST /web/users/:id/sessions/revoke-all).
   
   Invalidates all sessions for a user, optionally keeping the current session active.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (try
      (let [user-id (get-in request [:path-params :id])
            uuid (UUID/fromString user-id)
            keep-current? (= "true" (get-in request [:form-params "keep-current"]))
            current-token (when keep-current?
                            (get-in request [:cookies "session-token" :value]))]
        (if keep-current?
          ;; Revoke all except current
          (let [sessions (user-ports/get-user-sessions user-service uuid)]
            (doseq [session sessions]
              (when (not= (:token session) current-token)
                (user-ports/logout-user user-service (:token session)))))
          ;; Revoke all including current
          (user-ports/logout-user-everywhere user-service uuid))
        (-> (response/redirect (str "/web/users/" user-id "/sessions"))
            (assoc :flash {:success "Sessions revoked successfully"})))
      (catch IllegalArgumentException _
        (html-response (ui/error-message "Invalid user ID") 400))
      (catch Exception e
        (log/error e "Error revoking all sessions")
        (html-response (ui/error-message (.getMessage e)) 500)))))

;; =============================================================================
;; Audit Trail Handlers
;; =============================================================================

(defn- build-audit-list-opts
  "Build list-audit-logs options from table query and search filters.
   
   Supports filtering by:
   - action: Audit action type (:create, :update, :delete, :login, etc.)
   - result: Audit result (:success or :failure)
   - target_email: Target user email (contains search)
   - actor_email: Actor email (contains search)
   
   Args:
     table-query: Parsed table query with limit/offset/sort/dir
     filters: Parsed search filters from query params
     
   Returns:
     Options map for list-audit-logs service call"
  [table-query filters]
  (let [base {:limit          (:limit table-query)
              :offset         (:offset table-query)
              :sort-by        (:sort table-query)
              :sort-direction (:dir table-query)}]
    (cond-> base
      (:action filters)
      (assoc :filter-action (keyword (:action filters)))

      (:result filters)
      (assoc :filter-result (keyword (:result filters)))

      (:target_email filters)
      (assoc :filter-target-email (:target_email filters))

      (:actor_email filters)
      (assoc :filter-actor-email (:actor_email filters)))))

(defn audit-page-handler
  "Handler for the audit trail page (GET /web/audit).
   
   Displays audit logs in a table with filtering, sorting, and pagination.
   Supports filters for action type, result, target email, and actor email.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (try
      (let [qp          (:query-params request)
            table-query (web-table/parse-table-query
                         qp
                         {:default-sort      :created-at
                          :default-dir       :desc
                          :default-page-size 50})
            filters     (web-table/parse-search-filters qp)
            list-opts   (build-audit-list-opts table-query filters)
            audit-result (user-ports/list-audit-logs user-service list-opts)
            page-opts    {:user        (get request :user)
                          :flash       (get request :flash)
                          :table-query table-query
                          :filters     filters}]
        (html-response
         (user-ui/audit-page
          (:audit-logs audit-result)
          table-query
          (:total-count audit-result)
          filters
          page-opts)))
      (catch Exception e
        (log/error e "Error in audit-page-handler")
        (html-response
         (layout/page-layout "Error"
                             (ui/error-message (.getMessage e)))
         500)))))

(defn audit-table-fragment-handler
  "Handler for the audit table fragment (GET /web/audit/table).
   
   Returns only the audit table HTML for HTMX refresh when filters change.
   This enables client-side filtering without full page reload.
   
   Args:
     user-service: User service instance
     config: Application configuration map
     
   Returns:
     Ring handler function"
  [user-service config]
  (fn [request]
    (try
      (let [qp          (:query-params request)
            table-query (web-table/parse-table-query
                         qp
                         {:default-sort      :created-at
                          :default-dir       :desc
                          :default-page-size 50})
            filters     (web-table/parse-search-filters qp)
            list-opts   (build-audit-list-opts table-query filters)
            audit-result (user-ports/list-audit-logs user-service list-opts)
            page-opts    {:table-query table-query
                          :filters     filters}]
        (html-response
         (user-ui/audit-logs-table
          (:audit-logs audit-result)
          table-query
          (:total-count audit-result)
          filters)))
      (catch Exception e
        (log/error e "Error in audit-table-fragment-handler")
        (html-response (ui/error-message (.getMessage e)) 500)))))

