(ns boundary.payments.schema
  "Malli schemas for the payment provider abstraction layer.")

(def CheckoutRequest
  [:map
   [:amount-cents  pos-int?]
   [:currency      [:string {:min 3 :max 3}]]
   [:description   :string]
   [:redirect-url  :string]
   [:webhook-url   {:optional true} [:maybe :string]]
   [:metadata      {:optional true} [:maybe :map]]])

(def CheckoutResult
  [:map
   [:checkout-url        :string]
   [:provider-checkout-id :string]])

(def PaymentStatusResult
  [:map
   [:status              [:enum :pending :paid :failed :cancelled]]
   [:provider-payment-id {:optional true} [:maybe :string]]])

(def WebhookResult
  [:map
   [:event-type           [:enum :payment.paid :payment.failed :payment.cancelled :payment.authorized]]
   [:provider-payment-id  {:optional true} [:maybe :string]]
   [:provider-checkout-id {:optional true} [:maybe :string]]
   [:payload              :map]])
