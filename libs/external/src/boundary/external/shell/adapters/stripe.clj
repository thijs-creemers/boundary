(ns boundary.external.shell.adapters.stripe
  "Stripe payments adapter implementing IStripePayments and IStripeWebhooks.

   Uses clj-http for REST API calls. Stripe uses form-encoded POST bodies.
   Webhook verification delegates to the pure core/stripe functions.

   Usage:
     (def stripe (create-stripe-adapter
                   {:api-key \"sk_test_...\"
                    :webhook-secret \"whsec_...\"}))
     (create-payment-intent! stripe {:amount 1000 :currency \"eur\"})"
  (:require [boundary.external.core.stripe :as stripe-core]
            [boundary.external.ports :as ports]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.tools.logging :as log])
  (:import [java.time Instant]))

;; =============================================================================
;; HTTP Helpers
;; =============================================================================

(def ^:private default-api-version "2024-04-10")
(def ^:private default-base-url "https://api.stripe.com/v1")

(defn- stripe-get
  [{:keys [api-key api-version base-url]} path params]
  (http/get (str (or base-url default-base-url) path)
            {:basic-auth       [api-key ""]
             :headers          {"Stripe-Version" (or api-version default-api-version)}
             :query-params     params
             :as               :json
             :coerce           :always
             :throw-exceptions false}))

(defn- stripe-post
  [{:keys [api-key api-version base-url]} path form-params]
  (http/post (str (or base-url default-base-url) path)
             {:basic-auth       [api-key ""]
              :headers          {"Stripe-Version" (or api-version default-api-version)}
              :form-params      form-params
              :as               :json
              :coerce           :always
              :throw-exceptions false}))

(defn- success-response
  [response parse-fn]
  (let [body (:body response)
        status (:status response)]
    (if (< status 400)
      {:success? true :data (parse-fn body)}
      {:success? false
       :error    (stripe-core/parse-error-response
                  (if (string? body) (json/parse-string body) body)
                  status)})))

(defn- handle-exception
  [e context]
  (log/error e "Stripe API call failed" context)
  {:success? false
   :error    {:message (.getMessage e)
              :type    "NetworkError"}})

;; =============================================================================
;; Adapter Record
;; =============================================================================

(defrecord StripeAdapter [api-key webhook-secret api-version base-url])

(extend-protocol ports/IStripePayments
  StripeAdapter

  (create-payment-intent! [this input]
    (log/info "Creating Stripe payment intent" {:amount (:amount input) :currency (:currency input)})
    (try
      (let [params   (stripe-core/build-payment-intent-params input)
            response (stripe-post this "/payment_intents" params)]
        (success-response response stripe-core/parse-payment-intent))
      (catch Exception e
        (handle-exception e {:op :create-payment-intent}))))

  (retrieve-payment-intent! [this id]
    (log/info "Retrieving Stripe payment intent" {:id id})
    (try
      (let [response (stripe-get this (str "/payment_intents/" id) {})]
        (success-response response stripe-core/parse-payment-intent))
      (catch Exception e
        (handle-exception e {:op :retrieve-payment-intent :id id}))))

  (confirm-payment-intent! [this id]
    (log/info "Confirming Stripe payment intent" {:id id})
    (try
      (let [response (stripe-post this (str "/payment_intents/" id "/confirm") {})]
        (success-response response stripe-core/parse-payment-intent))
      (catch Exception e
        (handle-exception e {:op :confirm-payment-intent :id id}))))

  (cancel-payment-intent! [this id]
    (log/info "Cancelling Stripe payment intent" {:id id})
    (try
      (let [response (stripe-post this (str "/payment_intents/" id "/cancel") {})]
        (success-response response stripe-core/parse-payment-intent))
      (catch Exception e
        (handle-exception e {:op :cancel-payment-intent :id id}))))

  (list-payment-intents! [this params]
    (log/info "Listing Stripe payment intents")
    (try
      (let [query    (cond-> {}
                       (:limit params)          (assoc "limit" (str (:limit params)))
                       (:starting-after params) (assoc "starting_after" (:starting-after params))
                       (:customer params)       (assoc "customer" (:customer params)))
            response (stripe-get this "/payment_intents" query)
            body     (:body response)
            status   (:status response)]
        (if (< status 400)
          {:success?  true
           :data      (mapv stripe-core/parse-payment-intent (get body "data" []))
           :has-more? (boolean (get body "has_more" false))}
          {:success? false
           :error    (stripe-core/parse-error-response
                      (if (string? body) (json/parse-string body) body)
                      status)}))
      (catch Exception e
        (handle-exception e {:op :list-payment-intents})))))

(extend-protocol ports/IStripeWebhooks
  StripeAdapter

  (verify-webhook! [this payload signature]
    (log/info "Verifying Stripe webhook signature")
    (let [secret  (:webhook-secret this)
          epoch   (.getEpochSecond (Instant/now))
          result  (stripe-core/verify-stripe-signature payload signature secret epoch)]
      (if (:valid? result)
        (try
          (let [body  (json/parse-string payload)
                event (stripe-core/parse-stripe-event body)]
            {:valid? true :event event})
          (catch Exception e
            {:valid? false :error (str "Failed to parse event: " (.getMessage e))}))
        result))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-stripe-adapter
  "Create a Stripe adapter.

  Config keys:
    :api-key        - Stripe secret API key (required)
    :webhook-secret - Stripe webhook signing secret (optional)
    :api-version    - Stripe API version header (default \"2024-04-10\")
    :base-url       - Override API base URL for testing (default Stripe production)

  Returns:
    StripeAdapter implementing IStripePayments and IStripeWebhooks"
  [{:keys [api-key webhook-secret api-version base-url]
    :or   {api-version default-api-version}}]
  {:pre [(string? api-key)]}
  (log/info "Creating Stripe adapter" {:api-version api-version})
  (->StripeAdapter api-key webhook-secret api-version base-url))
