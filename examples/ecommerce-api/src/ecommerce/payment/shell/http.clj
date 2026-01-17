(ns ecommerce.payment.shell.http
  "HTTP handlers for payment API including webhooks."
  (:require [ecommerce.payment.ports :as ports]
            [ecommerce.shared.http.responses :as resp]
            [cheshire.core :as json])
  (:import [java.util UUID]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- str->uuid [s]
  (try (UUID/fromString s)
       (catch Exception _ nil)))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn create-payment-intent-handler
  "POST /api/payments/intents - Create payment intent for order."
  [payment-service]
  (fn [request]
    (let [body (:json-body request)
          order-id (some-> (:order-id body) str str->uuid)]
      (if order-id
        (let [result (ports/create-payment-intent payment-service order-id)]
          (resp/handle-result result
                              :resource-type "Order"
                              :on-success resp/created))
        (resp/bad-request "order-id is required")))))

(defn get-payment-status-handler
  "GET /api/payments/orders/:order-id - Get payment status for order."
  [payment-service]
  (fn [request]
    (let [order-id (str->uuid (get-in request [:path-params :order-id]))]
      (if order-id
        (let [result (ports/get-payment-status payment-service order-id)]
          (resp/handle-result result :resource-type "Payment"))
        (resp/bad-request "Invalid order ID")))))

(defn webhook-handler
  "POST /api/webhooks/payment - Handle payment provider webhooks.
   
   Verifies signature before processing event."
  [payment-service payment-config]
  (fn [request]
    (let [;; Get raw body for signature verification
          raw-body (if (string? (:body request))
                     (:body request)
                     (slurp (:body request)))
          signature (get-in request [:headers "stripe-signature"])
          provider (-> payment-service :provider)]
      ;; Verify signature
      (if (ports/verify-webhook-signature provider raw-body signature)
        ;; Parse and process event
        (let [event (json/parse-string raw-body true)
              result (ports/handle-webhook payment-service event)]
          (if (:ok result)
            (resp/ok (:ok result))
            (resp/handle-result result :resource-type "Webhook")))
        ;; Invalid signature
        (do
          (println "Webhook signature verification failed")
          (resp/json-response {:error {:code "invalid_signature"
                                       :message "Invalid webhook signature"}}
                              401))))))

;; =============================================================================
;; Test Endpoint (Development Only)
;; =============================================================================

(defn simulate-payment-handler
  "POST /api/payments/simulate - Simulate payment completion (dev only).
   
   This endpoint allows testing the payment flow without real Stripe."
  [payment-service]
  (fn [request]
    (let [body (:json-body request)
          order-id (some-> (:order-id body) str str->uuid)
          success? (get body :success true)]
      (if order-id
        ;; Create mock webhook event
        (let [event {:id (str "evt_test_" (random-uuid))
                     :type (if success?
                             "payment_intent.succeeded"
                             "payment_intent.payment_failed")
                     :data {:object {:id (str "pi_test_" (random-uuid))
                                     :status (if success? "succeeded" "failed")
                                     :metadata {:order-id (str order-id)}}}}
              result (ports/handle-webhook payment-service event)]
          (resp/handle-result result :resource-type "Payment"))
        (resp/bad-request "order-id is required")))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  "Payment API routes."
  [payment-service payment-config]
  [["/api/payments/intents"
    {:post {:handler (create-payment-intent-handler payment-service)
            :summary "Create payment intent"}}]
   ["/api/payments/orders/:order-id"
    {:get {:handler (get-payment-status-handler payment-service)
           :summary "Get payment status for order"}}]
   ["/api/webhooks/payment"
    {:post {:handler (webhook-handler payment-service payment-config)
            :summary "Handle payment webhooks"}}]
   ;; Development endpoint
   ["/api/payments/simulate"
    {:post {:handler (simulate-payment-handler payment-service)
            :summary "Simulate payment (dev only)"}}]])
