(ns ecommerce.shared.http.responses
  "Standard JSON API response helpers.
   
   All responses follow a consistent format:
   - Success: {:data ...} or {:data [...] :meta {...}}
   - Error: {:error {:code ... :message ...}}"
  (:require [cheshire.core :as json]
            [ring.util.response :as response]))

;; =============================================================================
;; Response Builders
;; =============================================================================

(defn json-response
  "Create a JSON response with the given body and status."
  ([body]
   (json-response body 200))
  ([body status]
   (-> (response/response (json/generate-string body))
       (response/status status)
       (response/content-type "application/json"))))

(defn ok
  "200 OK - Success with data."
  [data]
  (json-response {:data data} 200))

(defn ok-list
  "200 OK - Success with list and metadata."
  [items meta]
  (json-response {:data items :meta meta} 200))

(defn created
  "201 Created - Resource created."
  [data]
  (json-response {:data data} 201))

(defn no-content
  "204 No Content - Success with no body."
  []
  (-> (response/response nil)
      (response/status 204)))

(defn bad-request
  "400 Bad Request - Validation or input error."
  ([message]
   (bad-request message nil))
  ([message details]
   (json-response {:error {:code "bad_request"
                           :message message
                           :details details}}
                  400)))

(defn unauthorized
  "401 Unauthorized - Authentication required."
  []
  (json-response {:error {:code "unauthorized"
                          :message "Authentication required"}}
                 401))

(defn forbidden
  "403 Forbidden - Access denied."
  []
  (json-response {:error {:code "forbidden"
                          :message "Access denied"}}
                 403))

(defn not-found
  "404 Not Found - Resource not found."
  ([resource-type]
   (not-found resource-type nil))
  ([resource-type id]
   (json-response {:error {:code "not_found"
                           :message (str resource-type " not found")
                           :resource_type resource-type
                           :resource_id id}}
                  404)))

(defn conflict
  "409 Conflict - Resource conflict (e.g., duplicate)."
  [message]
  (json-response {:error {:code "conflict"
                          :message message}}
                 409))

(defn unprocessable
  "422 Unprocessable Entity - Business logic error."
  [message details]
  (json-response {:error {:code "unprocessable_entity"
                          :message message
                          :details details}}
                 422))

(defn internal-error
  "500 Internal Server Error."
  []
  (json-response {:error {:code "internal_error"
                          :message "An unexpected error occurred"}}
                 500))

;; =============================================================================
;; Result Handling
;; =============================================================================

(defn handle-result
  "Convert a service result {:ok ...} or {:error ...} to an HTTP response."
  [result & {:keys [resource-type on-success]
             :or {resource-type "Resource"
                  on-success ok}}]
  (cond
    (:ok result)
    (on-success (:ok result))
    
    (= :not-found (:error result))
    (not-found resource-type (:id result))
    
    (= :validation (:error result))
    (bad-request "Validation failed" (:details result))
    
    (= :conflict (:error result))
    (conflict (:message result))
    
    (= :insufficient-stock (:error result))
    (unprocessable "Insufficient stock" {:product-id (:product-id result)
                                          :available (:available result)
                                          :requested (:requested result)})
    
    (= :invalid-transition (:error result))
    (unprocessable "Invalid status transition" {:from (:from result)
                                                 :to (:to result)})
    
    :else
    (internal-error)))
