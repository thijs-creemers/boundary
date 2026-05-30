# boundary-push

[![Status](https://img.shields.io/badge/status-alpha-orange)]()
[![Clojure](https://img.shields.io/badge/clojure-1.12+-blue)]()
[![License](https://img.shields.io/badge/license-EPL--2.0-green)]()
[![Clojars Project](https://img.shields.io/clojars/v/org.boundary-app/boundary-push.svg)](https://clojars.org/org.boundary-app/boundary-push)

> Multi-platform push notifications for the Boundary framework — FCM (Firebase) and APNs (Apple) with device token management, job-based async delivery, and HMAC-secured analytics callbacks.

---

## Quick Start

```clojure
;; deps.edn
{:deps {org.boundary-app/boundary-push {:mvn/version "1.0.1-alpha-26"}}}
```

```clojure
(require '[boundary.push.shell.service :as push])

;; Define a notification type
(defpush order-shipped
  {:id           :order-shipped
   :title        {:en "Order Shipped" :nl "Bestelling Verzonden"}
   :body         {:en "Your order {{order-id}} is on its way!"}
   :channels     #{:fcm :apns}
   :priority     :high
   :ttl          3600
   :deep-link    "/orders/{{order-id}}"
   :retry        {:max-attempts 3 :backoff :exponential}})

;; Send via job queue
(push/send-push! push-service :order-shipped
  {:order-id "ORD-123"} {:user-id user-id :locale :nl})

;; Scheduled delivery
(push/schedule-push! push-service :appointment-reminder
  {:time "14:00"} {:user-id user-id} future-instant)

;; Broadcast to a platform
(push/broadcast! push-service :maintenance-alert
  {:message "Downtime at 2am"} {:platform :fcm})
```

---

## Integrant Configuration

Add to `resources/conf/{env}/config.edn` and require `boundary.push.shell.module-wiring` at system start:

```edn
:boundary/push
{:fcm-credentials  #env BND_PUSH_FCM_CREDENTIALS_JSON
 :apns-credentials {:key-id     #env BND_PUSH_APNS_KEY_ID
                    :team-id    #env BND_PUSH_APNS_TEAM_ID
                    :private-key #env BND_PUSH_APNS_PRIVATE_KEY
                    :bundle-id  #env BND_PUSH_APNS_BUNDLE_ID
                    :sandbox?   false}
 :db               #ig/ref :boundary/db
 :jobs             #ig/ref :boundary/jobs}
```

---

## API

```clojure
(require '[boundary.push.shell.service :as push])

;; Send immediately via job queue
(push/send-push! service :notification-id template-vars opts)

;; Schedule for a future instant
(push/schedule-push! service :notification-id template-vars opts instant)

;; Broadcast to all active tokens for a platform
(push/broadcast! service :notification-id template-vars {:platform :fcm})

;; Device token management
(push/register-token! service {:user-id uuid :token "..." :platform :fcm :app-id "com.example"})
(push/deactivate-token! service token)
(push/list-tokens service user-id)
```

---

## DB Migration

Run once before using push notifications. When `boundary-push` is on the classpath, `clojure -M:migrate up` auto-discovers these migrations:

```sql
-- libs/push/resources/boundary/push/migrations/20260524000000-device-tokens.up.sql
CREATE TABLE IF NOT EXISTS push_device_tokens (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL,
    tenant_id     UUID,
    token         VARCHAR(512) NOT NULL,
    platform      VARCHAR(10) NOT NULL,
    app_id        VARCHAR(255) NOT NULL,
    device_name   VARCHAR(255),
    os_version    VARCHAR(50),
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_push_device_token UNIQUE (token, app_id)
);

-- libs/push/resources/boundary/push/migrations/20260524000001-push-send-log.up.sql
CREATE TABLE IF NOT EXISTS push_send_log (
    id                  UUID PRIMARY KEY,
    notification_id     VARCHAR(255) NOT NULL,
    user_id             UUID,
    device_token_id     UUID,
    device_token        VARCHAR(512) NOT NULL,
    platform            VARCHAR(10) NOT NULL,
    title               VARCHAR(500),
    body                TEXT,
    priority            VARCHAR(10) NOT NULL DEFAULT 'normal',
    status              VARCHAR(20) NOT NULL,
    provider_message_id VARCHAR(255),
    error_message       TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at             TIMESTAMP,
    tenant_id           UUID
);
```

---

## Providers

| Provider | Key `:channels` | Credentials | Notes |
|----------|----------------|-------------|-------|
| Firebase Cloud Messaging | `:fcm` | Service account JSON (`BND_PUSH_FCM_CREDENTIALS_JSON`) | Supports multicast, token validation |
| Apple Push Notification service | `:apns` | Key ID + Team ID + P8 private key | Separate sandbox/production hosts; set `:sandbox?` per env |
| Mock (dev/test) | `:mock` | None | In-memory; use `boundary.push.shell.adapters.mock` |

---

## Tests

```bash
clojure -M:test:db/h2 :push
clojure -M:test:db/h2 :push --focus-meta :unit
clojure -M:test:db/h2 :push --focus-meta :contract
```

See [AGENTS.md](AGENTS.md) for full developer guide, gotchas, and REPL smoke checks.
