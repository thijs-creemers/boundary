(ns ecommerce.payment.core.payment
  "Pure business logic for payments.
   
   Handles payment intent creation, webhook event parsing,
   and payment status transitions.")

;; =============================================================================
;; Payment Intent Creation
;; =============================================================================

(defn create-payment-intent-data
  "Create payment intent data structure.
   
   Args:
     order - Order to create payment for
     now   - Current timestamp
   
   Returns:
     Payment intent creation params"
  [order]
  {:amount (:total-cents order)
   :currency (:currency order)
   :metadata {:order-id (str (:id order))
              :order-number (:order-number order)
              :customer-email (:customer-email order)}})

;; =============================================================================
;; Webhook Event Processing
;; =============================================================================

(def handled-event-types
  "Webhook event types we process."
  #{"payment_intent.succeeded"
    "payment_intent.payment_failed"
    "payment_intent.canceled"})

(defn should-process-event?
  "Check if event type should be processed."
  [event-type]
  (contains? handled-event-types event-type))

(defn extract-payment-intent-id
  "Extract payment intent ID from webhook event."
  [event]
  (get-in event [:data :object :id]))

(defn extract-order-id
  "Extract order ID from webhook event metadata."
  [event]
  (some-> (get-in event [:data :object :metadata :order-id])
          parse-uuid))

(defn determine-order-action
  "Determine what action to take on order based on event type.
   
   Returns:
     :mark-paid    - Payment succeeded
     :mark-failed  - Payment failed
     :cancel       - Payment canceled
     nil           - No action needed"
  [event-type]
  (case event-type
    "payment_intent.succeeded" :mark-paid
    "payment_intent.payment_failed" :mark-failed
    "payment_intent.canceled" :cancel
    nil))

;; =============================================================================
;; Payment Status
;; =============================================================================

(defn payment-status->api
  "Transform payment status for API response."
  [intent order]
  {:payment-intent-id (:id intent)
   :status (name (:status intent))
   :amount (:amount intent)
   :currency (:currency intent)
   :order-id (:id order)
   :order-status (name (:status order))})

;; =============================================================================
;; Webhook Signature Verification
;; =============================================================================

(defn compute-signature
  "Compute HMAC-SHA256 signature for webhook payload.
   Used by mock provider and for verification."
  [payload secret]
  (let [mac (javax.crypto.Mac/getInstance "HmacSHA256")
        secret-key (javax.crypto.spec.SecretKeySpec. 
                    (.getBytes secret "UTF-8") "HmacSHA256")]
    (.init mac secret-key)
    (let [hash (.doFinal mac (.getBytes payload "UTF-8"))]
      (apply str (map #(format "%02x" %) hash)))))

(defn verify-signature
  "Verify webhook signature matches expected.
   
   Stripe signature format: t=timestamp,v1=signature"
  [payload signature secret]
  (when (and payload signature secret)
    (try
      (let [;; Parse Stripe-style signature
            parts (into {} (map #(let [[k v] (clojure.string/split % #"=")]
                                   [(keyword k) v])
                                (clojure.string/split signature #",")))
            timestamp (:t parts)
            provided-sig (:v1 parts)
            ;; Compute expected signature
            signed-payload (str timestamp "." payload)
            expected-sig (compute-signature signed-payload secret)]
        (= provided-sig expected-sig))
      (catch Exception _
        false))))
