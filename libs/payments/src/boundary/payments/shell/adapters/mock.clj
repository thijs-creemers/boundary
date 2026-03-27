(ns boundary.payments.shell.adapters.mock
  "Mock payment provider — auto-approves all payments. For development and tests."
  (:require [boundary.payments.ports :as ports]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(defrecord MockPaymentProvider []
  ports/IPaymentProvider

  (create-checkout-session [_ {:keys [amount-cents currency description metadata]}]
    (let [checkout-id (str (UUID/randomUUID))
          payment-id  (:payment-id metadata)]
      (log/infof "Mock PSP: created checkout session %s for %d %s — %s"
                 checkout-id amount-cents currency description)
      {:checkout-url         (cond-> (str "/web/payment/mock-return?checkout-id=" checkout-id)
                               payment-id (str "&payment-id=" payment-id))
       :provider-checkout-id checkout-id}))

  (get-payment-status [_ provider-checkout-id]
    (log/infof "Mock PSP: get-payment-status for %s → :paid" provider-checkout-id)
    {:status              :paid
     :provider-payment-id (str "mock-payment-" provider-checkout-id)})

  (verify-webhook-signature [_ _raw-body _headers]
    true)

  (process-webhook [_ raw-body _headers]
    (let [body (if (string? raw-body)
                 (try (json/parse-string raw-body true)
                      (catch Exception _ {}))
                 (or raw-body {}))]
      (log/infof "Mock PSP: processing webhook %s" body)
      {:event-type            :payment.paid
       :provider-payment-id   (str "mock-payment-" (or (:checkout-id body) (UUID/randomUUID)))
       :provider-checkout-id  (or (:checkout-id body) (str (UUID/randomUUID)))
       :payload               body})))
