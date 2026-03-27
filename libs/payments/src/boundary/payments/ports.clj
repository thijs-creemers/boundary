(ns boundary.payments.ports
  "Payment provider abstraction — enables swappable PSP adapters (Mock, Mollie, Stripe).")

(defprotocol IPaymentProvider
  (create-checkout-session [this {:keys [amount-cents currency description
                                         redirect-url webhook-url metadata]}])
  ;; Returns {:checkout-url "..." :provider-checkout-id "..."}

  (get-payment-status [this provider-checkout-id])
  ;; Returns {:status :pending|:paid|:failed|:cancelled
  ;;          :provider-payment-id "..."}

  (process-webhook [this raw-body headers])
  ;; Returns {:event-type :payment.paid|:payment.failed|:payment.cancelled
  ;;          :provider-payment-id "..."
  ;;          :provider-checkout-id "..."
  ;;          :payload {...}}

  (verify-webhook-signature [this raw-body headers]))
  ;; Returns boolean
