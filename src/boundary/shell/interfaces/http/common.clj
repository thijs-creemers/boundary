(ns boundary.shell.interfaces.http.common
  "Common HTTP utilities and RFC 7807 Problem Details support.

   This namespace provides common HTTP functionality including standardized
   error responses following RFC 7807 Problem Details specification and
   other utility functions used across HTTP interfaces."
  (:require [clojure.string :as str]))

;; =============================================================================
;; RFC 7807 Problem Details
;; =============================================================================

(def ^:private default-error-mappings
  "Default mapping of exception types to HTTP status codes and titles."
  {:validation-error          [400 "Validation Error"]
   :invalid-request           [400 "Invalid Request"]
   :unauthorized              [401 "Unauthorized"]
   :auth-failed               [401 "Authentication Failed"]
   :forbidden                 [403 "Forbidden"]
   :not-found                 [404 "Not Found"]
   :user-not-found            [404 "User Not Found"]
   :resource-not-found        [404 "Resource Not Found"]
   :conflict                  [409 "Conflict"]
   :user-exists               [409 "User Already Exists"]
   :resource-exists           [409 "Resource Already Exists"]
   :business-rule-violation   [400 "Business Rule Violation"]
   :deletion-not-allowed      [403 "Deletion Not Allowed"]
   :hard-deletion-not-allowed [403 "Hard Deletion Not Allowed"]})

(defn exception->problem
      "Convert exception to RFC 7807 problem details.

       Creates standardized error responses following RFC 7807 Problem Details
       for HTTP APIs specification.

       Args:
         ex: Exception to convert
         correlation-id: Request correlation ID
         uri: Request URI
         error-mappings: Optional custom error type mappings (overrides defaults)

       Returns:
         Ring response map with RFC 7807 problem details"
  ([ex correlation-id uri]
   (exception->problem ex correlation-id uri {}))
  ([ex correlation-id uri error-mappings]
   (let [ex-data  (ex-data ex)
         ex-type  (:type ex-data)
         mappings (merge default-error-mappings error-mappings)
         [status title] (get mappings ex-type [500 "Internal Server Error"])]
     {:status  status
      :headers {"Content-Type" "application/problem+json"}
      :body    {:type          (str "https://api.example.com/problems/" (name (or ex-type :internal-error)))
                :title         title
                :status        status
                :detail        (.getMessage ex)
                :instance      uri
                :correlationId correlation-id
                :errors        (or (:errors ex-data) {})}})))

;; =============================================================================
;; Standard HTTP Response Handlers
;; =============================================================================

(defn create-not-found-handler
      "Create a standardized 404 Not Found handler.

       Returns:
         Ring handler function for 404 responses"
  []
  (fn [_]
    {:status  404
     :headers {"Content-Type" "application/problem+json"}
     :body    {:type   "https://api.example.com/problems/not-found"
               :title  "Not Found"
               :status 404
               :detail "The requested resource was not found"}}))

(defn create-method-not-allowed-handler
      "Create a standardized 405 Method Not Allowed handler.

       Args:
         allowed-methods: Collection of allowed HTTP methods

       Returns:
         Ring handler function for 405 responses"
  [allowed-methods]
  (fn [_]
    {:status  405
     :headers {"Content-Type" "application/problem+json"
               "Allow"        (str/join ", " (map name allowed-methods))}
     :body    {:type   "https://api.example.com/problems/method-not-allowed"
               :title  "Method Not Allowed"
               :status 405
               :detail (str "Allowed methods: " (str/join ", " (map name allowed-methods)))}}))

(defn health-check-handler
      "Create a generic health check handler.

       Args:
         service-name: Name of the service
         version: Service version (optional)
         additional-checks: Optional function that returns additional health data

       Returns:
         Ring handler function for health checks"
  ([service-name]
   (health-check-handler service-name nil nil))
  ([service-name version]
   (health-check-handler service-name version nil))
  ([service-name version additional-checks]
   (fn [_request]
     (let [base-health {:status    "ok"
                        :service   service-name
                        :version   (or version "unknown")
                        :timestamp (str (java.time.Instant/now))}
           additional  (when additional-checks (additional-checks))]
       {:status 200
        :body   (merge base-health additional)}))))

