# Observability Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Cross-cutting observability for services and persistence layers: structured logging, metrics, tracing breadcrumbs, and error reporting — without polluting core business logic.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.observability.logging.ports` | `ILogger`, `IAuditLogger` protocols |
| `boundary.observability.errors.ports` | `IErrorReporter`, `IErrorContext`, `IErrorFilter` protocols |
| `boundary.observability.metrics.ports` | `IMetricsRegistry`, `IMetricsEmitter` protocols |
| `boundary.observability.shell.service-interceptors` | Service-layer operation wrappers |
| `boundary.observability.shell.persistence-interceptors` | Persistence-layer query wrappers |
| `boundary.observability.shell.adapters.*` | Provider implementations (no-op, Datadog, Sentry) |

---

## Multi-Layer Interceptor Pattern

The recommended way to add observability is to wrap operations with interceptors. This gives automatic logging, metrics, error reporting, and breadcrumbs with no per-call boilerplate.

### Service Layer

```clojure
(require '[boundary.observability.shell.service-interceptors :as service-interceptors])

;; Wrap each service method call
(defn create-user [this user-data]
  (service-interceptors/execute-service-operation
   :create-user
   {:user-data user-data}
   (fn [{:keys [params]}]
     ;; Business logic here — observability is automatic
     (let [user (user-core/prepare-user (:user-data params))]
       (ports/save-user (:repository this) user)))))
```

The interceptor automatically:
- Logs operation start, success, and failure
- Records latency metrics
- Captures exceptions to the error reporter
- Adds breadcrumbs for distributed tracing

### Persistence Layer

```clojure
(require '[boundary.observability.shell.persistence-interceptors :as persistence-interceptors])

;; Wrap each database call
(defn find-user-by-email [this email]
  (persistence-interceptors/execute-persistence-operation
   (:logger this) (:error-reporter this)
   "find-user-by-email"
   {:email email}
   (fn []
     ;; Database query here
     (jdbc/execute-one! (:datasource (:ctx this)) query))))
```

---

## Protocols Reference

### ILogger

```clojure
;; In boundary.observability.logging.ports
(defprotocol ILogger
  (log* [this level message context exception])
  (trace [this message] [this message context])
  (debug [this message] [this message context])
  (info  [this message] [this message context])
  (warn  [this message] [this message context] [this message context exception])
  (error [this message] [this message context] [this message context exception])
  (fatal [this message] [this message context] [this message context exception]))
```

#### Log Context Map

```clojure
{:correlation-id string  ; Request correlation ID (auto-injected by HTTP pipeline)
 :request-id    string   ; HTTP request ID
 :tenant-id     string   ; Multi-tenant context
 :user-id       string   ; Authenticated user (if available)
 :span-id       string   ; Distributed tracing span ID
 :trace-id      string   ; Distributed tracing trace ID
 :tags          map}     ; Additional structured tags
```

### IAuditLogger

```clojure
(defprotocol IAuditLogger
  (audit-event [this event-type actor resource action result context])
  (security-event [this event-type severity details context]))
```

### IErrorReporter

```clojure
(defprotocol IErrorReporter
  (capture-exception [this exception] [this exception context] [this exception context tags])
  (capture-message   [this message level] [this message level context] [this message level context tags])
  (capture-event     [this event-map]))
```

### IErrorContext

```clojure
(defprotocol IErrorContext
  (with-context    [this context-map f])
  (add-breadcrumb! [this breadcrumb])
  (clear-breadcrumbs! [this])
  (set-user!       [this user-info])
  (set-tags!       [this tags])
  (set-extra!      [this extra])
  (current-context [this]))
```

### IMetricsEmitter

```clojure
(defprotocol IMetricsEmitter
  (inc-counter!      [this metric-handle] [this metric-handle value] [this metric-handle value tags])
  (set-gauge!        [this metric-handle value] [this metric-handle value tags])
  (observe-histogram![this metric-handle value] [this metric-handle value tags])
  (time-histogram!   [this metric-handle f] [this metric-handle tags f])
  (time-summary!     [this metric-handle f] [this metric-handle tags f]))
```

---

## Provider Configuration

### no-op (Development and Tests)

The default provider — all operations are silent. No configuration required.

```clojure
;; resources/conf/dev/config.edn
{:boundary/observability
 {:logger         {:type :no-op}
  :error-reporter {:type :no-op}
  :metrics        {:type :no-op}}}
```

### Datadog

```clojure
;; resources/conf/prod/config.edn
{:boundary/observability
 {:metrics {:type    :datadog
            :api-key #env DATADOG_API_KEY
            :host    #env ["DATADOG_HOST" "datadoghq.com"]
            :tags    {:env #env BND_ENV
                      :service "boundary"}}}}
```

### Sentry

```clojure
;; resources/conf/prod/config.edn
{:boundary/observability
 {:error-reporter {:type  :sentry
                   :dsn   #env SENTRY_DSN
                   :env   #env BND_ENV
                   :release #env APP_VERSION}}}
```

---

## Writing a Custom Adapter

Implement the protocols for your observability provider:

```clojure
(ns my-app.observability.adapters.my-logger
  (:require [boundary.observability.logging.ports :as ports]))

(defrecord MyLogger [config]
  ports/ILogger
  (log* [this level message context exception]
    ;; Send to your logging backend
    (my-backend/log {:level   level
                     :message message
                     :context context
                     :error   exception}))
  (info [this message]
    (log* this :info message {} nil))
  (info [this message context]
    (log* this :info message context nil))
  ;; ... implement remaining methods
  )

(defn create-my-logger [config]
  (->MyLogger config))
```

Register it in your Integrant config:

```clojure
;; In your system config
{:boundary/observability {:logger (my-app.observability.adapters.my-logger/create-my-logger
                                   {:endpoint "..."})}}}
```

---

## Where to Place Interceptors

**Rule**: Wrap at the service boundary (one level above the database) and the persistence boundary (one level above the SQL/driver call). Never wrap inside core functions.

```
HTTP handler
  └── service interceptor   ← service-interceptors/execute-service-operation
       └── core functions    ← no observability here
       └── persistence
            └── persistence interceptor  ← persistence-interceptors/execute-persistence-operation
                 └── JDBC/SQL call
```

---

## Gotchas

- Wrap operations at boundaries only — avoid duplicating interceptor execution inside already-instrumented paths.
- Keep PII out of logs and breadcrumbs — sanitize before passing `params` to interceptors.
- No-op adapters are used in tests automatically; never hardcode provider-specific calls in core.
- `add-breadcrumb!` is thread-local in most implementations — don't share error context across threads.

---

## Testing

```bash
clojure -M:test:db/h2 :observability
clojure -M:test:db/h2 :observability --focus-meta :unit
```

---

## Links

- [Library README](README.md)
- [Observability Integration Guide](../../docs-site/content/architecture/observability-integration.adoc)
- [Root AGENTS Guide](../../AGENTS.md)
