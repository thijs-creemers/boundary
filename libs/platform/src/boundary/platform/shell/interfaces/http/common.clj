(ns boundary.platform.shell.interfaces.http.common
  "Common HTTP utilities and RFC 7807 Problem Details support.

   This namespace provides common HTTP functionality including standardized
   error responses following RFC 7807 Problem Details specification and
   other utility functions used across HTTP interfaces.
   
   Pure problem details transformations are now in boundary.platform.core.http.problem-details"
  (:require [boundary.platform.core.http.problem-details :as core-problem]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; =============================================================================
;; RFC 7807 Problem Details (Re-exported from core)
;; =============================================================================

(def default-error-mappings
  "Default mapping of exception types to HTTP status codes and titles.
   
   Re-exported from core for backward compatibility."
  core-problem/default-error-mappings)

(defn exception->problem
  "Convert exception to RFC 7807 problem details.
   
   Delegates to pure core function.

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
   (core-problem/exception->problem-response ex correlation-id uri error-mappings)))

;; =============================================================================
;; Standard HTTP Response Handlers
;; =============================================================================

(defn create-not-found-handler
  "Create a standardized 404 Not Found handler.

   Returns:
     Ring handler function for 404 responses"
  []
  (fn [_]
    {:status 404
     :headers {"Content-Type" "application/problem+json"}
     :body (json/generate-string
            {:type "https://api.example.com/problems/not-found"
             :title "Not Found"
             :status 404
             :detail "The requested resource was not found"})}))

(defn create-method-not-allowed-handler
  "Create a standardized 405 Method Not Allowed handler.

   Args:
     allowed-methods: Collection of allowed HTTP methods

   Returns:
     Ring handler function for 405 responses"
  [allowed-methods]
  (fn [_]
    {:status 405
     :headers {"Content-Type" "application/problem+json"
               "Allow" (str/join ", " (map name allowed-methods))}
     :body (json/generate-string
            {:type "https://api.example.com/problems/method-not-allowed"
             :title "Method Not Allowed"
             :status 405
             :detail (str "Allowed methods: " (str/join ", " (map name allowed-methods)))})}))

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
     (let [base-health {:status "ok"
                        :service service-name
                        :version (or version "unknown")
                        :timestamp (str (java.time.Instant/now))}
           additional (when additional-checks (additional-checks))]
       {:status 200
        :headers {"Content-Type" "application/json"}
        :body (json/generate-string (merge base-health additional))}))))

