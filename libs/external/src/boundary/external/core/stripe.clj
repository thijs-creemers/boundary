(ns boundary.external.core.stripe
  "Pure functions for Stripe API data transformation and webhook verification.
   No I/O, no side effects.
   Webhook HMAC verification uses javax.crypto.Mac (HMAC-SHA256)."
  (:require [clojure.string :as str])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.security MessageDigest]))

;; =============================================================================
;; Parameter Building
;; =============================================================================

(defn build-payment-intent-params
  "Convert a CreatePaymentIntentInput map to Stripe form-encoded params.
   Stripe uses form-encoding (not JSON) for POST bodies.

  Args:
    input - map with :amount :currency and optional :description :metadata :customer-id

  Returns:
    Map of string keys for use as :form-params in clj-http"
  [{:keys [amount currency description metadata customer-id]}]
  (let [base (cond-> {"amount"   (str amount)
                      "currency" (str currency)}
               description (assoc "description" description)
               customer-id (assoc "customer" customer-id))]
    (if metadata
      (reduce-kv
       (fn [m k v] (assoc m (str "metadata[" (name k) "]") (str v)))
       base
       metadata)
      base)))

;; =============================================================================
;; Response Parsing
;; =============================================================================

(defn- epoch->inst
  "Convert a Unix epoch long to java.util.Date."
  [epoch]
  (when epoch
    (java.util.Date. (* (long epoch) 1000))))

(defn parse-payment-intent
  "Parse a Stripe payment intent response body into an internal PaymentIntent map.
   Converts camelCase keys to kebab-case and epoch timestamps to inst?.

  Args:
    body - parsed JSON map (string keys) from Stripe API response

  Returns:
    PaymentIntent map with keyword keys"
  [body]
  {:id            (get body "id")
   :status        (get body "status")
   :amount        (get body "amount")
   :currency      (get body "currency")
   :client-secret (get body "client_secret")
   :created-at    (epoch->inst (get body "created"))
   :metadata      (get body "metadata" {})})

(defn parse-stripe-event
  "Parse a Stripe webhook event body into an internal StripeWebhookEvent map.

  Args:
    body - parsed JSON map (string keys)

  Returns:
    StripeWebhookEvent map with keyword keys"
  [body]
  {:id          (get body "id")
   :type        (get body "type")
   :api-version (get body "api_version")
   :created-at  (epoch->inst (get body "created"))
   :data        (get body "data" {})})

(defn parse-error-response
  "Parse a Stripe error response body into an internal error map.

  Args:
    body        - parsed JSON map (string keys), may have top-level :error key
    status-code - HTTP status code integer

  Returns:
    Map with :message :type :code :status-code"
  [body status-code]
  (let [err (get body "error" body)]
    {:message     (get err "message" "Unknown Stripe error")
     :type        (get err "type" "api_error")
     :code        (get err "code")
     :status-code status-code}))

;; =============================================================================
;; Display Formatting
;; =============================================================================

(def ^:private currency-symbols
  {"eur" "€" "usd" "$" "gbp" "£" "jpy" "¥"})

(defn format-amount
  "Format a Stripe amount (in smallest currency unit, e.g. cents) as a display string.

  Args:
    cents    - integer amount in smallest currency unit
    currency - ISO 4217 currency code string (e.g. \"eur\")

  Returns:
    Display string, e.g. \"€ 10.50\" or \"USD 1000\" (for zero-decimal currencies)"
  [cents currency]
  (let [cur  (some-> currency str .toLowerCase)
        sym  (get currency-symbols cur (str/upper-case (or (str currency) "")))
        ;; JPY and similar zero-decimal currencies
        zero-decimal? (#{"jpy" "krw" "vnd" "clp" "gnf" "mga" "pyg" "rwf" "ugx" "xaf" "xof"} cur)]
    (if zero-decimal?
      (str sym " " cents)
      (String/format java.util.Locale/US "%s %.2f" (into-array Object [sym (/ (double cents) 100.0)])))))

;; =============================================================================
;; Webhook Signature Verification
;; =============================================================================

(defn- hmac-sha256-hex
  "Compute HMAC-SHA256 of message with key and return lowercase hex string."
  [^String key-str ^String message]
  (let [key-bytes (.getBytes key-str "UTF-8")
        mac       (Mac/getInstance "HmacSHA256")
        key-spec  (SecretKeySpec. key-bytes "HmacSHA256")]
    (.init mac key-spec)
    (let [digest (.doFinal mac (.getBytes message "UTF-8"))]
      (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(defn- constant-time-equal?
  "Compare two strings for equality in constant time using MessageDigest.isEqual.
   Prevents timing attacks."
  [^String a ^String b]
  (MessageDigest/isEqual
   (.getBytes a "UTF-8")
   (.getBytes b "UTF-8")))

(defn verify-stripe-signature
  "Verify a Stripe webhook signature header.

  Algorithm:
    1. Parse 't=<timestamp>,v1=<signature>' from the header
    2. Check |current-epoch - timestamp| <= tolerance (default 300s)
    3. Compute HMAC-SHA256(webhook-secret, '<timestamp>.<payload>')
    4. Constant-time compare with the v1 signature from the header

  Args:
    payload        - raw request body string
    signature      - value of the Stripe-Signature header
    webhook-secret - Stripe webhook signing secret
    current-epoch  - current Unix epoch as long (for testability)
    tolerance      - max clock skew in seconds (default 300)

  Returns:
    {:valid? true} or {:valid? false :error \"reason\"}"
  ([payload signature webhook-secret current-epoch]
   (verify-stripe-signature payload signature webhook-secret current-epoch 300))
  ([payload signature webhook-secret current-epoch tolerance]
   (try
     (let [parts     (clojure.string/split signature #",")
           kv-map    (into {} (map #(let [[k v] (clojure.string/split % #"=" 2)] [k v]) parts))
           ts-str    (get kv-map "t")
           v1-sig    (get kv-map "v1")]
       (if (or (nil? ts-str) (nil? v1-sig))
         {:valid? false :error "Missing t or v1 in Stripe-Signature header"}
         (let [ts (Long/parseLong ts-str)
               age (Math/abs (- current-epoch ts))]
           (if (> age tolerance)
             {:valid? false :error (str "Timestamp too old: " age "s > " tolerance "s tolerance")}
             (let [signed-payload (str ts-str "." payload)
                   expected       (hmac-sha256-hex webhook-secret signed-payload)]
               (if (constant-time-equal? expected v1-sig)
                 {:valid? true}
                 {:valid? false :error "Signature mismatch"}))))))
     (catch Exception e
       {:valid? false :error (.getMessage e)}))))
