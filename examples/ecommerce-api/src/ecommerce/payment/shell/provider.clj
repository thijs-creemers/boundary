(ns ecommerce.payment.shell.provider
  "Mock payment provider (Stripe-like).
   
   This simulates Stripe's Payment Intents API for demonstration.
   In production, replace with real Stripe SDK integration."
  (:require [ecommerce.payment.ports :as ports]
            [ecommerce.payment.core.payment :as payment-core])
  (:import [java.time Instant]))

;; =============================================================================
;; In-Memory Storage (for demo)
;; =============================================================================

(defonce ^:private intents (atom {}))

;; =============================================================================
;; ID Generation
;; =============================================================================

(defn- generate-intent-id []
  (str "pi_" (random-uuid)))

(defn- generate-client-secret [intent-id]
  (str intent-id "_secret_" (random-uuid)))

;; =============================================================================
;; Mock Provider Implementation
;; =============================================================================

(defrecord MockPaymentProvider [config]
  ports/IPaymentProvider
  
  (create-intent [_ amount currency metadata]
    (let [intent-id (generate-intent-id)
          intent {:id intent-id
                  :amount amount
                  :currency currency
                  :status :requires-payment-method
                  :client-secret (generate-client-secret intent-id)
                  :metadata metadata
                  :created-at (Instant/now)}]
      ;; Store in memory
      (swap! intents assoc intent-id intent)
      intent))
  
  (retrieve-intent [_ intent-id]
    (get @intents intent-id))
  
  (confirm-intent [_ intent-id]
    ;; Simulate payment confirmation
    ;; In real implementation, this would call Stripe API
    (when-let [intent (get @intents intent-id)]
      (let [;; Simulate: 95% success rate, 5% failure for testing
            success? (< (rand) 0.95)
            updated (assoc intent :status (if success? :succeeded :failed))]
        (swap! intents assoc intent-id updated)
        updated)))
  
  (cancel-intent [_ intent-id]
    (when-let [intent (get @intents intent-id)]
      (let [updated (assoc intent :status :canceled)]
        (swap! intents assoc intent-id updated)
        updated)))
  
  (verify-webhook-signature [_ payload signature]
    (let [secret (:webhook-secret config)]
      (payment-core/verify-signature payload signature secret))))

;; =============================================================================
;; Helper for Testing Webhooks
;; =============================================================================

(defn create-test-webhook-signature
  "Create a valid webhook signature for testing.
   
   Args:
     payload - JSON string payload
     secret  - Webhook secret
   
   Returns:
     Stripe-format signature string"
  [payload secret]
  (let [timestamp (str (quot (System/currentTimeMillis) 1000))
        signed-payload (str timestamp "." payload)
        signature (payment-core/compute-signature signed-payload secret)]
    (str "t=" timestamp ",v1=" signature)))

(defn simulate-payment-success
  "Simulate a successful payment for testing.
   Returns webhook event data."
  [intent-id order-id]
  {:id (str "evt_" (random-uuid))
   :type "payment_intent.succeeded"
   :data {:object {:id intent-id
                   :status "succeeded"
                   :metadata {:order-id (str order-id)}}}})

(defn simulate-payment-failure
  "Simulate a failed payment for testing."
  [intent-id order-id]
  {:id (str "evt_" (random-uuid))
   :type "payment_intent.payment_failed"
   :data {:object {:id intent-id
                   :status "failed"
                   :metadata {:order-id (str order-id)}}}})
