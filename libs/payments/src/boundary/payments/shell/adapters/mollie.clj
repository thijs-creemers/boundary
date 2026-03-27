(ns boundary.payments.shell.adapters.mollie
  "Mollie payment provider adapter."
  (:require [boundary.payments.core.provider :as provider]
            [boundary.payments.ports :as ports]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [hato.client :as hato])
  (:import [java.util UUID]))

(def ^:private mollie-api-base "https://api.mollie.com/v2")

(defn- mollie-headers [api-key]
  {"Authorization"  (str "Bearer " api-key)
   "Content-Type"   "application/json"
   "Accept"         "application/json"
   "User-Agent"     "boundary-payments/1.0"})

(defn- fetch-payment [api-key payment-id]
  (let [url      (str mollie-api-base "/payments/" payment-id)
        response (hato/get url {:headers        (mollie-headers api-key)
                                :as             :string
                                :throw-on-error? false})]
    (when (= 200 (:status response))
      (json/parse-string (:body response) true))))

(defn- mollie-status->event-type [status]
  (provider/mollie-status->event-type status))

(defrecord MolliePaymentProvider [api-key webhook-base-url]
  ports/IPaymentProvider

  (create-checkout-session [_ {:keys [amount-cents currency description redirect-url webhook-url metadata]}]
    (let [checkout-id  (str (UUID/randomUUID))
          webhook      (or webhook-url (str webhook-base-url "/api/v1/payments/webhook"))
          payload      {:amount      {:currency (or currency "EUR")
                                      :value    (format "%.2f" (/ (double amount-cents) 100.0))}
                        :description description
                        :redirectUrl redirect-url
                        :webhookUrl  webhook
                        :metadata    (merge {:checkout-id checkout-id} metadata)}
          response     (hato/post (str mollie-api-base "/payments")
                                  {:headers         (mollie-headers api-key)
                                   :body            (json/generate-string payload)
                                   :as              :string
                                   :throw-on-error? false})
          body         (json/parse-string (:body response) true)]
      (log/infof "Mollie create-checkout: status=%d id=%s" (:status response) (:id body))
      (when-not (#{200 201} (:status response))
        (throw (ex-info "Mollie checkout creation failed"
                        {:type   :internal-error
                         :status (:status response)
                         :body   body})))
      {:checkout-url         (get-in body [:_links :checkout :href])
       :provider-checkout-id (:id body)}))

  (get-payment-status [_ provider-checkout-id]
    (let [payment (fetch-payment api-key provider-checkout-id)]
      (if payment
        {:status              (or (mollie-status->event-type (:status payment)) :pending)
         :provider-payment-id (:id payment)}
        {:status :pending :provider-payment-id nil})))

  (verify-webhook-signature [_ _raw-body _headers]
    ;; Mollie does not use HMAC signing. Verification happens by fetching
    ;; the payment and checking its status.
    true)

  (process-webhook [_ raw-body _headers]
    ;; Mollie sends a form-POST with only the payment id as `id`
    (let [payment-id (if (string? raw-body)
                       (second (re-find #"id=([^&]+)" raw-body))
                       (or (:id raw-body) (str raw-body)))
          payment    (when payment-id (fetch-payment api-key payment-id))
          status     (:status payment)
          event-type (mollie-status->event-type status)]
      (log/infof "Mollie webhook: payment-id=%s status=%s → event=%s"
                 payment-id status event-type)
      (when-not event-type
        (throw (ex-info "Unhandled Mollie payment status"
                        {:type   :internal-error
                         :status status})))
      {:event-type            event-type
       :provider-payment-id   payment-id
       :provider-checkout-id  (get-in payment [:metadata :checkoutId]
                                      (get-in payment [:metadata :checkout-id]))
       :payload               (or payment {})})))
