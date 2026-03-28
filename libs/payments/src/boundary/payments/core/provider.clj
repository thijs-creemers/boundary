(ns boundary.payments.core.provider
  "Pure helper functions for payment provider logic — no I/O, no side effects.")

(defn cents->euro
  "Convert integer cents to a euro amount string (e.g. 11900 -> \"119.00\").
   Always uses a period as decimal separator regardless of JVM locale."
  [cents]
  (String/format java.util.Locale/US "%.2f" (into-array Object [(/ (double cents) 100.0)])))

(defn normalize-event-type
  "Map provider-specific event strings to internal event-type keywords."
  [raw-type provider]
  (case provider
    :mollie
    (case raw-type
      "paid"       :payment.paid
      "authorized" :payment.authorized
      "failed"     :payment.failed
      "canceled"   :payment.cancelled
      "cancelled"  :payment.cancelled
      nil)

    :stripe
    (case raw-type
      "payment_intent.succeeded"             :payment.paid
      "payment_intent.payment_failed"        :payment.failed
      "payment_intent.canceled"              :payment.cancelled
      "payment_intent.amount_capturable_updated" :payment.authorized
      nil)

    nil))

(defn mollie-status->event-type
  "Map a Mollie payment status string to an internal event-type keyword."
  [status]
  (normalize-event-type status :mollie))

(defn mollie-status->payment-status
  "Map a Mollie payment status string to a PaymentStatusResult :status keyword.
   Returns :pending for unrecognised statuses (e.g. \"open\", \"expired\")."
  [status]
  (case status
    "paid"       :paid
    "failed"     :failed
    "canceled"   :cancelled
    "cancelled"  :cancelled
    "expired"    :failed
    :pending))

(defn stripe-event->event-type
  "Map a Stripe event type string to an internal event-type keyword."
  [event-type]
  (normalize-event-type event-type :stripe))
