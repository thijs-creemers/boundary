# boundary-push: Push Notification Library Design

**Date:** 2026-05-24
**Status:** Draft
**Library:** `libs/push/`

## Summary

Multi-platform push notification delivery library for the Boundary framework. Supports Firebase Cloud Messaging (FCM) and Apple Push Notification service (APNs) directly — no third-party abstraction services. Follows FC/IS architecture with `defpush` macro consistent with `defreport`, `defevent`, and `defworkflow`.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Device token storage | Self-contained in boundary-push | Same pattern as jobs (own store). No coupling to user module. Simple table, mechanical cleanup. |
| Provider strategy | FCM + APNs from day one | Two providers expose bad abstractions early. Covers full mobile ecosystem. |
| `defpush` scope | Thick definitions | All config in definition (title, body, i18n, priority, TTL, deep-link, retry). Call sites stay clean. Matches other macros. |
| Provider protocols | Platform-specific behind unified service | `IFCMProvider` + `IAPNsProvider` instead of single `IPushProvider`. FCM and APNs have fundamentally different APIs/payloads. |
| Delivery analytics | Full with callback endpoint | Server-side send tracking + client-reported delivery/open events via HTTP callback. |
| Jobs integration | Hard dependency | All sends go through job queue. Push without retry/queue is fragile — no transport-level fallback like SMTP. |
| i18n | Built-in locale maps | Locale maps in `defpush` definition. No dependency on boundary-i18n. Push text is short and self-contained. |

## Library Structure

```
libs/push/
├── src/boundary/push/
│   ├── core/
│   │   ├── notification.clj       # defpush macro, registry, template rendering
│   │   ├── delivery.clj           # Pure: build platform payloads, retry calc, fan-out logic
│   │   ├── device.clj             # Pure: token validation, platform detection, staleness check
│   │   └── analytics.clj          # Pure: aggregate stats, rate calculations
│   ├── ports.clj                  # IPushService, IFCMProvider, IAPNsProvider, IDeviceTokenStore, IPushAnalyticsStore
│   ├── schema.clj                 # Malli schemas for all domain types
│   └── shell/
│       ├── service.clj            # IPushService impl — orchestrates providers, fan-out, analytics
│       ├── persistence.clj        # IDeviceTokenStore + IPushAnalyticsStore impl (next.jdbc)
│       ├── adapters/
│       │   ├── mock.clj           # MockFCMProvider + MockAPNsProvider (dev/test)
│       │   ├── fcm.clj            # Google FCM v1 API (HTTP, OAuth2 service account)
│       │   └── apns.clj           # Apple APNs (HTTP/2, JWT or certificate auth)
│       ├── handlers.clj           # Ring handlers: device registration + analytics callback
│       ├── jobs.clj               # Job handlers for async delivery + scheduled pushes
│       └── module_wiring.clj      # Integrant init-key/halt-key!
├── test/boundary/push/
│   ├── core/
│   │   ├── notification_test.clj
│   │   ├── delivery_test.clj
│   │   └── device_test.clj
│   └── shell/
│       ├── persistence_test.clj   # Contract tests (H2)
│       └── service_test.clj       # Integration tests (mock adapters)
├── resources/migrations/
│   ├── 001-device-tokens.up.sql
│   ├── 001-device-tokens.down.sql
│   ├── 002-push-log.up.sql
│   ├── 002-push-log.down.sql
│   ├── 003-analytics-events.up.sql
│   └── 003-analytics-events.down.sql
├── deps.edn
├── build.clj
└── AGENTS.md
```

**Dependencies:**
- Hard: `boundary/jobs`, `boundary/core`
- Dev/test: `boundary/devtools`

## Protocols (ports.clj)

### IPushService — consumer-facing orchestrator

```clojure
(defprotocol IPushService
  (send-push! [this notification-id data opts]
    "Enqueue push delivery for all user devices. opts: {:user-id uuid, :locale kw}")
  (send-push-to-device! [this notification-id data device-token]
    "Send to specific device. Used internally by job workers.")
  (schedule-push! [this notification-id data opts scheduled-at]
    "Schedule push for future delivery via jobs.")
  (broadcast! [this notification-id data opts]
    "Send to all registered devices matching opts: {:platform kw, :app-id str}"))
```

### IFCMProvider — Firebase Cloud Messaging

```clojure
(defprotocol IFCMProvider
  (fcm-send! [this payload]
    "Send FCM message. Returns {:success? bool :message-id str :error map}")
  (fcm-send-multicast! [this payload tokens]
    "Send to multiple FCM tokens. Returns per-token results.")
  (fcm-validate-token [this token]
    "Dry-run send to check token validity."))
```

### IAPNsProvider — Apple Push Notification service

```clojure
(defprotocol IAPNsProvider
  (apns-send! [this payload device-token]
    "Send APNs notification. Returns {:success? bool :apns-id str :error map}")
  (apns-send-batch! [this payload device-tokens]
    "Send to multiple APNs devices. Returns per-token results."))
```

### IDeviceTokenStore — persistence

```clojure
(defprotocol IDeviceTokenStore
  (register-device! [this user-id device-info]
    "Store device token. device-info: {:token str :platform kw :app-id str}")
  (unregister-device! [this user-id device-token]
    "Remove device token.")
  (get-user-devices [this user-id]
    "All active devices for user.")
  (get-devices-by-platform [this platform]
    "All devices for platform. Used by broadcast.")
  (mark-token-invalid! [this device-token]
    "Flag token as invalid after provider rejection.")
  (cleanup-stale-tokens! [this max-age-days]
    "Purge tokens not used within max-age-days."))
```

### IPushAnalyticsStore — delivery tracking

```clojure
(defprotocol IPushAnalyticsStore
  (record-send! [this event]
    "Log send attempt with provider response.")
  (record-delivery! [this event]
    "Log client-reported delivery confirmation.")
  (record-open! [this event]
    "Log client-reported notification open.")
  (get-push-stats [this notification-id opts]
    "Aggregate stats: sent/delivered/opened/failed counts."))
```

## `defpush` Macro

### Definition

```clojure
(defpush order-shipped
  {:id           :order-shipped
   :title        {:en "Order Shipped" :nl "Bestelling Verzonden"}
   :body         {:en "Your order {{order-id}} is on its way!"
                  :nl "Je bestelling {{order-id}} is onderweg!"}
   :channels     #{:fcm :apns}
   :priority     :high
   :ttl          3600
   :deep-link    "/orders/{{order-id}}"
   :silent?      false
   :collapse-key :order-status
   :retry        {:max-attempts 3 :backoff :exponential}})
```

### Registry

- Global atom-based registry (same as `defreport`, `defevent`, `defworkflow`)
- Validates definition against Malli schema at registration time
- `get-push`, `list-pushes`, `clear-registry!` for lookup and test isolation

### Template Rendering (pure)

- `render-template` — interpolates `{{var}}` placeholders with data map
- `resolve-content` — resolves localized content with fallback chain: requested locale -> `:en` -> first available
- `build-notification` — combines locale resolution + template rendering into ready-to-send map

### Usage

```clojure
(push/send-push! push-service :order-shipped
  {:order-id "ORD-123" :eta "2 hours"}
  {:user-id user-id :locale :nl})
```

## Schemas (schema.clj)

| Schema | Purpose |
|--------|---------|
| `PushDefinition` | Validates `defpush` definitions at registration |
| `DeviceInfo` | Input for device registration (token, platform, app-id) |
| `DeviceRecord` | Full device record with metadata and active flag |
| `SendPushInput` | Input for send-push! (user-id, locale) |
| `AnalyticsEvent` | Send/delivery/open event record |
| `PushStats` | Aggregated stats output (counts + rates) |
| `CallbackPayload` | Mobile app callback input (device-token, provider-message-id, event-type) |
| `LocalizedString` | Union type: plain string or locale->string map |
| `RetryConfig` | Retry configuration (max-attempts, backoff strategy) |

## Database Schema

### push_device_tokens

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| user_id | UUID | NOT NULL |
| token | VARCHAR(512) | NOT NULL |
| platform | VARCHAR(10) | 'fcm' or 'apns' |
| app_id | VARCHAR(255) | NOT NULL |
| device_name | VARCHAR(255) | Optional |
| os_version | VARCHAR(50) | Optional |
| active | BOOLEAN | Default TRUE, soft-deactivation |
| created_at | TIMESTAMP | |
| last_used_at | TIMESTAMP | |

Unique constraint on `(token, app_id)`. Indexes on `(user_id, active)` and `(platform, active)`.

### push_send_log

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| notification_id | VARCHAR(255) | defpush :id |
| user_id | UUID | Optional |
| device_token | VARCHAR(512) | |
| platform | VARCHAR(10) | |
| title | VARCHAR(500) | Rendered title |
| body | TEXT | Rendered body |
| priority | VARCHAR(10) | Default 'normal' |
| status | VARCHAR(20) | queued/sent/failed |
| provider_message_id | VARCHAR(255) | From FCM/APNs response |
| error_message | TEXT | On failure |
| created_at | TIMESTAMP | |
| sent_at | TIMESTAMP | |

Indexes on `(notification_id, created_at)` and `(user_id, created_at)`.

### push_analytics_events

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| notification_id | VARCHAR(255) | |
| device_token | VARCHAR(512) | |
| platform | VARCHAR(10) | |
| event_type | VARCHAR(20) | sent/delivered/opened/failed |
| user_id | UUID | Optional |
| provider_message_id | VARCHAR(255) | |
| error_message | TEXT | |
| timestamp | TIMESTAMP | |

Indexes on `(notification_id, event_type)` and `(timestamp)`.

## Delivery Flow

```
send-push! → enqueue :push/send job → job worker picks up
  → resolve defpush definition from registry
  → fetch user's active devices from store
  → group devices by platform
  → for each platform group:
      → build platform-specific payload (pure, in core/delivery.clj)
      → call IFCMProvider or IAPNsProvider
      → record-send! analytics event per device
      → mark-token-invalid! for rejected tokens
```

- `broadcast!` uses paginated device fetch to avoid OOM on large device sets
- `schedule-push!` is a delayed job — jobs module handles scheduling
- Retry handled by jobs module retry config + pure `retry-delay-ms` calculation in core

### Platform Payload Building (pure)

- `build-fcm-payload` — transforms rendered notification into FCM v1 API structure (token, notification, data, android config)
- `build-apns-payload` — transforms into APNs structure (aps alert, sound, badge, content-available, mutable-content)
- Both are pure functions, fully testable without I/O

### Invalid Token Feedback Loop

Provider returns "token invalid" error → `mark-token-invalid!` sets `active = false` → future sends skip that token. `cleanup-stale-tokens!` purges old inactive tokens periodically.

## HTTP Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/push/devices` | User | Register device token |
| GET | `/api/push/devices` | User | List user's devices |
| DELETE | `/api/push/devices/:token` | User | Unregister device |
| POST | `/api/push/callback` | None | Mobile app delivery/open callback |
| GET | `/api/push/stats/:notification-id` | Admin | Delivery/open rate stats |

Callback endpoint: no auth, identified by `device-token` + `provider-message-id`. Idempotent (duplicate callbacks ignored). Rate limiting recommended at middleware level.

## Integrant Configuration

### Dev/Test (mock providers)

```clojure
:boundary.push/fcm-provider  {:provider :mock}
:boundary.push/apns-provider {:provider :mock}
:boundary.push/device-store  {:db #ig/ref :boundary/datasource}
:boundary.push/analytics-store {:db #ig/ref :boundary/datasource}
:boundary.push/service
  {:device-store    #ig/ref :boundary.push/device-store
   :analytics-store #ig/ref :boundary.push/analytics-store
   :fcm-provider    #ig/ref :boundary.push/fcm-provider
   :apns-provider   #ig/ref :boundary.push/apns-provider
   :job-queue       #ig/ref :boundary.jobs/queue}
:boundary.push/job-handlers
  {:push-service    #ig/ref :boundary.push/service
   :device-store    #ig/ref :boundary.push/device-store
   :fcm-provider    #ig/ref :boundary.push/fcm-provider
   :apns-provider   #ig/ref :boundary.push/apns-provider
   :analytics-store #ig/ref :boundary.push/analytics-store
   :job-registry    #ig/ref :boundary.jobs/registry}
:boundary.push/routes
  {:device-store    #ig/ref :boundary.push/device-store
   :analytics-store #ig/ref :boundary.push/analytics-store}
```

### Production (real providers)

```clojure
:boundary.push/fcm-provider
  {:provider         :fcm
   :project-id       #env FIREBASE_PROJECT_ID
   :credentials-path #env GOOGLE_APPLICATION_CREDENTIALS}

:boundary.push/apns-provider
  {:provider   :apns
   :team-id    #env APNS_TEAM_ID
   :key-id     #env APNS_KEY_ID
   :key-path   #env APNS_KEY_PATH
   :bundle-id  #env APNS_BUNDLE_ID
   :sandbox?   false}
```

## Testing Strategy

| Layer | Metadata | What | How |
|-------|----------|------|-----|
| Unit | `^:unit` | Template rendering, payload building, retry calc, locale fallback, schema validation | Pure function tests, no mocks |
| Integration | `^:integration` | Full send flow, job handler execution, Ring handlers | Mock providers + in-memory stores |
| Contract | `^:contract` | Device CRUD, analytics queries, token lifecycle | next.jdbc against H2 |

```bash
clojure -M:test:db/h2 :push                              # All push tests
clojure -M:test:db/h2 :push --focus-meta :unit            # Unit only
clojure -M:test:db/h2 :push --focus-meta :contract        # Contract only
clojure -M:test:db/h2 --focus boundary.push.core.notification-test  # Single ns
```

Test isolation: `clear-registry!` in fixtures between `defpush` tests.
