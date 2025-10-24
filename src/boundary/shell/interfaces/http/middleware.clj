(ns boundary.shell.interfaces.http.middleware
  "HTTP middleware for web applications.

   This namespace provides reusable HTTP middleware that can be used across
   different modules and applications. It follows RFC standards and best
   practices for web API development.

   Features:
   - Request correlation ID management
   - Structured request/response logging
   - RFC 7807 Problem Details for error responses
   - Generic exception handling middleware"
  (:require [clojure.tools.logging :as log])
  (:import (java.util UUID)))

;; =============================================================================
;; Middleware - Correlation ID
;; =============================================================================

(defn wrap-correlation-id
  "Middleware to add correlation ID to requests and responses.

   Adds a unique correlation ID to each request for tracing purposes.
   Uses X-Request-Id header if present, otherwise generates a new UUID.

   Args:
     handler: Ring handler function

   Returns:
     Ring middleware function"
  [handler]
  (fn [request]
    (let [correlation-id (or (get-in request [:headers "x-request-id"])
                             (str (UUID/randomUUID)))
          request-with-id (assoc request :correlation-id correlation-id)
          response (handler request-with-id)]
      (if response
        (assoc-in response [:headers "X-Request-Id"] correlation-id)
        response))))

;; =============================================================================
;; Middleware - Request Logging
;; =============================================================================

(defn wrap-request-logging
  "Middleware for structured request/response logging.

   Logs HTTP requests and responses with timing information and correlation IDs
   for observability and debugging purposes.

   Args:
     handler: Ring handler function

   Returns:
     Ring middleware function"
  [handler]
  (fn [request]
    (let [start-time (System/currentTimeMillis)
          {:keys [request-method uri correlation-id]} request]
      (log/info "HTTP request started"
                {:method request-method
                 :uri uri
                 :correlation-id correlation-id})
      (let [response (handler request)
            duration (- (System/currentTimeMillis) start-time)]
        (log/info "HTTP request completed"
                  {:method request-method
                   :uri uri
                   :status (:status response)
                   :duration-ms duration
                   :correlation-id correlation-id})
        response))))

;; =============================================================================
;; Middleware - Exception Handling
;; =============================================================================

(defn wrap-exception-handling
  "Middleware to catch exceptions and return RFC 7807 problem details.

   Catches all exceptions and converts them to standardized RFC 7807 problem
   details responses. Logs exceptions for debugging and monitoring.

   Args:
     handler: Ring handler function
     error-mappings: Optional custom error type mappings

   Returns:
     Ring middleware function"
  ([handler]
   (wrap-exception-handling handler {}))
  ([handler error-mappings]
   (fn [request]
     (try
       (handler request)
       (catch Exception ex
         (log/error "Request failed with exception"
                    {:uri (:uri request)
                     :method (:request-method request)
                     :correlation-id (:correlation-id request)
                     :error (.getMessage ex)
                     :ex-data (ex-data ex)})
         (let [common (requiring-resolve 'boundary.shell.interfaces.http.common)]
           ((resolve 'boundary.shell.interfaces.http.common/exception->problem)
            ex (:correlation-id request) (:uri request) error-mappings)))))))

