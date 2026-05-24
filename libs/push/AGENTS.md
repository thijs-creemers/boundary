# boundary-push — Push Notification Library

Multi-platform push notification delivery for FCM (Firebase) and APNs (Apple). Device token management, job-based async delivery, HMAC-secured analytics callbacks. Uses `defpush` macro for notification definitions.

## Key Namespaces

| Namespace | Layer | Purpose |
|-----------|-------|---------|
| `boundary.push.core.notification` | Core | `defpush` macro, registry, template rendering |
| `boundary.push.core.delivery` | Core | Payload building (FCM/APNs), error classification, retry calc |
| `boundary.push.core.device` | Core | Token validation, platform detection, staleness |
| `boundary.push.core.analytics` | Core | Rate calculations |
| `boundary.push.ports` | Ports | IPushService, IFCMProvider, IAPNsProvider, IDeviceTokenStore, IPushAnalyticsStore |
| `boundary.push.schema` | Schema | Malli schemas for all domain types |
| `boundary.push.shell.service` | Shell | IPushService impl, HMAC generation/verification |
| `boundary.push.shell.persistence` | Shell | next.jdbc/HoneySQL stores for devices + analytics |
| `boundary.push.shell.adapters.mock` | Shell | Mock FCM + APNs (dev/test) |
| `boundary.push.shell.adapters.fcm` | Shell | Google FCM v1 API adapter |
| `boundary.push.shell.adapters.apns` | Shell | Apple APNs HTTP/2 adapter |
| `boundary.push.shell.handlers` | Shell | Ring handlers for device CRUD + callback |
| `boundary.push.shell.jobs` | Shell | Job handlers for async delivery |
| `boundary.push.shell.module-wiring` | Shell | Integrant lifecycle |

## Protocol: IFCMProvider

```clojure
(fcm-send! [this payload])           ;; Single send, returns {:success? :message-id :error}
(fcm-send-multicast! [this payload tokens])  ;; Batch send
(fcm-validate-token [this token])    ;; Dry-run validation
```

## Protocol: IAPNsProvider

```clojure
(apns-send! [this payload device-token])    ;; Single send
(apns-send-batch! [this payload tokens])    ;; Sequential batch
```

Note: APNs has no dry-run validation equivalent. Token validity discovered at send time.

## Notification Definition

```clojure
(defpush order-shipped
  {:id           :order-shipped
   :title        {:en "Order Shipped" :nl "Bestelling Verzonden"}
   :body         {:en "Your order {{order-id}} is on its way!"}
   :channels     #{:fcm :apns}
   :priority     :high
   :ttl          3600
   :deep-link    "/orders/{{order-id}}"
   :silent?      false
   :collapse-key :order-status
   :retry        {:max-attempts 3 :backoff :exponential}})
```

## Sending

```clojure
;; Direct (via job queue)
(push/send-push! push-service :order-shipped
  {:order-id "ORD-123"} {:user-id user-id :locale :nl})

;; Scheduled
(push/schedule-push! push-service :appointment-reminder
  {:time "14:00"} {:user-id user-id} future-instant)

;; Broadcast
(push/broadcast! push-service :maintenance-alert
  {:message "Downtime at 2am"} {:platform :fcm})
```

## Error Classification

| Category | Action |
|----------|--------|
| `:retryable` | Re-enqueue with backoff |
| `:token-invalid` | Deactivate token, don't retry |
| `:rate-limited` | Re-enqueue with longer backoff |
| `:permanent` | Log, don't retry |

## Gotchas

1. **FCM tokens rotate** — always handle `UNREGISTERED` by marking token invalid
2. **APNs sandbox vs production** — different hosts, set `sandbox?` correctly per environment
3. **Callback HMAC** — mobile apps must send back `callback-token` from push data payload
4. **Template variables** — `{{var}}` syntax, missing vars left as-is (not stripped)
5. **Locale fallback** — requested → `:en` → first available
6. **push_send_log is write-once** — post-send states (delivered/opened) only in analytics events table

## Testing

```bash
clojure -M:test:db/h2 :push                              # All
clojure -M:test:db/h2 :push --focus-meta :unit            # Unit
clojure -M:test:db/h2 :push --focus-meta :contract        # Contract (H2)
clojure -M:test:db/h2 :push --focus-meta :integration     # Integration
```
