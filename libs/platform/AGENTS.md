# Platform Library — Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

Infrastructure layer for HTTP routing/interceptors, database integration, CLI/runtime wiring, and shared platform adapters used by feature modules.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.platform.shell.http.interceptors` | HTTP-specific interceptor pipeline execution |
| `boundary.platform.shell.interceptors` | Universal cross-cutting interceptors (logging, metrics, error) |
| `boundary.platform.shell.interfaces.http.routes` | Reitit router and Ring handler creation |
| `boundary.platform.ports.http` | HTTP router and server protocols |
| `boundary.platform.db.*` | Database context, connection pooling, shared persistence utilities |

---

## HTTP Interceptor Architecture

### Interceptor Shape

Every interceptor is a map with three optional phase functions:

```clojure
{:name   :my-interceptor
 :enter  (fn [context] ...)  ; Process request, modify context
 :leave  (fn [context] ...)  ; Process response, modify context
 :error  (fn [context] ...)} ; Handle exceptions, produce safe response
```

### HTTP Context Model

The context map threaded through all interceptors:

```clojure
{:request        ; Ring request map
 :response       ; Ring response map (built up by handlers/interceptors)
 :route          ; Route metadata from Reitit match
 :path-params    ; Extracted path parameters
 :query-params   ; Extracted query parameters
 :system         ; Observability services {:logger :metrics-emitter :error-reporter}
 :attrs          ; Additional attributes set by interceptors
 :correlation-id ; Unique request ID
 :started-at}    ; Request start timestamp (for latency metrics)
```

### Built-in HTTP Interceptors

From `boundary.platform.shell.http.interceptors`:

| Interceptor | Phase | What it does |
|-------------|-------|--------------|
| `http-request-logging` | enter + leave | Logs request entry and completion with timing |
| `http-request-metrics` | enter + leave | Collects timing and status code metrics |
| `http-error-reporting` | error | Captures exceptions to error tracking service |
| `http-correlation-header` | leave | Adds correlation ID to response headers |
| `http-error-handler` | error | Converts `:type` in ex-data to HTTP status codes |
| `http-security-headers` | leave | Adds CSP, HSTS, X-Frame-Options, etc. |
| `http-csrf-protection` | enter | Validates CSRF tokens on state-changing requests |
| `http-rate-limit(limit, window-ms, cache?)` | enter | Fixed-window rate limiting (Redis or in-process) |

### Universal Cross-Cutting Interceptors

From `boundary.platform.shell.interceptors`:

| Interceptor | Purpose |
|-------------|---------|
| `context-interceptor` | Adds correlation-id, now timestamp, ensures `:op` present |
| `logging-start` / `logging-complete` / `logging-error` | Operation logging with timing |
| `metrics-start` / `metrics-complete` / `metrics-error` | Attempts, success, latency metrics |
| `error-capture` | Captures exceptions in error reporting system |
| `error-normalize` | Maps error types to HTTP status codes |
| `error-response-converter` | Converts failed contexts into HTTP error responses |
| `effects-dispatch` | Executes side effects from core function results |
| `response-shape-http` | Shapes results into HTTP responses (201 for creates) |
| `response-shape-cli` | Shapes results into CLI format with exit codes |

### Pipeline Templates

```clojure
;; Pre-built pipelines for common scenarios
base-observability-pipeline   ; context, logging, metrics, error-capture
error-handling-pipeline       ; error-normalize, error-response-converter
http-response-pipeline        ; error-handling + response-shape-http
cli-response-pipeline         ; error-handling + response-shape-cli

;; Create custom pipelines
(create-http-pipeline & custom-interceptors)   ; Complete HTTP pipeline
(create-cli-pipeline & custom-interceptors)    ; Complete CLI pipeline
(add-error-handling pipeline)                  ; Add error handling to any pipeline
```

---

## Route Configuration

### Route Specification Format

```clojure
{:path    "/api/users"
 :methods {:get  {:handler  'boundary.user.shell.http/list-users-handler
                  :summary  "List users"
                  :tags     ["users"]
                  :parameters {:query [:map [:limit {:optional true} :int]]}}
           :post {:handler  'boundary.user.shell.http/create-user-handler
                  :summary  "Create user"
                  :tags     ["users"]
                  :coercion {:body CreateUserRequest}}}}
```

### Creating a Router

```clojure
(require '[boundary.platform.shell.interfaces.http.routes :as routes])

;; Creates complete Reitit router with health, api-docs, and your routes
(def router
  (routes/create-router config module-routes {:api-prefix "/api/v1"}))

;; Creates Ring handler from router
(def handler
  (routes/create-handler router {}))

;; Complete app in one call
(def app
  (routes/create-app config module-routes {:api-prefix "/api/v1"}))
```

### Per-Route Interceptors

```clojure
;; Define a custom interceptor
(def require-admin
  {:name :require-admin
   :enter (fn [ctx]
            (if (admin? (get-in ctx [:request :session :user]))
              ctx
              (assoc ctx :response {:status 403 :body {:error "Forbidden"}})))
   :leave (fn [ctx] ctx)
   :error (fn [ctx _error]
            (assoc ctx :response {:status 500 :body {:error "Internal error"}}))})

;; Apply to specific routes
[{:path    "/api/admin"
  :methods {:post {:handler     'handlers/create-resource
                   :interceptors [require-admin
                                  'audit/log-action
                                  (http-rate-limit 10 60000 cache)]
                   :summary     "Create admin resource"}}}]
```

---

## Database Integration

The platform library provides database context and connection pooling:

```clojure
;; Integrant key: :boundary/db-context
;; Config in resources/conf/{env}/config.edn
{:boundary/db-context
 {:jdbc-url #env ["DATABASE_URL" "jdbc:h2:mem:dev;DB_CLOSE_DELAY=-1"]}}
```

The `db-context` map is injected into services via Integrant:

```clojure
;; In your module's shell/service.clj
(defrecord MyService [db-context user-repo])

;; db-context has :datasource key for next.jdbc
(next.jdbc/execute! (:datasource db-context) ["SELECT * FROM my_table"])
```

---

## CLI Wiring

The platform library manages CLI entry and system wiring via Integrant keys:

```clojure
;; Start the system (use in REPL)
(require '[integrant.repl :as ig-repl])
(ig-repl/go)     ; Start
(ig-repl/reset)  ; Reload and restart
(ig-repl/halt)   ; Stop
```

---

## Error Type → HTTP Status Mapping

Exceptions thrown with `:type` in `ex-data` are automatically converted:

| `:type` | HTTP Status |
|---------|------------|
| `:validation-error` | 400 |
| `:unauthorized` | 401 |
| `:forbidden` | 403 |
| `:not-found` | 404 |
| `:conflict` | 409 |
| `:internal-error` | 500 |

```clojure
;; Always include :type in ex-info calls
(throw (ex-info "User not found"
                {:type :not-found
                 :user-id id}))
```

---

## Rate Limiting

```clojure
;; With Redis cache (distributed, recommended for production)
(http-rate-limit 100 60000 cache-service)   ; 100 req/min per IP

;; Without cache (in-process fallback, single-node only)
(http-rate-limit 100 60000)

;; Returns 429 Too Many Requests when limit exceeded
;; Response includes Retry-After header
```

---

## Gotchas

- Ensure interceptor response structures match HTMX target contracts for fragment endpoints.
- Keep exceptions typed (`:type` in `ex-data`) so HTTP error mapping stays deterministic.
- Static resource routes bypass content negotiation — serve from `/public` directory.
- Rate limiting without Redis is per-process only and resets on restart.

---

## Testing

```bash
clojure -M:test:db/h2 :platform
clojure -M:test:db/h2 :platform --focus-meta :unit
clojure -M:test:db/h2 :platform --focus-meta :integration
```

---

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
- [Architecture Overview](../../docs-site/content/architecture/overview.adoc)
- [Middleware Architecture](../../docs-site/content/architecture/middleware-architecture.adoc)
