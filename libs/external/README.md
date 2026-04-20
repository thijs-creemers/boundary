# boundary/external

[![Status](https://img.shields.io/badge/status-in%20development-yellow)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/org.boundary-app/boundary-external.svg)](https://clojars.org/org.boundary-app/boundary-external)

**Status:** Active (not production-ready)  
**Version:** 1.0.1-alpha-13

Adapters for external services: Twilio SMS/WhatsApp, SMTP transport, and IMAP mailbox.

## Installation

```clojure
{:deps {org.boundary-app/boundary-external {:mvn/version "1.0.1-alpha-13"}}}
```

## Features

- **Email**: SMTP adapter for sending emails
- **Payments**: Stripe payment processing
- **Notifications**: Generic notification system

## Quick Start

```clojure
(ns myapp.notifications
  (:require [myapp.external.email :as email]))

(def mailer (email/smtp-mailer {:host "smtp.example.com" 
                                :port 587
                                :user "user"
                                :pass "pass"}))

(email/send mailer {:to "user@example.com"
                    :subject "Welcome"
                    :body "Welcome to our app!"})
```

## License

See root [CONTRIBUTING](../../CONTRIBUTING.md)
