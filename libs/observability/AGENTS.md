# Observability Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Multi-Layer Interceptor Pattern

Automatic logging, metrics, and error reporting without boilerplate.

```clojure
;; Service layer - use interceptor
(defn create-user [this user-data]
  (service-interceptors/execute-service-operation
   :create-user
   {:user-data user-data}
   (fn [{:keys [params]}]
     ;; Business logic here - observability automatic
     (let [user (user-core/prepare-user (:user-data params))]
       (.create-user repository user)))))

;; Persistence layer - use interceptor
(defn find-user-by-email [this email]
  (persistence-interceptors/execute-persistence-operation
   logger error-reporter
   "find-user-by-email"
   {:email email}
   (fn []
     ;; Database query here
     (jdbc/execute-one! ctx query))))

;; Result: Automatic breadcrumbs, error reporting, logging, metrics
```

### Benefits
- 31/31 methods converted in user module
- ~50% code reduction
- Consistent error handling and logging
- Automatic metrics collection

### Providers
- No-op (development)
- Datadog
- Sentry

## Testing

```bash
clojure -M:test:db/h2 :observability
```
