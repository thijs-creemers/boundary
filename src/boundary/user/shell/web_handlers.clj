(ns boundary.user.shell.web-handlers
  "Web UI handlers for user module.

   This namespace implements Ring handlers that return HTML responses
   for user-related web pages, either full pages or HTMX partial updates.

   Handler Categories:
   - Page Handlers: Return full HTML pages for initial browser requests
   - HTMX Handlers: Return HTML fragments for dynamic updates  

   All handlers use the shared UI components and user shell services."
  (:require [boundary.user.core.ui :as user-ui]
            [boundary.shared.ui.core.components :as ui]
            [boundary.shared.ui.core.layout :as layout]
            [boundary.user.ports :as user-ports]
            [boundary.user.schema :as user-schema]
            [malli.core :as m]
            [ring.util.response :as response]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- validate-request-data
  "Validate request data against schema.
   
   Args:
     schema: Malli schema
     data: Data to validate
     
   Returns:
     [valid? errors] tuple"
  [schema data]
  (let [valid? (m/validate schema data)]
    (if valid?
      [true nil]
      [false (m/explain schema data)])))

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
   {:status status
    :headers (merge {"Content-Type" "text/html; charset=utf-8"} extra-headers)
    :body (if (string? html) html (ui/render-html html))}))

;; =============================================================================
;; Page Handlers
;; =============================================================================

(defn users-page-handler
  "Handler for the users listing page (GET /users).
   
   Fetches users from the user service and renders them in a table.
   
   Args:
     user-service: User service instance
     
   Returns:
     Ring handler function"
  [user-service]
  (fn [request]
    (try
      (let [tenant-id (get-in request [:headers "x-tenant-id"] "default")
            users-result (user-ports/list-users-by-tenant user-service tenant-id {:limit 100
                                                                                  :offset 0})
            page-opts {:user (get request :user)
                       :flash (get request :flash)}]
        (html-response (user-ui/users-page (:users users-result) page-opts)))
      (catch Exception e
        (html-response
         (layout/page-layout "Error"
                             (ui/error-message (.getMessage e)))
         500)))))

(defn user-detail-page-handler
  "Handler for individual user detail page (GET /users/:id).
   
   Args:
     user-service: User service instance
     
   Returns:
     Ring handler function"
  [user-service]
  (fn [request]
    (try
      (let [user-id (Integer/parseInt (get-in request [:path-params :id]))
            user-result (user-ports/get-user-by-id user-service user-id)
            page-opts {:user (get request :user)
                       :flash (get request :flash)}]
        (if user-result
          (html-response (user-ui/user-detail-page user-result page-opts))
          (html-response
           (layout/error-layout 404 "User Not Found"
                                "The requested user could not be found.")
           404)))
      (catch NumberFormatException _
        (html-response
         (layout/error-layout 400 "Invalid User ID"
                              "User ID must be a valid number.")
         400))
      (catch Exception e
        (html-response
         (layout/page-layout "Error"
                             (ui/error-message (.getMessage e)))
         500)))))

(defn create-user-page-handler
  "Handler for the create user page (GET /users/new).
   
   Returns:
     Ring handler function"
  []
  (fn [request]
    (let [page-opts {:user (get request :user)
                     :flash (get request :flash)}]
      (html-response (user-ui/create-user-page {} {} page-opts)))))

;; =============================================================================
;; HTMX Handlers
;; =============================================================================

(defn create-user-htmx-handler
  "HTMX handler for creating a new user (POST /users).
   
   Validates form data and creates user, returning HTML fragment.
   
   Args:
     user-service: User service instance
     
   Returns:
     Ring handler function"
  [user-service]
  (fn [request]
    (let [form-data (:form-params request)
          [valid? validation-errors] (validate-request-data user-schema/CreateUserRequest form-data)]
      (if-not valid?
        (html-response (user-ui/user-validation-errors validation-errors) 400)
        (try
          (let [user-result (user-ports/register-user user-service form-data)
                success-html (user-ui/user-created-success user-result)]
            (html-response success-html 201 {"HX-Trigger" "userCreated"}))
          (catch Exception e
            (html-response (ui/error-message (.getMessage e)) 500)))))))

(defn update-user-htmx-handler
  "HTMX handler for updating a user (PUT /users/:id).
   
   Args:
     user-service: User service instance
     
   Returns:
     Ring handler function"
  [user-service]
  (fn [request]
    (try
      (let [user-id (Integer/parseInt (get-in request [:path-params :id]))
            form-data (:form-params request)
            [valid? validation-errors] (validate-request-data user-schema/UpdateUserRequest form-data)]
        (if-not valid?
          (html-response (user-ui/user-validation-errors validation-errors) 400)
          (try
            (let [user-result (user-ports/update-user-profile user-service (assoc form-data :id user-id))
                  success-html (user-ui/user-updated-success user-result)]
              (html-response success-html 200 {"HX-Trigger" "userUpdated"}))
            (catch Exception e
              (html-response (ui/error-message (.getMessage e)) 500)))))
      (catch NumberFormatException _
        (html-response (ui/error-message "Invalid user ID") 400)))))

(defn delete-user-htmx-handler
  "HTMX handler for deleting a user (DELETE /users/:id).
   
   Args:
     user-service: User service instance
     
   Returns:
     Ring handler function"
  [user-service]
  (fn [request]
    (try
      (let [user-id (Integer/parseInt (get-in request [:path-params :id]))]
        (user-ports/deactivate-user user-service user-id)
        (html-response (user-ui/user-deleted-success user-id)
                       200
                       {"HX-Trigger" "userDeleted"}))
      (catch NumberFormatException _
        (html-response (ui/error-message "Invalid user ID") 400))
      (catch Exception e
        (html-response (ui/error-message (.getMessage e)) 500)))))

;; =============================================================================
;; Static Assets Handler
;; =============================================================================

(defn static-assets-handler
  "Handler for serving static assets (GET /assets/*).
   
   This is a basic implementation - in production you'd typically use
   a more sophisticated asset server or CDN.
   
   Returns:
     Ring handler function"
  []
  (fn [request]
    (let [asset-path (get-in request [:path-params :*])]
      ;; Basic implementation - just serve common HTMX and CSS files
      (case asset-path
        "htmx.min.js" (response/resource-response "public/js/htmx.min.js")
        "site.css" (response/resource-response "public/css/site.css")
        (response/not-found "Asset not found")))))