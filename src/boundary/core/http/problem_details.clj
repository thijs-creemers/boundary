(ns boundary.core.http.problem-details
  "Pure functions for RFC 7807 Problem Details transformations.
   
   All functions are pure data transformations from exceptions to
   standardized error response structures."
  (:require [boundary.shared.core.config.feature-flags :as flags]
            [boundary.shared.core.validation.context :as val-context]))

;; =============================================================================
;; Error Type Mappings
;; =============================================================================

(def default-error-mappings
  "Default mapping of exception types to HTTP status codes and titles.
   
   Pure data structure: no side effects."
  {:validation-error [400 "Validation Error"]
   :invalid-request [400 "Invalid Request"]
   :unauthorized [401 "Unauthorized"]
   :auth-failed [401 "Authentication Failed"]
   :forbidden [403 "Forbidden"]
   :not-found [404 "Not Found"]
   :user-not-found [404 "User Not Found"]
   :resource-not-found [404 "Resource Not Found"]
   :conflict [409 "Conflict"]
   :user-exists [409 "User Already Exists"]
   :resource-exists [409 "Resource Already Exists"]
   :business-rule-violation [400 "Business Rule Violation"]
   :deletion-not-allowed [403 "Deletion Not Allowed"]
   :hard-deletion-not-allowed [403 "Hard Deletion Not Allowed"]})

;; =============================================================================
;; Problem Details Construction
;; =============================================================================

(defn exception->problem-body
  "Transform exception to RFC 7807 problem details body.
   
   Pure function: extracts exception data and builds response structure.
   
   Args:
     ex: Exception with ex-data containing :type, :errors, etc.
     correlation-id: Request correlation ID string
     uri: Request URI string
     error-mappings: Map of error types to [status title] pairs
     
   Returns:
     Map with RFC 7807 problem details structure including extension members
     from ex-data (excluding reserved/internal keys)"
  [ex correlation-id uri error-mappings]
  (let [ex-data (ex-data ex)
        ex-type (:type ex-data)
        mappings (merge default-error-mappings error-mappings)
        [status title] (get mappings ex-type [500 "Internal Server Error"])
        ;; Reserved RFC 7807 fields that shouldn't be duplicated
        reserved-keys #{:type :title :status :detail :instance}
        ;; Internal keys that shouldn't appear in response
        internal-keys #{:errors}
        ;; Extract extension members (non-reserved, non-internal ex-data keys)
        extension-members (apply dissoc ex-data (concat reserved-keys internal-keys))]
    (merge
     {:type (str "https://api.example.com/problems/"
                 (name (or ex-type :internal-error)))
      :title title
      :status status
      :detail (.getMessage ex)
      :instance uri
      :correlationId correlation-id
      :errors (or (:errors ex-data) {})}
      ;; Merge extension members at top level per RFC 7807
     extension-members)))

(defn problem-details->response
  "Convert problem details body to Ring response map.
   
   Pure function: wraps problem details in HTTP response structure.
   
   Args:
     problem-body: RFC 7807 problem details map
     
   Returns:
     Ring response map with appropriate headers"
  [problem-body]
  {:status (:status problem-body)
   :headers {"Content-Type" "application/problem+json"}
   :body problem-body})

(defn exception->problem-response
  "Transform exception to complete RFC 7807 problem response.
   
   Pure function: convenience wrapper combining body and response creation.
   
   Args:
     ex: Exception to transform
     correlation-id: Request correlation ID
     uri: Request URI
     error-mappings: Optional custom error mappings
     
   Returns:
     Ring response map with RFC 7807 problem details"
  ([ex correlation-id uri]
   (exception->problem-response ex correlation-id uri {}))
  ([ex correlation-id uri error-mappings]
   (-> (exception->problem-body ex correlation-id uri error-mappings)
       (problem-details->response))))
