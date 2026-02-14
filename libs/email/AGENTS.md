# Email Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Email sending with SMTP support, validation, and optional async processing via the jobs module. Supports sync, async (future-based), and queued (jobs module) sending modes.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.email.core.email` | Pure functions: prepare, validate, add headers, summarize |
| `boundary.email.ports` | Protocols: EmailSenderProtocol, EmailQueueProtocol |
| `boundary.email.schema` | Malli schemas: EmailAddress, Email, SendEmailInput |
| `boundary.email.shell.adapters.smtp` | SMTP adapter (javax.mail) |
| `boundary.email.shell.jobs-integration` | Optional integration with jobs module for queued sending |

## Email Lifecycle

```clojure
(require '[boundary.email.core.email :as email])
(require '[boundary.email.ports :as ports])
(require '[boundary.email.shell.adapters.smtp :as smtp])

;; 1. Create sender
(def sender (smtp/create-smtp-sender
              {:host "smtp.gmail.com" :port 587
               :username "user@gmail.com" :password "app-password"
               :tls? true}))

;; 2. Prepare email (pure - normalizes :to to vector, adds UUID + timestamp)
(def prepared (email/prepare-email
                {:to "user@example.com"
                 :from "no-reply@myapp.com"
                 :subject "Welcome!"
                 :body "Thanks for signing up"}))

;; 3. Optional: add headers (pure)
(def with-headers (-> prepared
                      (email/add-reply-to "support@myapp.com")
                      (email/add-cc "admin@myapp.com")))

;; 4. Validate (pure)
(email/validate-email with-headers)
;; => {:valid? true :errors []}

;; 5. Send
(ports/send-email! sender with-headers)
;; => {:success? true :message-id "..."}

;; Or async
(def result-future (ports/send-email-async! sender with-headers))
@result-future  ; deref when needed
```

## Sending Modes

| Use Case | Method | Notes |
|----------|--------|-------|
| Critical (password reset) | `send-email!` | Blocks, immediate feedback |
| Non-critical, low volume | `send-email-async!` | Clojure `future`, non-blocking |
| High volume (>10/min) | `queue-email-job!` + jobs module | Redis queue, auto-retry |

## Gotchas

1. **Gmail**: Must use 16-char App Password, not regular password
2. **Port + TLS/SSL**: Port 587 → `:tls? true`, Port 465 → `:ssl? true`, Port 25 → both false
3. **Recipients are always vectors** after `prepare-email`, even if input is a single string
4. **Jobs module is optional** - uses `requiring-resolve`, throws `:type :missing-dependency` if unavailable
5. **No HTML templates yet** - plain text only (v2 planned)
6. **No attachment support yet** - schema includes it but SMTP adapter doesn't handle it

## Local Development

```bash
# Start local SMTP server (MailHog)
docker run -d -p 1025:1025 -p 8025:8025 mailhog/mailhog
# Config: {:host "localhost" :port 1025 :tls? false}
# View emails: http://localhost:8025
```

## Testing

```bash
clojure -M:test:db/h2 :email
```
