# Testing Strategy for Functional Core / Imperative Shell Architecture

*Comprehensive testing approach aligned with module-centric FC/IS architecture*

## Testing Philosophy

### Architecture-Aligned Testing
The Functional Core / Imperative Shell architecture naturally suggests a layered testing approach:

1. **Functional Core**: Pure functions enable comprehensive unit testing without mocks
2. **Imperative Shell**: Integration and system tests focus on I/O boundaries
3. **Module Boundaries**: Each module tested independently with clear contracts

### Testing Pyramid for FC/IS

```
                    ┌─────────────────┐
                    │  System Tests   │ ← Full system end-to-end
                    │   (Interfaces)  │
                    └─────────────────┘
                  ┌───────────────────────┐
                  │  Integration Tests    │ ← Shell components with real dependencies
                  │  (Imperative Shell)   │
                  └───────────────────────┘
                ┌─────────────────────────────┐
                │      Unit Tests             │ ← Pure functions, no mocks needed
                │   (Functional Core)         │
                └─────────────────────────────┘
```

## Layer-Specific Testing Strategies

### Unit Testing (Functional Core)

**Characteristics:**
- **No Mocks Required**: Pure functions are deterministic and self-contained
- **Property-Based Testing**: Use test.check for comprehensive scenario coverage
- **Fast Execution**: No I/O dependencies, rapid feedback loops
- **High Coverage**: Target >95% code coverage for business logic

**Example Testing Pattern:**
```clojure
(deftest create-user-business-logic-test
  (testing "user creation with valid input"
    (let [user-input {:email "test@example.com"
                     :name "Test User"
                     :role :user
                     :active true}
          
          ;; Mock ports with simple functions (no complex mocking framework)
          test-ports {:user-repository (fn [action email]
                                        (case action
                                          :find-by-email nil)) ; Email available
                     :system-services (fn [action]
                                       (case action
                                         :current-timestamp #inst "2025-01-10T10:00:00Z"
                                         :generate-id #uuid "123e4567-e89b-12d3-a456-426614174000"))}
          
          ;; Call pure core function
          result (user-core/create-new-user user-input test-ports)]
      
      ;; Assert on pure data structures
      (is (= :success (:status result)))
      (is (= "test@example.com" (get-in result [:data :email])))
      (is (= #uuid "123e4567-e89b-12d3-a456-426614174000" (get-in result [:data :id])))
      (is (contains-effect? (:effects result) :persist-user))))

(deftest create-user-business-rules-test
  (testing "email uniqueness validation"
    (let [user-input {:email "existing@example.com"
                     :name "Test User"
                     :role :user}
          
          ;; Port returns existing user
          test-ports {:user-repository (fn [action email]
                                        (case action
                                          :find-by-email {:id #uuid "existing-user-id"
                                                         :email "existing@example.com"}))}
          
          result (user-core/create-new-user user-input test-ports)]
      
      (is (= :error (:status result)))
      (is (some #(= :already-exists (:code %)) (:errors result))))))

;; Property-based testing for business logic
(defspec user-creation-properties 100
  (prop/for-all [user-data (s/gen ::valid-user-input)]
    (let [result (user-core/create-new-user user-data test-ports)]
      (and
        ;; Result is always valid
        (contains? #{:success :error} (:status result))
        ;; Success results have required data
        (if (= :success (:status result))
          (and (some? (get-in result [:data :id]))
               (some? (get-in result [:data :created-at])))
          true)
        ;; Error results have error details
        (if (= :error (:status result))
          (seq (:errors result))
          true)))))
```

**Coverage Goals:**
- **Business Logic**: >95% line and branch coverage
- **Edge Cases**: Property-based testing covers boundary conditions
- **Error Paths**: All business rule violations tested

### Integration Testing (Imperative Shell)

**Characteristics:**
- **Real Dependencies**: Test with actual databases, external services
- **Transaction Testing**: Verify cross-module coordination and rollback
- **Adapter Testing**: Validate port implementations
- **Configuration Testing**: Test different environment configurations

**Example Testing Pattern:**
```clojure
(deftest user-repository-integration-test
  (testing "PostgreSQL repository implementation"
    (with-test-database [db test-db-config]
      (let [user-repo (user-adapters/make-postgresql-user-repository db)
            tenant-id #uuid "test-tenant-123"
            
            ;; Test data
            test-user {:email "integration@test.com"
                      :name "Integration Test User"
                      :role :user
                      :active true
                      :tenant-id tenant-id}]
        
        ;; Test create
        (let [created-user (user-ports/create-user user-repo test-user)]
          (is (uuid? (:id created-user)))
          (is (inst? (:created-at created-user)))
          
          ;; Test retrieve
          (let [retrieved-user (user-ports/find-user-by-id user-repo (:id created-user))]
            (is (= (:email created-user) (:email retrieved-user))))
          
          ;; Test update
          (let [updated-user (user-ports/update-user user-repo 
                                                    (assoc created-user :name "Updated Name"))]
            (is (= "Updated Name" (:name updated-user)))
            (is (not= (:updated-at created-user) (:updated-at updated-user))))
          
          ;; Test soft delete
          (let [delete-result (user-ports/soft-delete-user user-repo (:id created-user))]
            (is (true? delete-result))
            (is (nil? (user-ports/find-user-by-id user-repo (:id created-user))))))))))

(deftest cross-module-transaction-test
  (testing "transaction coordination between user and billing modules"
    (with-test-database [db test-db-config]
      (let [system {:datasource db
                   :user-repository (user-adapters/make-postgresql-user-repository db)
                   :billing-repository (billing-adapters/make-postgresql-billing-repository db)}]
        
        (testing "successful transaction commits both operations"
          (let [result (user-billing-service/create-user-with-billing-setup system user-with-billing-data)]
            (is (= :success (:status result)))
            (is (some? (get-in result [:data :user :id])))
            (is (some? (get-in result [:data :billing-account :id])))))
        
        (testing "failed transaction rolls back all operations"
          (is (thrown? Exception
                (user-billing-service/create-user-with-invalid-billing system invalid-billing-data)))
          
          ;; Verify no user or billing data was persisted
          (is (empty? (user-ports/find-users-by-tenant (:user-repository system) test-tenant-id {})))
          (is (empty? (billing-ports/find-accounts-by-tenant (:billing-repository system) test-tenant-id {}))))))))
```

### System Testing (Full Stack)

**Characteristics:**
- **End-to-End Workflows**: Complete business process validation
- **Multi-Interface Testing**: Test REST, CLI, and Web interfaces
- **Production-Like Environment**: Real databases, message queues, external services
- **Performance Validation**: Load and stress testing

**Example Testing Pattern:**
```clojure
(deftest full-user-management-workflow-test
  (testing "complete user lifecycle via REST API"
    (with-system [system test-system-config]
      (let [api-client (test-api-client system)
            tenant-id (test-tenant-id)]
        
        ;; Create user via REST API
        (let [create-response (api-client :post "/api/v1/users"
                                         {:email "e2e@test.com"
                                          :name "E2E Test User"  
                                          :role "user"
                                          :active true})
              user-id (get-in create-response [:body :data :id])]
          
          (is (= 201 (:status create-response)))
          (is (uuid-string? user-id))
          
          ;; Verify user exists via GET
          (let [get-response (api-client :get (str "/api/v1/users/" user-id))]
            (is (= 200 (:status get-response)))
            (is (= "e2e@test.com" (get-in get-response [:body :data :email]))))
          
          ;; Update user via PUT
          (let [update-response (api-client :put (str "/api/v1/users/" user-id)
                                          {:name "Updated E2E User"
                                           :active false})]
            (is (= 200 (:status update-response)))
            (is (= "Updated E2E User" (get-in update-response [:body :data :name]))))
          
          ;; Delete user via DELETE
          (let [delete-response (api-client :delete (str "/api/v1/users/" user-id))]
            (is (= 204 (:status delete-response)))
            
            ;; Verify user is deleted
            (let [get-deleted-response (api-client :get (str "/api/v1/users/" user-id))]
              (is (= 404 (:status get-deleted-response))))))))))

(deftest cli-api-consistency-test
  (testing "CLI and REST API produce identical results"
    (with-system [system test-system-config]
      (let [api-client (test-api-client system)
            cli-client (test-cli-client system)]
        
        ;; Create user via API
        (let [api-user (api-client :post "/api/v1/users" test-user-data)
              api-user-id (get-in api-user [:body :data :id])
              
              ;; Create identical user via CLI  
              cli-result (cli-client ["users" "create" 
                                    "--email" "cli@test.com"
                                    "--name" "CLI Test User"
                                    "--role" "user"
                                    "--format" "json"])
              cli-user-id (get-in cli-result [:data :id])]
          
          ;; Both should have same structure and valid IDs
          (is (uuid-string? api-user-id))
          (is (uuid-string? cli-user-id))
          
          ;; Get users via both interfaces
          (let [api-get (api-client :get (str "/api/v1/users/" api-user-id))
                cli-get (cli-client ["users" "get" "--id" api-user-id "--format" "json"])]
            
            ;; Should return equivalent data structures
            (is (equivalent-user-data? 
                  (get-in api-get [:body :data])
                  (:data cli-get))))))))
```

## Module-Specific Testing

### User Module Testing

**Core Logic Testing:**
- User creation business rules
- Role-based access control logic
- Email validation and uniqueness
- User lifecycle state transitions

**Shell Testing:**
- PostgreSQL user repository
- SMTP notification service  
- HTTP API endpoints
- CLI command processing

**Integration Testing:**
- User registration with welcome email
- Session management integration
- Cross-module user references

### Billing Module Testing

**Core Logic Testing:**
- Invoice calculation algorithms
- Payment processing business rules
- Tax calculation logic
- Subscription billing cycles

**Shell Testing:**
- Billing database operations
- Payment gateway integrations
- PDF invoice generation
- Billing notification emails

**Integration Testing:**
- Order processing with user module
- Subscription lifecycle management
- Financial reporting accuracy

### Workflow Module Testing

**Core Logic Testing:**
- State machine transitions
- Business process validation
- Task assignment algorithms
- Deadline calculation logic

**Shell Testing:**
- Workflow persistence layer
- Task notification system
- Process monitoring
- Workflow API endpoints

**Integration Testing:**
- Multi-user workflow coordination
- Process completion triggers
- Audit trail generation

## Testing Infrastructure

### Test Data Management

**Test Database Strategy:**
```clojure
(defn with-test-database [db-config test-fn]
  "Provides isolated test database for each test"
  (let [test-db-name (str "test_" (random-uuid))
        test-db-config (assoc db-config :database test-db-name)]
    (try
      (create-test-database test-db-config)
      (run-migrations test-db-config)
      (test-fn test-db-config)
      (finally
        (drop-test-database test-db-config)))))

(defn with-test-data [db fixtures test-fn]
  "Loads test fixtures into database"
  (transaction [tx db]
    (try
      (load-fixtures tx fixtures)
      (test-fn tx)
      (finally
        (rollback-transaction tx)))))
```

**Test Fixtures:**
- **Minimal Fixtures**: Just enough data for specific test scenarios
- **Representative Data**: Realistic data volumes for performance testing
- **Edge Case Fixtures**: Boundary conditions and error scenarios

### Mock and Stub Strategy

**Functional Core (Minimal Mocking):**
- Simple function-based mocks for ports
- No complex mocking frameworks needed
- Focus on data in, data out testing

**Imperative Shell (Selective Mocking):**
- Mock external services (payment gateways, email services)
- Use real databases with test data
- Mock slow or unreliable dependencies

**Example Mock Patterns:**
```clojure
;; Simple function-based port mock
(def test-user-repository
  {:find-by-id (fn [id] (get @test-users id))
   :create-user (fn [user] 
                  (let [id (random-uuid)
                        user-with-id (assoc user :id id)]
                    (swap! test-users assoc id user-with-id)
                    user-with-id))})

;; External service mock
(defrecord MockPaymentProcessor [responses]
  PaymentProcessor
  (process-payment [_ payment-request]
    (or (get @responses (:card-number payment-request))
        {:status :success :transaction-id (random-uuid)})))
```

### Continuous Testing

**Local Development:**
- Fast unit test feedback during development
- Integration tests run on file save
- Property-based test continuous execution

**CI/CD Pipeline:**
```yaml
test-pipeline:
  stages:
    - unit-tests:
        command: clojure -M:test:unit
        coverage: >90%
        
    - integration-tests:
        command: clojure -M:test:integration  
        services: [postgresql, redis]
        
    - system-tests:
        command: clojure -M:test:system
        environment: staging-like
        
    - performance-tests:
        command: clojure -M:test:performance
        acceptance-criteria:
          response-time: <200ms
          throughput: >1000rps
```

## Performance Testing

### Load Testing Strategy

**API Performance:**
- Target: <200ms response time for 95th percentile
- Concurrent users: 1000+ simultaneous connections
- Database connection pooling efficiency
- Memory usage under sustained load

**CLI Performance:**
- Command execution time <2s for typical operations
- Bulk operations handling (import/export)
- Resource cleanup and memory management

### Performance Test Scenarios

**User Management Load:**
```clojure
(deftest user-management-load-test
  (testing "user API under load"
    (let [concurrent-users 100
          requests-per-user 50
          
          load-test-fn (fn []
                        (dotimes [_ requests-per-user]
                          (let [start-time (System/currentTimeMillis)
                                response (create-test-user)
                                duration (- (System/currentTimeMillis) start-time)]
                            (is (< duration 200) "Response time within target")
                            (is (= 201 (:status response)) "Successful creation"))))]
      
      ;; Run concurrent load test
      (let [futures (repeatedly concurrent-users #(future (load-test-fn)))]
        (doseq [f futures]
          @f)) ;; Wait for all to complete
      
      ;; Verify system health after load test
      (is (healthy-system? system)))))
```

## Quality Gates and Metrics

### Coverage Targets

| Layer | Coverage Target | Measurement |
|-------|----------------|-------------|
| **Functional Core** | >95% | Line and branch coverage |
| **Imperative Shell** | >80% | Integration test coverage |
| **System Tests** | 100% | Critical user journeys |
| **API Contracts** | 100% | All endpoints tested |

### Performance Targets

| Metric | Target | Measurement Method |
|--------|--------|--------------------|
| **API Response Time** | <200ms (95th percentile) | Load testing |
| **CLI Command Time** | <2s typical operations | Automated timing |
| **Database Queries** | <50ms average | Query performance monitoring |
| **Memory Usage** | <2GB under load | Resource monitoring |

### Quality Metrics

| Category | Metric | Target |
|----------|---------|---------|
| **Reliability** | Test pass rate | >99% |
| **Maintainability** | Test execution time | <10 minutes full suite |
| **Documentation** | Test documentation | 100% critical tests documented |
| **Automation** | Manual test percentage | <5% of total tests |

## Testing Best Practices

### Test Organization

**Module Boundary Respect:**
- Tests mirror module structure
- No cross-module test dependencies
- Clear test ownership per module

**Test Naming Convention:**
```
<module>.<layer>.<component>-test
user.core.user-test          ; Core business logic
user.shell.repository-test   ; Shell integration
user.system.api-test         ; Full system tests
```

### Data-Driven Testing

**Test Data as Code:**
```clojure
(def user-test-scenarios
  {:valid-user {:email "valid@test.com"
                :name "Valid User"
                :role :user
                :expected-status :success}
   
   :invalid-email {:email "invalid-email"
                  :name "Invalid Email User"  
                  :role :user
                  :expected-status :error
                  :expected-error-code :invalid-format}
   
   :duplicate-email {:email "existing@test.com"
                    :name "Duplicate Email"
                    :role :user
                    :expected-status :error
                    :expected-error-code :already-exists}})

(deftest user-creation-scenarios-test
  (doseq [[scenario-name scenario-data] user-test-scenarios]
    (testing (str "user creation scenario: " scenario-name)
      (let [result (create-user-test-execution scenario-data)]
        (is (= (:expected-status scenario-data) (:status result)))
        (when (:expected-error-code scenario-data)
          (is (contains-error-code? result (:expected-error-code scenario-data))))))))
```

### Error Testing

**Comprehensive Error Coverage:**
- All business rule violations
- All validation failures
- Infrastructure failure scenarios
- Recovery and retry mechanisms

**Error Test Pattern:**
```clojure
(deftest error-handling-comprehensive-test
  (testing "database connection failure"
    (with-failing-database [db]
      (let [result (user-service/create-user system invalid-user-data)]
        (is (= :system-error (:status result)))
        (is (contains-error-code? result :database-unavailable)))))
  
  (testing "external service timeout"
    (with-timeout-service [email-service 5000]  ; 5 second timeout
      (let [result (user-service/create-user-with-welcome-email system user-data)]
        ;; User should be created despite email failure
        (is (= :success (:status result)))
        ;; But effect should indicate email failure
        (is (contains-effect-status? result :send-welcome-email :failed)))))
```

---
*Documented: 2025-01-10 18:24*
*Architecture: Functional Core / Imperative Shell with Module-Centric Organization*
