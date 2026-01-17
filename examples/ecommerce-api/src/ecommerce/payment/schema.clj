(ns ecommerce.payment.schema
  "Malli schemas for payment module."
  (:require [malli.core :as m]))

;; =============================================================================
;; Payment Intent Status (Stripe-like)
;; =============================================================================

(def PaymentIntentStatus
  "Payment intent statuses (following Stripe conventions)."
  [:enum 
   :requires-payment-method
   :requires-confirmation
   :requires-action
   :processing
   :succeeded
   :canceled])

;; =============================================================================
;; Payment Entities
;; =============================================================================

(def PaymentIntent
  "Payment intent entity (Stripe-like)."
  [:map
   [:id :string]  ;; e.g., "pi_abc123"
   [:amount pos-int?]
   [:currency [:enum "EUR" "USD" "GBP"]]
   [:status PaymentIntentStatus]
   [:client-secret :string]
   [:metadata {:optional true} [:map-of :keyword :any]]
   [:created-at inst?]])

;; =============================================================================
;; API Schemas
;; =============================================================================

(def CreatePaymentIntentRequest
  "Request to create a payment intent."
  [:map
   [:order-id uuid?]])

(def WebhookEvent
  "Stripe-like webhook event."
  [:map
   [:id :string]
   [:type :string]
   [:data [:map
           [:object [:map-of :keyword :any]]]]])

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate
  "Validate data against schema."
  [schema data]
  (if (m/validate schema data)
    {:ok data}
    {:error :validation
     :details (m/explain schema data)}))
