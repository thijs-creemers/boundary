(ns boundary.payments.schema
  "Malli schemas for the payment provider abstraction layer.")

(def PaymentStatus
  "Normalized payment status across providers. Aligned with the consumer-side
   PaymentStatus enum in boundary-license (:pending :paid :failed :expired
   :chargeback) plus :cancelled for user/PSP-cancelled payments."
  [:enum :pending :paid :failed :cancelled :expired :chargeback])

(def OffSessionPaymentStatus
  "Statuses an off-session charge can report at creation time. Subset of
   PaymentStatus — a just-created charge cannot be cancelled, expired or
   charged back."
  [:enum :pending :paid :failed])

(def CheckoutRequest
  [:map
   [:amount-cents  pos-int?]
   [:currency      [:string {:min 3 :max 3}]]
   [:description   :string]
   [:redirect-url  :string]
   [:webhook-url   {:optional true} [:maybe :string]]
   [:metadata      {:optional true} [:maybe :map]]
   ;; Mandate options — request storage of the payment method for later
   ;; off-session charges (Stripe: setup_future_usage; Mollie: sequenceType).
   [:setup-future-usage   {:optional true} [:maybe [:enum :off-session :on-session]]]
   [:customer-email       {:optional true} [:maybe [:re #".+@.+"]]]
   [:provider-customer-id {:optional true} [:maybe :string]]])

(def CheckoutResult
  [:map
   [:checkout-url        :string]
   [:provider-checkout-id :string]
   ;; Some providers expose the underlying payment id at session creation.
   [:provider-payment-id {:optional true} [:maybe :string]]])

(def OffSessionPaymentRequest
  [:map
   [:amount-cents          pos-int?]
   [:currency              [:string {:min 3 :max 3}]]
   [:description           :string]
   [:provider-customer-id  :string]
   ;; Optional — providers may use the customer's stored default mandate.
   [:provider-payment-method-id {:optional true} [:maybe :string]]
   [:metadata              {:optional true} [:maybe :map]]])

(def OffSessionPaymentResult
  [:map
   [:provider-payment-id :string]
   [:status              OffSessionPaymentStatus]])

(def PaymentStatusResult
  [:map
   [:status              PaymentStatus]
   [:provider-payment-id {:optional true} [:maybe :string]]
   ;; Mandate details, when the provider exposes them — lets consumers store
   ;; the customer + payment method after the first checkout completes.
   [:provider-customer-id       {:optional true} [:maybe :string]]
   [:provider-payment-method-id {:optional true} [:maybe :string]]])

(def ExpireCheckoutResult
  [:map
   [:provider-checkout-id :string]
   [:status               [:enum :expired]]])

(def WebhookResult
  [:map
   [:event-type           [:enum :payment.paid :payment.failed :payment.cancelled :payment.authorized]]
   [:provider-payment-id  {:optional true} [:maybe :string]]
   [:provider-checkout-id {:optional true} [:maybe :string]]
   [:payload              :map]])
