# boundary/external

**Status:** In Development  
**Version:** 0.1.0-SNAPSHOT

Adapters for external services: email, payments, and notifications.

## Installation

```clojure
{:deps {boundary/external {:mvn/version "0.1.0"}}}
```

## Features

- **Email**: SMTP adapter for sending emails
- **Payments**: Stripe payment processing
- **Notifications**: Generic notification system

## Quick Start

```clojure
(ns myapp.notifications
  (:require [boundary.external.email.smtp :as email]))

(def mailer (email/smtp-mailer {:host "smtp.example.com" 
                                :port 587
                                :user "user"
                                :pass "pass"}))

(email/send mailer {:to "user@example.com"
                    :subject "Welcome"
                    :body "Welcome to our app!"})
```

## License

See root [LICENSE](../../LICENSE)
