(ns ecommerce.shared.http.middleware
  "HTTP middleware for the e-commerce API."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ring.util.response :as response]))

;; =============================================================================
;; JSON Body Parsing
;; =============================================================================

(defn wrap-json-body
  "Parse JSON request body into :json-body key."
  [handler]
  (fn [request]
    (if-let [body (:body request)]
      (try
        (let [body-str (if (string? body)
                         body
                         (slurp body))
              parsed (when-not (str/blank? body-str)
                       (json/parse-string body-str true))]
          (handler (assoc request :json-body parsed)))
        (catch Exception _
          (-> (response/response 
               (json/generate-string 
                {:error {:code "invalid_json"
                         :message "Invalid JSON in request body"}}))
              (response/status 400)
              (response/content-type "application/json"))))
      (handler request))))

;; =============================================================================
;; Session ID (for cart)
;; =============================================================================

(defn wrap-session-id
  "Ensure request has a session ID for cart management.
   Uses X-Session-ID header or generates one."
  [handler]
  (fn [request]
    (let [session-id (or (get-in request [:headers "x-session-id"])
                         (str (random-uuid)))
          response (handler (assoc request :session-id session-id))]
      (-> response
          (assoc-in [:headers "X-Session-ID"] session-id)))))

;; =============================================================================
;; Error Handling
;; =============================================================================

(defn wrap-exception-handler
  "Catch unhandled exceptions and return JSON error response."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (println "Unhandled exception:" (.getMessage e))
        (.printStackTrace e)
        (-> (response/response
             (json/generate-string
              {:error {:code "internal_error"
                       :message "An unexpected error occurred"}}))
            (response/status 500)
            (response/content-type "application/json"))))))

;; =============================================================================
;; CORS (for development)
;; =============================================================================

(defn wrap-cors
  "Add CORS headers for development."
  [handler]
  (fn [request]
    (if (= :options (:request-method request))
      (-> (response/response nil)
          (response/status 204)
          (response/header "Access-Control-Allow-Origin" "*")
          (response/header "Access-Control-Allow-Methods" "GET, POST, PUT, PATCH, DELETE, OPTIONS")
          (response/header "Access-Control-Allow-Headers" "Content-Type, X-Session-ID"))
      (-> (handler request)
          (response/header "Access-Control-Allow-Origin" "*")))))

;; =============================================================================
;; Request Logging
;; =============================================================================

(defn wrap-request-logging
  "Log incoming requests."
  [handler]
  (fn [request]
    (let [start (System/currentTimeMillis)
          response (handler request)
          duration (- (System/currentTimeMillis) start)]
      (println (format "%s %s - %d (%dms)"
                       (-> request :request-method name str/upper-case)
                       (:uri request)
                       (:status response)
                       duration))
      response)))
