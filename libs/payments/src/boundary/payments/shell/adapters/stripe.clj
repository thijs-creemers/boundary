(ns boundary.payments.shell.adapters.stripe
  "Stripe payment provider adapter."
  (:require [boundary.payments.core.provider :as provider]
            [boundary.payments.ports :as ports]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hato.client :as hato])
  (:import [java.nio.charset StandardCharsets]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private stripe-api-base "https://api.stripe.com/v1")

(defn- stripe-headers [api-key]
  {"Authorization" (str "Bearer " api-key)
   "Accept"        "application/json"
   "User-Agent"    "boundary-payments/1.0"})

(defn- url-encode [^String s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn- form-encode
  "Encode a flat string->string map as application/x-www-form-urlencoded."
  [^java.util.Map params]
  (->> params
       (map (fn [[k v]] (str (url-encode (name k)) "=" (url-encode (str v)))))
       (str/join "&")))

(defn- hmac-sha256 ^bytes [^bytes key-bytes ^bytes data-bytes]
  (let [mac (Mac/getInstance "HmacSHA256")
        key (SecretKeySpec. key-bytes "HmacSHA256")]
    (.init mac key)
    (.doFinal mac data-bytes)))

(defn- hex-encode [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(defn- compute-stripe-signature [raw-body timestamp webhook-secret]
  (let [signed-payload (str timestamp "." raw-body)
        key-bytes      (.getBytes ^String webhook-secret StandardCharsets/UTF_8)
        data-bytes     (.getBytes ^String signed-payload StandardCharsets/UTF_8)]
    (hex-encode (hmac-sha256 key-bytes data-bytes))))

(defn- parse-stripe-signature-header
  "Parse Stripe-Signature header into {:t timestamp :v1 signature}."
  [header]
  (when header
    (into {}
          (for [part (str/split (str header) #",")
                :let [[k v] (str/split part #"=" 2)]
                :when (and k v)]
            [k v]))))

(defn- stripe-checkout-params
  "Build form-encoded params map for a Stripe Checkout Session."
  [{:keys [amount-cents currency description redirect-url]}]
  ;; Stripe Checkout Session uses nested form params for line_items
  {"line_items[0][price_data][currency]"                    (str/lower-case (or currency "eur"))
   "line_items[0][price_data][unit_amount]"                 (str amount-cents)
   "line_items[0][price_data][product_data][name]"          description
   "line_items[0][quantity]"                                "1"
   "payment_method_types[0]"                                "card"
   "mode"                                                   "payment"
   "success_url"                                            redirect-url
   "cancel_url"                                             redirect-url})

(defrecord StripePaymentProvider [api-key webhook-secret]
  ports/IPaymentProvider

  (create-checkout-session [_ opts]
    (let [params   (stripe-checkout-params opts)
          response (hato/post (str stripe-api-base "/checkout/sessions")
                              {:headers         (merge (stripe-headers api-key)
                                                       {"Content-Type" "application/x-www-form-urlencoded"})
                               :body            (form-encode params)
                               :as              :string
                               :throw-on-error? false})
          body     (json/parse-string (:body response) true)]
      (log/infof "Stripe create-checkout: status=%d id=%s" (:status response) (:id body))
      (when-not (#{200 201} (:status response))
        (throw (ex-info "Stripe checkout creation failed"
                        {:type   :internal-error
                         :status (:status response)
                         :body   body})))
      {:checkout-url         (:url body)
       :provider-checkout-id (:id body)}))

  (get-payment-status [_ provider-checkout-id]
    (let [url      (str stripe-api-base "/checkout/sessions/" provider-checkout-id)
          response (hato/get url {:headers         (stripe-headers api-key)
                                  :as              :string
                                  :throw-on-error? false})
          body     (json/parse-string (:body response) true)]
      {:status              (case (:payment_status body)
                              "paid"                 :paid
                              "no_payment_required"  :paid
                              :pending)
       :provider-payment-id (:payment_intent body)}))

  (verify-webhook-signature [_ raw-body headers]
    (let [sig-header (or (get headers "stripe-signature")
                         (get headers "Stripe-Signature"))
          parts      (parse-stripe-signature-header sig-header)
          ts         (get parts "t")
          v1         (get parts "v1")]
      (if (and ts v1 webhook-secret)
        (= (compute-stripe-signature raw-body ts webhook-secret) v1)
        false)))

  (process-webhook [_ raw-body _headers]
    (let [event      (if (string? raw-body)
                       (json/parse-string raw-body true)
                       (or raw-body {}))
          event-type (provider/stripe-event->event-type (:type event))
          pi-obj     (get-in event [:data :object])]
      (log/infof "Stripe webhook: type=%s → event=%s" (:type event) event-type)
      (when-not event-type
        (throw (ex-info "Unhandled Stripe event type"
                        {:type       :internal-error
                         :event-type (:type event)})))
      {:event-type            event-type
       :provider-payment-id   (:id pi-obj)
       :provider-checkout-id  (get-in pi-obj [:metadata :checkout_id])
       :payload               event})))
