# Interceptor-Based Observability Pattern for Boundary Framework

## Overview

**Problem:** Shell layer functions in every module are cluttered with repetitive logging, metrics, and error reporting calls, making the actual business orchestration logic hard to read and maintain.

**Solution:** Implement a lightweight interceptor pipeline pattern that handles cross-cutting observability concerns declaratively, allowing shell functions to focus on domain orchestration.

**Goals:**
- Reduce cognitive load for developers
- Eliminate repetitive observability boilerplate
- Maintain observability fidelity (context, correlation IDs, error surfaces)
- Preserve FC/IS purity (core remains uninvolved in infrastructure)
- Keep testability high (unit tests shouldn't require observability libs)

## Why Interceptors Over Wrappers

| Aspect | Interceptors | Wrappers |
|--------|-------------|----------|
| **Granularity** | Per-stage timing/metrics | Whole-call timing only |
| **Extensibility** | Add new concerns = add interceptor | Requires wrapper modification |
| **Error Handling** | Unified error pipeline | Per-wrapper try/catch |
| **Configuration** | Pipeline assembly from registry | Per-wrap options |
| **Multi-step Flows** | Natural fit for HTTP/CLI lifecycles | Better for single functions |
| **Cognitive Load** | Higher initial learning, lower ongoing | Lower initial, potential accumulation |

**Decision:** Interceptors win for our use case due to multi-step request lifecycles and expected growth in cross-cutting concerns.

## Context Schema

The `ctx` map is threaded through the entire pipeline:

```clojure
{:request          ; Raw HTTP request or CLI args
 :op               ; Operation keyword (:user/create, :billing/invoice, etc.)
 :system           ; Dependency injection map
 :correlation-id   ; Request correlation ID
 :tenant           ; Tenant context (future)
 :now              ; Request timestamp
 :validated        ; Validated/coerced input data
 :result           ; Core function result
 :effects          ; Side effects to execute
 :effect-errors    ; Side effect execution errors
 :errors           ; Validation/domain errors
 :exception        ; Caught exception (error path)
 :response         ; Final response (HTTP/CLI)
 :timing           ; Performance measurements
 :halt?            ; Short-circuit flag
}
```

### System Dependencies

```clojure
{:logger           ; Logging port implementation
 :metrics          ; Metrics port implementation  
 :error-reporter   ; Error reporting port implementation
 :user-repository    ; Domain-specific adapters
 :notification-service
 :config           ; Configuration service
}
```

## Core Interceptor Set

### Universal Interceptors (All Operations)

#### 1. Context Establishment
```clojure
(def context-interceptor
  {:name :context
   :enter (fn [ctx]
            (-> ctx
                (assoc :correlation-id (or (get-in ctx [:request :headers "x-correlation-id"])
                                           (str (java.util.UUID/randomUUID))))
                (assoc :now (java.time.Instant/now))))})
```

#### 2. Logging Start
```clojure
(def logging-start
  {:name :logging-start
   :enter (fn [{:keys [logger op correlation-id] :as ctx}]
            (logging/info logger "start" {:op op :corr correlation-id})
            ctx)})
```

#### 3. Metrics Start
```clojure
(def metrics-start
  {:name :metrics-start
   :enter (fn [ctx]
            (metrics/increment (get-in ctx [:system :metrics]) (str (:op ctx) ".attempt"))
            (assoc-in ctx [:timing :start] (System/nanoTime)))})
```

#### 4. Logging Complete
```clojure
(def logging-complete
  {:name :logging-complete
   :leave (fn [ctx]
            (let [logger (get-in ctx [:system :logger])
                  ok? (= :success (get-in ctx [:result :status]))
                  ms (when-let [start (get-in ctx [:timing :start])]
                       (/ (- (System/nanoTime) start) 1e6))]
              (logging/info logger (if ok? "success" "completed-with-errors")
                            {:op (:op ctx)
                             :corr (:correlation-id ctx)
                             :ms ms
                             :effect-errors (count (:effect-errors ctx))})
              ctx))})
```

#### 5. Metrics Complete
```clojure
(def metrics-complete
  {:name :metrics-complete
   :leave (fn [ctx]
            (let [m (get-in ctx [:system :metrics])
                  op (:op ctx)
                  start (get-in ctx [:timing :start])
                  dur-ms (when start (/ (- (System/nanoTime) start) 1e6))
                  status (get-in ctx [:result :status])]
              (metrics/observe m (str op ".latency.ms") dur-ms)
              (metrics/increment m (str op (if (= status :success) ".success" ".error")))
              ctx))})
```

### Error Handling Interceptors

#### 6. Error Capture
```clojure
(def error-capture
  {:name :error-capture
   :error (fn [ctx]
            (error-reporting/capture-exception (get-in ctx [:system :error-reporter])
                                               (:exception ctx)
                                               {:op (:op ctx) :corr (:correlation-id ctx)})
            ctx)})
```

#### 7. Error Normalization
```clojure
(def error-normalize
  {:name :error-normalize
   :error (fn [ctx]
            (assoc ctx :response {:status 500
                                  :body {:type "internal"
                                         :title "Unexpected Error"
                                         :correlationId (:correlation-id ctx)}}))})
```

#### 8. Error Logging
```clojure
(def logging-error
  {:name :logging-error
   :error (fn [ctx]
            (logging/error (get-in ctx [:system :logger]) "failure"
                          {:op (:op ctx)
                           :corr (:correlation-id ctx)
                           :ex (ex-message (:exception ctx))})
            ctx)})
```

### Domain-Specific Interceptors

#### 9. Validation (User Create Example)
```clojure
(def validation-user-create
  {:name :validation
   :enter (fn [ctx]
            (let [data (get-in ctx [:request :body])
                  vres (validation/validate-request user-schema/CreateUserRequest data :http)]
              (if (= :success (:status vres))
                (assoc ctx :validated (:data vres))
                (-> ctx
                    (assoc :halt? true
                           :errors (:errors vres)
                           :response {:status 400
                                      :body {:type "validation" :errors (:errors vres)}})))))})
```

#### 10. Core Invocation
```clojure
(def core-user-create
  {:name :core
   :enter (fn [ctx]
            (let [ports {:user-repository (get-in ctx [:system :user-repository])
                         :notification-service (get-in ctx [:system :notification-service])}
                  res (user-core/create-new-user (:validated ctx) ports)]
              (assoc ctx :result res)))})
```

#### 11. Effects Dispatch
```clojure
(def effects-dispatch
  {:name :effects
   :enter (fn [ctx]
            (reduce
             (fn [c effect]
               (try
                 (case (:type effect)
                   :persist-user (persist! (get-in c [:system :user-repository]) (:user effect))
                   :send-welcome-email (send-welcome! (get-in c [:system :notification-service]) (:email effect))
                   nil)
                 (catch Throwable e
                   (update c :effect-errors (fnil conj []) {:effect effect :ex (ex-message e)}))))
             (assoc ctx :effect-errors [])
             (get-in ctx [:result :effects])))})
```

#### 12. Response Shaping (HTTP)
```clojure
(def response-shape-http
  {:name :response
   :leave (fn [ctx]
            (if (= :success (get-in ctx [:result :status]))
              (assoc ctx :response {:status 201
                                    :body (user->api (get-in ctx [:result :data]))})
              (if (:response ctx)
                ctx
                (assoc ctx :response {:status 400
                                      :body {:type "domain-error"
                                             :errors (get-in ctx [:result :errors])}}))))})
```

## Pipeline Runner

### Minimal Implementation
```clojure
(defn run-pipeline [initial-ctx interceptors]
  (let [stack (atom [])]
    (try
      (loop [ctx initial-ctx remaining interceptors]
        (if (or (empty? remaining) (:halt? ctx))
          ;; unwind (:leave functions)
          (reduce (fn [c {:keys [leave]}] (if leave (leave c) c))
                  ctx (reverse @stack))
          (let [{:keys [enter]} (first remaining)]
            (let [new-ctx (if enter (enter ctx) ctx)]
              (swap! stack conj (first remaining))
              (recur new-ctx (rest remaining)))))
      (catch Throwable t
        ;; error path
        (let [err-ctx (assoc initial-ctx :exception t)]
          (-> err-ctx
              (run-error-interceptors @stack)))))))

(defn run-error-interceptors [ctx stack]
  (reduce (fn [c {:keys [error]}] (if error (error c) c))
          ctx
          (reverse stack)))
```

### Enhanced Runner (Future)
- Short-circuit optimization
- Error interceptor filtering  
- Conditional interceptor execution
- Performance instrumentation

## Pipeline Assembly

### User Create HTTP Pipeline
```clojure
(def user-create-http-pipeline
  [context-interceptor
   logging-start
   metrics-start
   validation-user-create
   ;; auth-basic (future)
   core-user-create
   effects-dispatch
   logging-complete
   metrics-complete
   response-shape-http])
```

### Usage in Handler
```clojure
(defn handle-user-create [request system]
  (let [ctx {:request request
             :system system
             :op :user/create}]
    (:response (run-pipeline ctx user-create-http-pipeline))))
```

### CLI Variant
```clojure
(def user-create-cli-pipeline
  [context-interceptor
   logging-start
   metrics-start
   validation-user-create-cli  ; CLI-specific validation
   core-user-create            ; Same core logic
   effects-dispatch            ; Same effects
   logging-complete
   metrics-complete
   response-shape-cli])        ; CLI-specific response
```

## Execution Flow

### Happy Path
1. **Context** → Establish correlation ID, timestamp
2. **Logging Start** → Log operation start
3. **Metrics Start** → Increment attempt counter, start timer
4. **Validation** → Validate input, halt if invalid
5. **Core** → Execute pure business logic
6. **Effects** → Execute side effects (persist, notify)
7. **Logging Complete** → Log success with duration
8. **Metrics Complete** → Record success + latency
9. **Response** → Shape final output

### Error Path (Exception)
1. **Error Capture** → Send to error reporting service
2. **Error Logging** → Log structured error
3. **Error Normalize** → Convert to standard error response
4. **Response** → Shape error output
5. **Leave Functions** → Unwind any successful interceptors

### Validation Failure Path
1. **Validation** → Sets `:halt? true`, partial response
2. Skip core/effects (short-circuit)
3. **Logging Complete** → Log validation failure
4. **Metrics Complete** → Record validation error
5. **Response** → Return validation error response

## Migration Strategy

### Phase 1: Foundation (Week 1)
- [ ] Implement basic pipeline runner
- [ ] Create core universal interceptors
- [ ] Define context schema and conventions
- [ ] Write comprehensive tests for runner + interceptors

### Phase 2: User Module (Week 2)
- [ ] Identify highest-duplication user shell functions
- [ ] Create user-specific interceptors (validation, core, effects)
- [ ] Migrate user creation (HTTP + CLI)
- [ ] Remove redundant observability code
- [ ] Verify observability fidelity maintained

### Phase 3: Expand User Operations (Week 3)
- [ ] Migrate user update/delete operations
- [ ] Create reusable interceptor variants
- [ ] Document interceptor patterns
- [ ] Performance baseline + optimization

### Phase 4: Template for Other Modules (Week 4)
- [ ] Extract common patterns into shared interceptors
- [ ] Create billing module interceptors
- [ ] Migrate 1-2 billing operations
- [ ] Document module migration guide

### Phase 5: Advanced Features (Week 5+)
- [ ] Authorization interceptor
- [ ] Tracing integration
- [ ] Feature flag interceptor
- [ ] Rate limiting interceptor
- [ ] Operation registry for configuration

## Testing Strategy

### Unit Tests (Interceptors)
```clojure
(deftest context-interceptor-test
  (testing "adds correlation ID and timestamp"
    (let [ctx {:request {:headers {}}}
          result ((:enter context-interceptor) ctx)]
      (is (string? (:correlation-id result)))
      (is (instance? java.time.Instant (:now result))))))

(deftest validation-interceptor-test
  (testing "valid input passes through"
    (let [ctx {:request {:body {:email "test@example.com" :name "Test"}}}
          result ((:enter validation-user-create) ctx)]
      (is (contains? result :validated))
      (is (not (:halt? result)))))
  
  (testing "invalid input halts pipeline"
    (let [ctx {:request {:body {:email "invalid"}}}
          result ((:enter validation-user-create) ctx)]
      (is (:halt? result))
      (is (contains? result :errors)))))
```

### Integration Tests (Pipeline)
```clojure
(deftest user-create-pipeline-test
  (testing "successful user creation"
    (let [system {:logger (mock-logger)
                  :metrics (mock-metrics)
                  :user-repository (mock-user-repo)}
          ctx {:request {:body valid-user-data}
               :system system
               :op :user/create}
          result (run-pipeline ctx user-create-http-pipeline)]
      (is (= 201 (get-in result [:response :status])))
      (verify-metric-incremented system "user/create.attempt")
      (verify-metric-incremented system "user/create.success")
      (verify-log-written system "start" "success"))))
```

### Error Path Tests
```clojure
(deftest pipeline-error-handling-test
  (testing "exception in core function"
    (let [system {:user-repository (throwing-repo)}
          ctx {:request {:body valid-user-data} :system system :op :user/create}
          result (run-pipeline ctx user-create-http-pipeline)]
      (is (= 500 (get-in result [:response :status])))
      (verify-error-captured system)
      (verify-metric-incremented system "user/create.error"))))
```

## Performance Considerations

### Overhead Analysis
- **Interceptor dispatch:** ~10-50 nanoseconds per interceptor
- **Context map operations:** ~100-500 nanoseconds per assoc/update  
- **Total pipeline overhead:** ~1-5 microseconds (negligible vs I/O)

### Optimization Opportunities
- Pre-compile interceptor chains
- Use transients for context mutations
- Lazy evaluation of expensive metrics
- Conditional interceptor execution based on feature flags

## Configuration Integration

### Operation Registry (Future)
```clojure
{:user/create {:metrics {:enabled? true
                        :sample-rate 1.0}
               :logging {:level :info
                        :include-body? false}
               :validation {:strict-mode? true}
               :auth {:required? false}}
 :billing/process-payment {:metrics {:enabled? true
                                    :sample-rate 0.1}  ; Sample for high volume
                           :logging {:level :debug
                                    :include-body? true}
                           :auth {:required? true}}}
```

### Environment-Based Pipeline Assembly
```clojure
(defn build-pipeline [op env config]
  (cond-> [context-interceptor]
    true                    (conj logging-start)
    (metrics-enabled? config) (conj metrics-start)
    (auth-required? op config) (conj auth-interceptor)
    true                    (conj (core-interceptor-for op))
    true                    (conj effects-dispatch)
    (logging-enabled? config) (conj logging-complete)
    (metrics-enabled? config) (conj metrics-complete)
    true                    (conj (response-interceptor-for env))))
```

## Extension Points

### Planned Interceptors
- **Authorization:** JWT validation, role-based access control
- **Tracing:** OpenTelemetry span creation/management
- **Feature Flags:** Early halt if operation disabled
- **Rate Limiting:** Request throttling with Redis backend
- **Caching:** Response caching for idempotent operations
- **Transformation:** Request/response data transformation
- **Audit:** Compliance logging for sensitive operations

### Custom Interceptor Template
```clojure
(def my-custom-interceptor
  {:name :my-custom
   :enter (fn [ctx]
            ;; Pre-processing logic
            ;; Return updated ctx or ctx with :halt? true
            ctx)
   :leave (fn [ctx]
            ;; Post-processing logic
            ;; Return updated ctx
            ctx)
   :error (fn [ctx]
            ;; Error handling logic
            ;; ctx contains :exception key
            ctx)})
```

## Benefits Summary

### For Developers
- **Reduced Boilerplate:** 70-80% reduction in observability code per shell function
- **Consistent Patterns:** Same interceptor chain across HTTP/CLI/service calls
- **Easy Extension:** Add new cross-cutting concerns without touching existing code
- **Clear Separation:** Business orchestration vs infrastructure concerns

### For Operations
- **Uniform Observability:** Consistent logging/metrics across all operations
- **Better Debuggability:** Correlation IDs, structured logs, granular timing
- **Configuration Control:** Enable/disable observability features per environment
- **Performance Insights:** Per-stage timing, success/failure rates

### For Architecture
- **Maintainable:** Cross-cutting concerns centralized and reusable
- **Testable:** Pure interceptors easy to unit test
- **Extensible:** Add new patterns without disrupting existing ones
- **FC/IS Compliant:** Core remains pure, shell handles all infrastructure

## Next Steps

1. **Review and Approve:** Validate approach aligns with team preferences
2. **Create Implementation Task:** Break down into concrete development tasks
3. **Prototype:** Implement basic runner + interceptors for user creation
4. **Measure Impact:** Compare before/after code complexity and observability quality
5. **Iterate:** Refine based on developer feedback and performance measurements
6. **Scale:** Roll out to other modules following established patterns

---

**Document Status:** Draft - Ready for Implementation  
**Last Updated:** {current-date}  
**Next Review:** After Phase 1 completion