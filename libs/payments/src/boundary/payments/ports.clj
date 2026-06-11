(ns boundary.payments.ports
  "Payment provider abstraction — enables swappable PSP adapters (Mock, Mollie, Stripe).")

(defprotocol IPaymentProvider
  (provider-name [this])
  ;; Returns a keyword identifying the provider: :mock, :mollie, :stripe

  (create-checkout-session [this {:keys [amount-cents currency description
                                         redirect-url success-url cancel-url
                                         webhook-url metadata
                                         setup-future-usage customer-email
                                         provider-customer-id]}])
  ;; Hosted checkout for the first payment. Pass :setup-future-usage :off-session
  ;; to store a mandate/payment-method for later off-session charges
  ;; (Stripe: setup_future_usage=off_session; Mollie: sequenceType=first).
  ;; :success-url/:cancel-url override :redirect-url when given (Stripe).
  ;; Returns {:checkout-url "..." :provider-checkout-id "..."
  ;;          :provider-payment-id "..."}   ; optional, when known at creation

  (create-off-session-payment [this {:keys [amount-cents currency description
                                            provider-customer-id
                                            provider-payment-method-id
                                            metadata]}])
  ;; Charge a stored customer + payment method without user interaction
  ;; (recurring billing). :provider-payment-method-id is optional — providers
  ;; may fall back to the customer's stored default mandate.
  ;; Returns {:provider-payment-id "..." :status :pending|:paid|:failed}

  (get-payment-status [this provider-checkout-id])
  ;; Stripe accepts both Checkout Session ids (cs_...) and PaymentIntent ids
  ;; (pi_...), dispatched by prefix; Mollie takes a payment id.
  ;; Returns {:status :pending|:paid|:failed|:cancelled|:expired|:chargeback
  ;;          :provider-payment-id "..."
  ;;          :provider-customer-id "..."          ; optional, mandate follow-up
  ;;          :provider-payment-method-id "..."}   ; optional, mandate follow-up

  (expire-checkout-session [this provider-checkout-id])
  ;; Expire an abandoned checkout session so it can no longer be completed.
  ;; Returns {:provider-checkout-id "..." :status :expired}

  (process-webhook [this raw-body headers])
  ;; Returns {:event-type :payment.paid|:payment.failed|:payment.cancelled
  ;;                       |:payment.expired|:payment.authorized
  ;;          :provider-payment-id "..."
  ;;          :provider-checkout-id "..."
  ;;          :payload {...}}

  (verify-webhook-signature [this raw-body headers]))
  ;; Provider-agnostic signature check over the RAW body string + headers
  ;; (Stripe: HMAC via Stripe-Signature header; Mollie: no HMAC, always true).
  ;; Returns boolean
