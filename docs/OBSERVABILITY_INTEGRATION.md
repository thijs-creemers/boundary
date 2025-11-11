# Observability Integration Guide

The Boundary framework includes a comprehensive observability infrastructure with logging, metrics, and error reporting capabilities. This guide shows how feature modules can integrate with these observability components.

## Architecture Overview

The observability infrastructure follows the Functional Core/Imperative Shell pattern:

- **Core protocols** - Define interfaces for logging, metrics, and error reporting
- **Shell adapters** - Implement protocols for different providers (Datadog, Sentry, no-op, etc.)
- **System wiring** - Integrant-managed lifecycle and dependency injection
- **Feature integration** - Clean dependency injection into feature modules

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│    Feature      │    │   Observability  │    │    Adapters     │
│    Modules      │───▶│    Protocols     │◀───│  (Datadog,      │
│                 │    │                  │    │   Sentry, etc.) │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

## System Configuration

### 1. Configuration Structure

Add observability configurations to your `config.edn`:

```clojure
{:logging {:provider :no-op
           :level :info}
 :metrics {:provider :no-op
           :export-interval 60000}
 :error-reporting {:provider :no-op
                   :environment "development"}}
```

### 2. Component Wiring

The system automatically wires observability components via Integrant:

```clojure
;; In your Integrant config
{:boundary/logging {:provider :no-op}
 :boundary/metrics {:provider :no-op}  
 :boundary/error-reporting {:provider :no-op}
 
 ;; Feature modules receive observability via dependency injection
 :boundary/user-service {:user-repository (ig/ref :boundary/user-repository)
                         :logging (ig/ref :boundary/logging)
                         :metrics (ig/ref :boundary/metrics)
                         :error-reporting (ig/ref :boundary/error-reporting)}}
```

## Feature Module Integration

### 1. Service Layer Integration

Feature services receive observability components via dependency injection:

```clojure
(ns boundary.user.shell.service
  (:require [boundary.logging.ports :as logging]
            [boundary.metrics.ports :as metrics]
            [boundary.error-reporting.ports :as error-reporting]))

(defn create-user-service 
  [user-repository session-repository & {:keys [logging metrics error-reporting]}]
  (->UserService user-repository session-repository logging metrics error-reporting))

(defrecord UserService [user-repository session-repository logging metrics error-reporting]
  ports/IUserService
  
  (create-user [this user-data]
    ;; Log the operation
    (.info logging "Creating new user" {:user-id (:id user-data)})
    
    ;; Track metrics
    (let [counter (.register-counter! metrics :user.creation.attempts "User creation attempts" {})]
      (.inc-counter! metrics counter))
    
    (try
      ;; Business logic
      (let [result (user-repository/create! user-repository user-data)]
        ;; Track success
        (let [success-counter (.register-counter! metrics :user.creation.success "Successful user creations" {})]
          (.inc-counter! metrics success-counter))
        
        (.info logging "User created successfully" {:user-id (:id result)})
        result)
      
      (catch Exception e
        ;; Report error and track failure metrics
        (.capture-exception error-reporting e {:context "user-creation" :user-data user-data})
        
        (let [error-counter (.register-counter! metrics :user.creation.errors "User creation errors" {})]
          (.inc-counter! metrics error-counter))
        
        (.error logging "Failed to create user" {:error (.getMessage e) :user-data user-data})
        (throw e))))
```

### 2. HTTP Handler Integration

HTTP handlers can also receive observability components:

```clojure
(defn create-handler [user-service & {:keys [logging metrics error-reporting]}]
  (ring/ring-handler
    (ring/router
      [["/users"
        {:post {:handler (fn [request]
                          ;; Track HTTP metrics
                          (let [timer (.register-histogram! metrics 
                                                           :http.request.duration 
                                                           "HTTP request duration"
                                                           [0.1 0.5 1.0 2.0 5.0] 
                                                           {:endpoint "/users" :method "POST"})]
                            (.time-histogram! metrics timer 
                              (fn []
                                (try
                                  (let [result (user-service/create-user user-service (:body request))]
                                    {:status 201 :body result})
                                  
                                  (catch Exception e
                                    (.capture-exception error-reporting e {:context "http-handler"})
                                    {:status 500 :body {:error "Internal server error"}}))))))}}]])))
```

### 3. Repository Layer Integration

Repositories can track database performance metrics:

```clojure
(defrecord DatabaseUserRepository [db-context metrics]
  ports/IUserRepository
  
  (create! [this user-data]
    (let [timer (.register-histogram! metrics 
                                     :db.user.create.duration 
                                     "User creation query duration"
                                     [0.01 0.05 0.1 0.5 1.0] 
                                     {:table "users"})]
      (.time-histogram! metrics timer
        (fn []
          ;; Database operation
          (db/create-user! db-context user-data))))))
```

## Available Protocols

### Logging Protocol

```clojure
(require '[boundary.logging.ports :as logging])

;; Basic logging
(.info logger "Operation completed" {:user-id 123})
(.warn logger "Validation warning" {:field "email"})
(.error logger "Operation failed" {:error "Connection timeout"})

;; Structured context
(.with-context logger {:correlation-id "abc-123"}
  (fn []
    (.info logger "Processing request")))
```

### Metrics Protocol

```clojure
(require '[boundary.metrics.ports :as metrics])

;; Register metrics (typically done once at service initialization)
(let [counter (.register-counter! metrics :api.requests "API requests" {:service "user"})
      gauge (.register-gauge! metrics :active.connections "Active connections" {})
      histogram (.register-histogram! metrics :request.duration "Request duration" [0.1 0.5 1.0] {})]

  ;; Record values
  (.inc-counter! metrics counter)
  (.inc-counter! metrics counter 5)
  (.set-gauge! metrics gauge 42.0)
  (.observe-histogram! metrics histogram 0.234)
  
  ;; Time operations
  (.time-histogram! metrics histogram
    (fn [] 
      ;; Your operation here
      (Thread/sleep 100))))
```

### Error Reporting Protocol

```clojure
(require '[boundary.error-reporting.ports :as error-reporting])

;; Report exceptions
(.capture-exception error-reporter exception {:user-id 123 :action "create-user"})

;; Report messages
(.capture-message error-reporter "Validation failed" :warning {:field "email"})

;; Add context
(.with-context error-reporter {:correlation-id "abc-123"}
  (fn []
    ;; Any errors here will include the correlation ID
    (do-risky-operation)))

;; Add breadcrumbs for debugging
(.add-breadcrumb! error-reporter {:message "User validation started" 
                                  :category "validation" 
                                  :level :info})
```

## Provider Configuration

### No-Op Provider (Default)

Safe for development and testing - all operations are ignored:

```clojure
{:logging {:provider :no-op}
 :metrics {:provider :no-op}
 :error-reporting {:provider :no-op}}
```

### Production Providers

#### Datadog Logging (Available)

Complete Datadog logging integration with HTTP batch processing:

```clojure
{:logging {:provider :datadog 
           :api-key "your-32-character-api-key"
           :service "your-service-name"
           :host "intake.logs.datadoghq.com"  ; optional, defaults to US
           :batch-size 100                    ; optional
           :flush-interval 5000}}             ; optional, milliseconds
```

#### Future Providers

The system is designed to support additional providers:

```clojure
;; Metrics providers
{:metrics {:provider :prometheus :endpoint "http://localhost:9090"}}
{:metrics {:provider :datadog :api-key "your-key"}}  ; metrics adapter planned
{:metrics {:provider :cloudwatch :region "us-east-1"}}

;; Error reporting providers  
{:error-reporting {:provider :sentry :dsn "your-sentry-dsn"}}
{:error-reporting {:provider :rollbar :access-token "your-token"}}

;; Additional logging providers
{:logging {:provider :json :level :info}}
{:logging {:provider :elasticsearch :endpoint "http://localhost:9200"}}
```

## Testing Integration

### Unit Testing

Mock observability components in unit tests:

```clojure
(deftest test-user-creation
  (let [mock-logger (reify logging/ILogger 
                      (info [_ msg data] 
                        (println "LOG:" msg data)))
        mock-metrics (reify metrics/IMetricsRegistry
                       (register-counter! [_ name desc tags] ::counter)
                       metrics/IMetricsEmitter
                       (inc-counter! [_ handle] nil))
        service (create-user-service repo session-repo 
                                   :logging mock-logger 
                                   :metrics mock-metrics)]
    ;; Test service behavior
    (is (= expected-result (user-service/create-user service user-data)))))
```

### Integration Testing

Use no-op providers for integration tests:

```clojure
(deftest test-full-user-workflow
  (let [system (ig/init {:boundary/logging {:provider :no-op}
                         :boundary/metrics {:provider :no-op}
                         :boundary/error-reporting {:provider :no-op}
                         ;; ... other components
                         })]
    ;; Test complete workflows
    (is (= 201 (:status (handler test-request))))
    (ig/halt! system)))
```

## Best Practices

1. **Lazy Registration**: Register metrics once during service initialization, not on every operation
2. **Structured Logging**: Use maps for log data rather than string interpolation
3. **Error Context**: Include relevant context when reporting errors (user-id, correlation-id, etc.)
4. **Metric Naming**: Use hierarchical naming (e.g., `user.creation.success`, `http.request.duration`)
5. **Resource Cleanup**: Let Integrant handle component lifecycle - don't manually close connections
6. **Performance**: No-op providers have minimal overhead, but still avoid excessive metric registration

## Example: Complete Feature Integration

See `src/boundary/user/shell/service.clj` for a complete working example of observability integration in a feature module.

The key principles:
- Dependency injection of observability components
- Protocol-based abstractions for testability  
- Lazy metric registration for performance
- Structured context for debugging
- Clean separation between business logic and observability concerns