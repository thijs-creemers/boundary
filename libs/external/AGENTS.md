# External Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

External third-party communication adapters following the FC/IS pattern.
Covers three integration channels: outbound email (SMTP), inbound email (IMAP), and SMS/WhatsApp (Twilio).

For payment processing (Mollie, Stripe), see [`libs/payments/AGENTS.md`](../payments/AGENTS.md).

## Key Namespaces

```
boundary.external.schema           — Malli schemas for all three adapters
boundary.external.ports            — Protocol definitions (ISmtpProvider, IImapMailbox, ITwilioMessaging)
boundary.external.core.smtp        — Pure SMTP helpers (normalize-recipients, build-mime-properties)
boundary.external.core.imap        — Pure IMAP helpers (parse-message-headers, extract-body-text,
                                     build-inbound-message, filter-by-date, filter-unread)
boundary.external.core.twilio      — Pure Twilio helpers (build-sms-params, build-whatsapp-params,
                                     parse-message-response, parse-twilio-error)
boundary.external.shell.adapters.smtp    — SmtpProviderAdapter (javax.mail)
boundary.external.shell.adapters.imap    — ImapMailboxAdapter (javax.mail, UIDFolder)
boundary.external.shell.adapters.twilio  — TwilioAdapter (clj-http, Basic auth)
boundary.external.shell.module-wiring    — Integrant init/halt for :boundary.external/* keys
```

## FC/IS Layout

```
libs/external/src/boundary/external/
├── schema.clj          Malli schemas
├── ports.clj           Protocol definitions
├── core/
│   ├── smtp.clj        Pure SMTP helpers (no javax.mail I/O)
│   ├── imap.clj        Pure IMAP message parsing / filtering
│   └── twilio.clj      Pure Twilio param building + response parsing
└── shell/
    ├── adapters/
    │   ├── smtp.clj    SmtpProviderAdapter — javax.mail Transport
    │   ├── imap.clj    ImapMailboxAdapter — javax.mail Store/UIDFolder
    │   └── twilio.clj  TwilioAdapter — clj-http REST
    └── module_wiring.clj  Integrant multimethods
```

## Integrant Keys

All three keys are opt-in — add to `:active` in `config.edn` to enable:

```clojure
:boundary.external/smtp
{:host     #env SMTP_HOST
 :port     587
 :username #env SMTP_USERNAME
 :password #env SMTP_PASSWORD
 :tls?     true
 :from     "noreply@example.com"}

:boundary.external/imap
{:host     #env IMAP_HOST
 :port     993
 :username #env IMAP_USERNAME
 :password #env IMAP_PASSWORD
 :ssl?     true}

:boundary.external/twilio
{:account-sid  #env TWILIO_ACCOUNT_SID
 :auth-token   #env TWILIO_AUTH_TOKEN
 :from-number  #env TWILIO_FROM_NUMBER}
```

## Dependencies

- `clj-http/clj-http 3.13.1` — Twilio REST calls
- `cheshire/cheshire 6.2.0` — JSON parsing
- `com.sun.mail/javax.mail 1.6.2` — SMTP and IMAP
- `metosin/malli 0.20.1` — schema validation

## Key Pitfalls

### IMAP UIDFolder Cast
`Message.getMessageNumber()` is NOT the IMAP UID. Always cast the folder:
```clojure
(let [uid-fld (cast com.sun.mail.imap.IMAPFolder folder)]
  (.getUID uid-fld message))
```

### IMAP Connection-Per-Call
`ImapMailboxAdapter` opens and closes a Store + Folder per operation. `close!` is a no-op (returns `true`). Use `try/finally` inside `with-imap-connection`.

### Twilio WhatsApp Sandbox
In dev, Twilio sandbox requires the sandbox number (`whatsapp:+14155238886`) as the From number. The adapter is config-agnostic — set `:from-number` accordingly.

## Testing

```bash
# All external tests
clojure -M:test:db/h2 :external

# Unit tests only (pure functions, no I/O)
clojure -M:test:db/h2 --focus-meta :unit

# Integration tests (validates parsing; no real services needed)
clojure -M:test:db/h2 --focus-meta :integration
```

## Links

- [Payments AGENTS — PSP integration](../payments/AGENTS.md)
- [Root AGENTS Guide](../../AGENTS.md)
