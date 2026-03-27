(ns boundary.payments.shell.module-wiring
  "Integrant wiring for the :boundary/payment-provider component."
  (:require [boundary.payments.shell.adapters.mock :as mock]
            [boundary.payments.shell.adapters.mollie :as mollie]
            [boundary.payments.shell.adapters.stripe :as stripe]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; :boundary/payment-provider
;; config: {:provider :mock|:mollie|:stripe
;;           :api-key "..."            ; mollie or stripe secret key
;;           :webhook-secret "..."     ; stripe only
;;           :webhook-base-url "..."}  ; mollie: base URL for webhook registration

(defmethod ig/init-key :boundary/payment-provider
  [_ {:keys [provider api-key webhook-secret webhook-base-url]}]
  (log/infof "Initializing payment provider: %s" provider)
  (case (or provider :mock)
    :mock   (do (log/info "Using Mock payment provider (development mode)")
                (mock/->MockPaymentProvider))
    :mollie (do (log/info "Using Mollie payment provider")
                (mollie/->MolliePaymentProvider api-key webhook-base-url))
    :stripe (do (log/info "Using Stripe payment provider")
                (stripe/->StripePaymentProvider api-key webhook-secret))
    (throw (ex-info "Unknown payment provider"
                    {:type     :internal-error
                     :provider provider
                     :valid    #{:mock :mollie :stripe}}))))

(defmethod ig/halt-key! :boundary/payment-provider
  [_ _]
  (log/info "Payment provider halted"))
