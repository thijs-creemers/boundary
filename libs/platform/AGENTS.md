# Platform Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## HTTP Interceptors

**Declarative cross-cutting concerns** (auth, rate limiting, audit):

```clojure
;; Define interceptor
(def require-admin
  {:name :require-admin
   :enter (fn [ctx]
            (if (admin? (get-in ctx [:request :session :user]))
              ctx
              (assoc ctx :response {:status 403 :body {:error "Forbidden"}})))
   :leave (fn [ctx]
            ;; Response processing (optional)
            ctx)
   :error (fn [ctx error]
            ;; Exception handling (optional)
            (assoc ctx :response {:status 500 :body {:error "Internal error"}}))})

;; Normalized route format with interceptors
[{:path "/api/admin"
  :methods {:post {:handler 'handlers/create-resource
                   :interceptors ['auth/require-admin
                                  'audit/log-action
                                  'rate-limit/admin-limit]
                   :summary "Create admin resource"}}}]
```

### Interceptor Phases
- `:enter` - Request processing (auth, validation, transformation)
- `:leave` - Response processing (audit, metrics, transformation)
- `:error` - Exception handling (custom error responses)

### Built-in Interceptors
Request logging, metrics, error reporting, correlation IDs

## Testing

```bash
clojure -M:test:db/h2 :platform
```
