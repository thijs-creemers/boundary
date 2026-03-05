# External Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

External third-party service adapters following the FC/IS pattern.

## Status

**Implemented** — Four adapters: SMTP, IMAP, Stripe, and Twilio.

## Key Namespaces

```
boundary.external.schema           — Malli schemas for all four adapters
boundary.external.ports            — Protocol definitions (ISmtpProvider, IImapMailbox,
                                     IStripePayments, IStripeWebhooks, ITwilioMessaging)
boundary.external.core.smtp        — Pure SMTP helpers (normalize-recipients, build-mime-properties)
boundary.external.core.imap        — Pure IMAP helpers (parse-message-headers, extract-body-text,
                                     build-inbound-message, filter-by-date, filter-unread)
boundary.external.core.stripe      — Pure Stripe helpers (build-payment-intent-params,
                                     parse-payment-intent, verify-stripe-signature)
boundary.external.core.twilio      — Pure Twilio helpers (build-sms-params, build-whatsapp-params,
                                     parse-message-response, parse-twilio-error)
boundary.external.shell.adapters.smtp    — SmtpProviderAdapter (javax.mail)
boundary.external.shell.adapters.imap    — ImapMailboxAdapter (javax.mail, UIDFolder)
boundary.external.shell.adapters.stripe  — StripeAdapter (clj-http, form-encoded POSTs)
boundary.external.shell.adapters.twilio  — TwilioAdapter (clj-http, Basic auth)
boundary.external.shell.module-wiring    — Integrant init/halt for :boundary.external/* keys
```

## Enabling Adapters

All four adapters are shipped in `:inactive` in `resources/conf/dev/config.edn`. Move any key to `:active` to enable it:

```clojure
;; In :active section of config.edn:
:boundary.external/stripe
{:api-key #env STRIPE_API_KEY
 :webhook-secret #env STRIPE_WEBHOOK_SECRET
 :api-version "2024-04-10"}
```

Integrant keys: `:boundary.external/smtp`, `:boundary.external/imap`, `:boundary.external/stripe`, `:boundary.external/twilio`.

## FC/IS Layout

```
libs/external/src/boundary/external/
├── schema.clj          Malli schemas
├── ports.clj           Protocol definitions
├── core/
│   ├── smtp.clj        Pure SMTP helpers (no javax.mail I/O)
│   ├── imap.clj        Pure IMAP message parsing / filtering
│   ├── stripe.clj      Pure Stripe transformations + HMAC webhook verification
│   └── twilio.clj      Pure Twilio param building + response parsing
└── shell/
    ├── adapters/
    │   ├── smtp.clj    SmtpProviderAdapter — javax.mail Transport
    │   ├── imap.clj    ImapMailboxAdapter — javax.mail Store/UIDFolder
    │   ├── stripe.clj  StripeAdapter — clj-http REST
    │   └── twilio.clj  TwilioAdapter — clj-http REST
    └── module_wiring.clj  Integrant multimethods
```

## Dependencies

- `clj-http/clj-http 3.13.0` — Stripe and Twilio REST calls
- `cheshire/cheshire 6.1.0` — JSON parsing for API responses
- `com.sun.mail/javax.mail 1.6.2` — SMTP and IMAP
- `metosin/malli 0.20.0` — schema validation

## Key Pitfalls

### IMAP UIDFolder Cast
`Message.getMessageNumber()` is NOT the IMAP UID. Always cast the folder:
```clojure
(let [uid-fld (cast com.sun.mail.imap.IMAPFolder folder)]
  (.getUID uid-fld message))
```

### Stripe Form-Encoding
Stripe uses form-encoded bodies (NOT JSON) for POST requests:
```clojure
{:form-params {"amount" "1000" "currency" "eur"} :as :json :coerce :always}
```

### Stripe Webhook Raw Body
The webhook route must read the raw body BEFORE any body-parsing middleware:
```clojure
;; verify-webhook! takes the raw payload string, not a parsed map
(ports/verify-webhook! stripe-adapter raw-body-string stripe-signature-header)
```

### Stripe Webhook Timestamp Tolerance
`verify-stripe-signature` in `core/stripe` defaults to 300s clock skew tolerance. Pass a custom value as the 5th arg if needed.

### HMAC Constant-Time Compare
Webhook signature comparison uses `MessageDigest/isEqual` on byte arrays — never `.equals` on strings.

### IMAP Connection-Per-Call
`ImapMailboxAdapter` opens and closes a Store + Folder per operation. `close!` is a no-op (returns `true`). Use `try/finally` inside `with-imap-connection`.

### Twilio WhatsApp Sandbox
In dev, Twilio sandbox requires the sandbox number (`whatsapp:+14155238886`) as the From number. The adapter is config-agnostic — set `:from-number` accordingly.

## Testing

```bash
# All external tests
clojure -M:test:db/h2 :external

# Unit tests only (no I/O)
clojure -M:test:db/h2 --focus-meta :unit

# Integration tests (no real services — tests against unreachable hosts)
clojure -M:test:db/h2 --focus-meta :integration

# Lint
clojure -M:clj-kondo --lint libs/external/src libs/external/test
```

## Links

- [Root AGENTS Guide](../../AGENTS.md)
