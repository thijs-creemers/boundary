# Payments Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

PSP (Payment Service Provider) abstraction for the Boundary Framework.
Provides a single `IPaymentProvider` protocol that decouples application code
from specific payment processors. Swap between Mollie, Stripe, and Mock without
changing any business logic.

**Scope:** checkout-session flow — create → redirect → webhook → status.
This is the only payment library in the framework; `libs/external` handles
communication channels (SMTP, IMAP, Twilio) but not payments.

---

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.payments.ports` | `IPaymentProvider` protocol definition |
| `boundary.payments.schema` | Malli schemas: `CheckoutRequest`, `CheckoutResult`, `PaymentStatusResult`, `WebhookResult` |
| `boundary.payments.core.provider` | Pure helpers: `cents->euro`, `normalize-event-type`, status mappers |
| `boundary.payments.shell.adapters.mock` | Mock adapter — auto-approves, no network calls |
| `boundary.payments.shell.adapters.mollie` | Mollie PSP adapter (hato HTTP client) |
| `boundary.payments.shell.adapters.stripe` | Stripe PSP adapter (hato + HMAC webhook verification) |
| `boundary.payments.shell.module-wiring` | Integrant `init-key` / `halt-key!` |

---

## Protocol — `IPaymentProvider`

```clojure
(create-checkout-session [this opts])
;; opts: {:amount-cents pos-int
;;        :currency     "EUR"        ; ISO 4217
;;        :description  string
;;        :redirect-url string       ; where PSP sends the user after payment
;;        :webhook-url  string       ; optional; Mollie uses this
;;        :metadata     map}         ; optional; passed through to PSP
;; Returns: {:checkout-url string :provider-checkout-id string}

(get-payment-status [this provider-checkout-id])
;; Returns: {:status :pending|:paid|:failed|:cancelled
;;           :provider-payment-id string}

(verify-webhook-signature [this raw-body headers])
;; Returns: boolean

(process-webhook [this raw-body headers])
;; Returns: {:event-type           keyword
;;           :provider-payment-id  string
;;           :provider-checkout-id string
;;           :payload              map}
```

### Normalized event types

| Keyword | Meaning |
|---------|---------|
| `:payment.paid` | Payment successfully completed |
| `:payment.authorized` | Authorized, not yet captured |
| `:payment.failed` | Payment attempt failed |
| `:payment.cancelled` | Cancelled by user or PSP |

---

## Adapters

### Mock (`:mock`)
- Auto-approves every payment — no network calls
- `create-checkout-session` → `{:checkout-url "/web/payment/mock-return?checkout-id=<uuid>"}`
- `verify-webhook-signature` → always `true`
- `get-payment-status` → always `{:status :paid}`
- Use in development and all automated tests

### Mollie (`:mollie`)
- REST API: `https://api.mollie.com/v2/payments`
- Webhook: form-POST with `id=<payment-id>` (no HMAC signing)
- `verify-webhook-signature` → always `true`; real verification happens via `get-payment-status`
- Status mapping: `"paid"` → `:payment.paid`, `"failed"` → `:payment.failed`, `"canceled"/"cancelled"` → `:payment.cancelled`
- Requires `:api-key` and `:webhook-base-url`

### Stripe (`:stripe`)
- REST API: `https://api.stripe.com/v1/checkout/sessions`
- Webhook: HMAC-SHA256 over `timestamp.raw-body`, verified with `Stripe-Signature` header
- Event mapping: `"payment_intent.succeeded"` → `:payment.paid`, etc.
- Requires `:api-key` and `:webhook-secret`

---

## Integrant

```clojure
;; config.edn
:boundary/payment-provider
{:provider        :mock        ; :mock | :mollie | :stripe
 :api-key         #env PSP_API_KEY
 :webhook-secret  #env PSP_WEBHOOK_SECRET   ; Stripe only
 :webhook-base-url #env APP_BASE_URL}       ; Mollie only
```

Only one key, one provider at a time. Switch providers by changing `:provider` — no code changes.

---

## Pure Helpers (`boundary.payments.core.provider`)

```clojure
(provider/cents->euro 11900)           ;=> "119.00"
(provider/mollie-status->event-type "paid")             ;=> :payment.paid
(provider/stripe-event->event-type "payment_intent.succeeded") ;=> :payment.paid
(provider/normalize-event-type "paid" :mollie)          ;=> :payment.paid
```

---

## Webhook handling pattern

Always verify the signature before processing:

```clojure
(defn handle-payment-webhook [request payment-provider]
  (let [raw-body (slurp (:body request))
        headers  (:headers request)]
    (if (ports/verify-webhook-signature payment-provider raw-body headers)
      (let [{:keys [event-type provider-checkout-id]}
            (ports/process-webhook payment-provider raw-body headers)]
        (case event-type
          :payment.paid      (activate-order! provider-checkout-id)
          :payment.failed    (mark-order-failed! provider-checkout-id)
          :payment.cancelled (cancel-order! provider-checkout-id)
          nil)) ; ignore unknown event types
      {:status 400 :body "Invalid signature"})))
```

**Important:** the webhook route must receive the **raw body string** before any body-parsing middleware. Parsing the body first breaks HMAC verification for Stripe.

---

## Gotchas

1. **Raw body for webhooks** — Stripe HMAC is computed over the raw string. Never pass a parsed map to `verify-webhook-signature` or `process-webhook`.
2. **Mollie has no HMAC** — `verify-webhook-signature` always returns `true` for Mollie. Verify by calling `get-payment-status` with the payment ID from the webhook body.
3. **Mollie `"canceled"` vs `"cancelled"`** — Mollie uses the American spelling. Both are mapped to `:payment.cancelled`.
4. **Amounts in cents** — always use integer cents (11900 = €119.00). Never pass floats or euro strings.
5. **Currency as ISO 4217 string** — `"EUR"`, `"USD"`. Stripe lowercases it automatically; Mollie requires uppercase.
6. **Mock checkout URL** — `/web/payment/mock-return?checkout-id=<uuid>` is a local redirect; your app must have a handler for it in development.
7. **Provider is stateless** — records hold only config; safe to share across threads.

---

## Testing

```bash
clojure -M:test:db/h2 :payments        # All payments tests
clojure -M:test:db/h2 --focus-meta :unit  # Pure core functions only
```

Use the `:mock` provider in all tests — no PSP credentials required.

```clojure
;; In test setup
(def test-provider (mock/->MockPaymentProvider))
```

---

## Links

- [Payments How-to Guide](../../docs/modules/guides/pages/payments.adoc)
- [Root AGENTS Guide](../../AGENTS.md)
