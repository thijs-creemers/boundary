---
title: "Testing Guide"
weight: 50
description: "Comprehensive testing guide covering three-tier strategy, snapshot testing, and TDD workflow"
---

# Boundary Framework Testing Guide

**Version**: 1.0.0  
**Status**: Production Ready  
**Last Updated**: 2026-01-26

Comprehensive guide to testing Boundary applications, covering the three-tier testing strategy, metadata usage, snapshot testing, and best practices.

---

## Table of Contents

1. [Testing Philosophy](#testing-philosophy)
2. [Three-Tier Strategy](#three-tier-strategy)
3. [Unit Testing (Core)](#unit-testing-core)
4. [Integration Testing (Shell Services)](#integration-testing-shell-services)
5. [Contract Testing (HTTP/Adapters)](#contract-testing-httpadapters)
6. [Test Organization & Metadata](#test-organization--metadata)
7. [Test Commands & Workflow](#test-commands--workflow)
8. [Snapshot Testing](#snapshot-testing)
9. [Test Fixtures & Helpers](#test-fixtures--helpers)
10. [Accessibility Testing](#accessibility-testing)
11. [Testing HTMX Interactions](#testing-htmx-interactions)
12. [Mocking Strategies](#mocking-strategies)
13. [Test Coverage & Quality](#test-coverage--quality)
14. [TDD Workflow](#tdd-workflow)
15. [Best Practices](#best-practices)
16. [Troubleshooting](#troubleshooting)

---

## Testing philosophy

Boundary follows the **Functional Core / Imperative Shell (FC/IS)** architecture. This design significantly impacts how we test our code:

1. **Test the Logic, Mock the World**: Business logic resides in pure functions within the `core/` layer. These are tested with simple unit tests.
2. **Isolate Side Effects**: All I/O, database access, and external communication happens in the `shell/` layer. We use integration and contract tests to verify these interactions.
3. **Fast Feedback Loop**: Unit tests should be instantaneous. We prioritize them for complex logic.
4. **Deterministic Results**: Tests must not depend on external state (except for a controlled test database).
5. **No Property-Based Testing (Yet)**: Per current project roadmap, focus on example-based testing. Property-based testing is deferred.

### The "Test Pyramid" in Boundary
We aim for a classic test pyramid:
- **Unit (Top)**: Hundreds of tests, ultra-fast.
- **Integration (Middle)**: Dozens of tests, verifying service wiring.
- **Contract (Base)**: Critical path verification of external interfaces.

---

## Three-Tier Strategy

We categorize tests into three distinct tiers to balance speed, isolation, and confidence.

| Tier | Focus | Location | Metadata | Dependencies |
|------|-------|----------|----------|--------------|
| **Unit** | Pure logic, transformations | `core/*_test.clj` | `:unit` | None |
| **Integration** | Service orchestration | `shell/*_test.clj` | `:integration` | H2 DB / Mocked Ports |
| **Contract** | HTTP/Persistence boundaries | `shell/*_test.clj` | `:contract` | Real DB (H2) + HTTP |

---

## Unit testing (core)

Unit tests focus on the **Functional Core**. They verify that pure functions produce the correct output for a given input.

### Characteristics
- ✅ Fast (milliseconds)
- ✅ No side effects
- ✅ High coverage of edge cases
- ✅ No database or network required

### Example: Validation Logic
Testing a pure validation function in `boundary.user.core.user`:

```clojure
(ns boundary.user.core.user-test
  (:require [boundary.user.core.user :as user-core]
            [clojure.test :refer [deftest is testing]]))

^{:kaocha.testable/meta {:unit true}}

(deftest validate-user-creation-request-test
  (testing "Valid request passes"
    (let [request {:email "alice@example.com" :name "Alice" :role :user}
          [valid? errors data] (user-core/validate-user-creation-request request)]
      (is (true? valid?))
      (is (empty? errors))
      (is (= "alice@example.com" (:email data)))))

  (testing "Invalid email fails"
    (let [request {:email "not-an-email" :name "Alice" :role :user}
          [valid? errors] (user-core/validate-user-creation-request request)]
      (is (false? valid?))
      (is (contains? errors :email)))))
```bash

### Example: UI Component
Testing a Hiccup-generating function. Notice we assert on the **data structure** first, which is more robust than string matching.

```clojure
(deftest render-user-badge-test
  (testing "Renders admin badge"
    (let [user {:name "Admin" :role :admin}
          result (ui/render-user-badge user)]
      ;; Structural assertion
      (is (= :span.badge.admin (first result)))
      ;; Content assertion
      (is (clojure.string/includes? (str result) "Administrator")))))
```bash

### Example: Data Transformation
Testing the conversion between database format (snake_case) and internal format (kebab-case):

```clojure
(deftest entity-conversion-test
  (testing "Converts snake_case DB record to kebab-case entity"
    (let [db-record {:first_name "John" :last_name "Doe" :created_at #inst "2025-01-01"}
          expected {:first-name "John" :last-name "Doe" :created-at #inst "2025-01-01"}]
      (is (= expected (utils/db->entity db-record))))))
```bash

---

## Integration testing (shell services)

Integration tests verify the **Imperative Shell**. They ensure that service functions correctly coordinate between the Functional Core and external ports (adapters).

### Characteristics
- ✅ Verify service-layer logic
- ✅ Use real database (H2 in-memory)
- ✅ Use mocked ports for external services (email, S3, etc.)
- ⚠️ Slower than unit tests

### Example: User Registration Service
Testing `boundary.user.shell.service` using a real H2 database. We use a dynamic variable `*service*` to hold the initialized service instance.

```clojure
(ns boundary.user.shell.service-test
  (:require [boundary.user.ports :as ports]
            [boundary.user.shell.service :refer [create-service]]
            [boundary.platform.shell.adapters.database.factory :as db-factory]
            [clojure.test :refer [deftest is testing use-fixtures]]))

^{:kaocha.testable/meta {:integration true}}

(defonce ^:dynamic *db-ctx* nil)
(defonce ^:dynamic *service* nil)

(defn setup-test-db [f]
  (let [db-ctx (db-factory/db-context {:adapter :h2 :database-path "mem:test_db"})]
    ;; Run migrations or create tables manually for the test
    (db-factory/execute! db-ctx ["CREATE TABLE users (...)"])
    (binding [*db-ctx* db-ctx
              *service* (create-service db-ctx)]
      (f)
      (db-factory/close-db-context! db-ctx))))

(use-fixtures :once setup-test-db)

(deftest register-user-integration-test
  (testing "Successfully registers user in database"
    (let [user-data {:email "new@example.com" :name "New User" :password "pass123"}
          result (ports/register-user *service* user-data)]
      (is (uuid? (:id result)))
      (is (= "new@example.com" (:email result)))
      
      ;; Verify persistence
      (let [persisted (ports/get-user-by-email *service* "new@example.com")]
        (is (= (:id result) (:id persisted)))))))
```bash

---

## Contract testing (HTTP/adapters)

Contract tests verify the **system boundaries**. They ensure that our HTTP endpoints and database adapters adhere to the expected interface (contracts).

### Characteristics
- ✅ Full HTTP request/response cycle
- ✅ Real database interactions
- ✅ Verify security (auth/authz)
- ✅ Verify HTMX headers and partials

### Example: HTTP Endpoint
Testing the login endpoint using Ring requests. We bypass the actual network and call the handler function directly.

```clojure
(deftest login-endpoint-test
  (testing "Successful login returns 200 and JWT token"
    (let [request {:request-method :post
                   :uri "/api/auth/login"
                   :body (json/generate-string {:email "alice@example.com" 
                                               :password "correct-pass"})
                   :headers {"content-type" "application/json"}}
          response (*handler* request)]
      (is (= 200 (:status response)))
      (is (contains? (json/parse-string (:body response)) "token"))))

  (testing "Invalid credentials return 401"
    (let [request {:request-method :post
                   :uri "/api/auth/login"
                   :body (json/generate-string {:email "alice@example.com" 
                                               :password "wrong-pass"})
                   :headers {"content-type" "application/json"}}
          response (*handler* request)]
      (is (= 401 (:status response))))))
```bash

---

## Test organization & metadata

### Directory structure
Tests are organized to mirror the `src` directory structure within each library. This makes it easy to find the corresponding test for any source file.

```
libs/{library}/
├── src/boundary/{library}/
│   ├── core/      # Pure logic
│   └── shell/     # I/O, adapters
└── test/boundary/{library}/
    ├── core/      # Unit tests
    └── shell/     # Integration & Contract tests
```bash

### Metadata tags
We use metadata tags to allow the test runner to filter tests by layer or module.

- `:unit`: Pure functions, no I/O.
- `:integration`: Service layer tests with database.
- `:contract`: Boundary tests (HTTP/persistence).
- Module tags: `:user`, `:admin`, `:core`, etc.

**Applying metadata**:
```clojure
;; At the namespace level (Preferred for layer filtering)
(ns boundary.user.core.user-test)
(alter-meta! *ns* assoc :kaocha/tags [:unit :user])

;; At the individual test level (For specific test filtering)
(deftest ^{:unit true} my-test ...)
```bash

---

## Test commands & workflow

### General commands
The framework uses **Kaocha** as the test runner. All commands should be run from the root directory.

```bash
# Run all tests across all libraries
clojure -M:test:db/h2

# Run all tests with JWT secret (required for some auth tests)
JWT_SECRET="dev-secret-32-chars-minimum" clojure -M:test:db/h2
```bash

### Filtering by Metadata
Metadata filtering allows you to run only the relevant subset of tests during development.

```bash
# Run unit tests only (fastest)
clojure -M:test:db/h2 --focus-meta :unit

# Run integration tests
clojure -M:test:db/h2 --focus-meta :integration

# Run contract tests
clojure -M:test:db/h2 --focus-meta :contract
```bash

### Filtering by Library
```bash
# Run tests for specific library
clojure -M:test:db/h2 :core
clojure -M:test:db/h2 :user
clojure -M:test:db/h2 :admin
```bash

### Watch mode
Watch mode automatically re-runs tests when files are saved. Highly recommended for TDD.

```bash
# Watch core library unit tests
clojure -M:test:db/h2 --watch :core --focus-meta :unit

# Watch all user module tests
clojure -M:test:db/h2 --watch :user
```text

---

## Snapshot testing

Snapshot testing is used to ensure that complex data structures (like validation results or HTML fragments) remain stable.

### Why snapshot testing?
Validation rules can become complex. Snapshot testing captures the entire result (including error messages and data shapes) and flags any deviation. This is much more effective than manually asserting on individual error strings.

### Workflow
1. **Write Snapshot Test**: Use `snapshot-io/check-snapshot!`.
2. **Run Test**: If it's a new test, it will create a snapshot file. If it's an existing test, it will compare the result.
3. **Update Snapshots**: If changes are intentional, update the snapshots.

```bash
# Update validation snapshots for the user module
UPDATE_SNAPSHOTS=true clojure -M:test:db/h2 --focus boundary.user.core.user-validation-snapshot-test
```bash

### Example snapshot test
```clojure
(deftest email-validation-invalid-format-snapshot
  (testing "Invalid email format produces structured error"
    (let [request (assoc valid-user-request :email "not-an-email")
          result (user-core/validate-user-creation-request request)]
      (snapshot-io/check-snapshot!
       result
       {:ns (ns-name *ns*)
        :test 'email-validation-invalid-format}))))
```bash
Snapshots are stored as `.edn` files under `libs/{library}/test/snapshots/validation/`. They are human-readable and should be committed to version control.

---

## Test fixtures & helpers

### Dynamic variables for test state
We use dynamic vars and `use-fixtures` to manage test setup (like database connections). This avoids global state pollution.

```clojure
(defonce ^:dynamic *db-ctx* nil)

(defn with-clean-database [f]
  (let [ds (get-in *db-ctx* [:datasource])]
    (jdbc/execute! ds ["DELETE FROM users"])
    (f)))

(use-fixtures :each with-clean-database)
```bash

### Helper functions
Common operations should be extracted to helper functions to keep tests readable and maintainable.

```clojure
(defn create-test-user! [email role]
  (ports/create-user *user-service* {:email email :role role :password "pass123"}))

(defn authenticated-request [method uri user]
  (let [token (auth/generate-token user)]
    {:request-method method
     :uri uri
     :headers {"authorization" (str "Bearer " token)}}))
```text

---

## Accessibility testing

Boundary prioritizes accessibility in its UI. We test for ARIA labels, semantic HTML, and form associations.

### Accessibility checklist
- [ ] Every input has a `<label>` with a `for` attribute.
- [ ] Icon-only buttons have an `aria-label`.
- [ ] Required fields have both a visual indicator (`*`) and the `required` attribute.
- [ ] Status badges use text alternatives, not just color.
- [ ] Navigation components use `<nav>` tags.
- [ ] Error messages are associated with their fields (using `aria-describedby` or placement).

### Example accessibility test
```clojure
(deftest icon-button-accessibility-test
  (testing "Search button has aria-label"
    (let [html (str (ui/icon-button :search))]
      (is (clojure.string/includes? html "aria-label=\"Search\"")))))

(deftest form-field-label-test
  (testing "Email input has associated label"
    (let [html (str (ui/render-email-field :email "test@example.com"))]
      (is (clojure.string/includes? html "<label for=\"email\">"))
      (is (clojure.string/includes? html "<input id=\"email\" type=\"email\"")))))
```bash

---

## Testing HTMX interactions

Since Boundary uses HTMX for dynamic behavior, we must test that our handlers return the correct fragments and headers.

### Testing Fragments
Verify that a fragment handler returns only the partial HTML, not the full page layout.

```clojure
(deftest table-fragment-test
  (testing "Returns only the table container"
    (let [request {:headers {"hx-request" "true"}}
          response (handlers/table-fragment request)]
      (is (not (clojure.string/includes? (:body response) "<body")))
      (is (clojure.string/includes? (:body response) "id=\"entity-table\"")))))
```bash

### Testing HTMX Headers
Verify that the server sends `HX-Trigger` or `HX-Redirect` headers when appropriate.

```clojure
(deftest user-creation-htmx-test
  (testing "Sends HX-Trigger header on success"
    (let [request {:request-method :post :form-params {...}}
          response (handlers/create-user-handler request)]
      (is (= "userCreated" (get-in response [:headers "HX-Trigger"]))))))
```bash

---

## Mocking Strategies

While we prefer real H2 databases for integration tests, some external services must be mocked.

### 1. Using `reify` (Ad-hoc Mocks)
Best for simple, one-off mocks within a single test file.

```clojure
(let [mock-email-port (reify ports/IEmailPort
                        (send-email [_ details]
                          (reset! sent-emails-atom details)))]
  (ports/register-user service data mock-email-port))
```bash

### 2. Using `defrecord` (Reusable Mocks)
Best for mocks that are shared across multiple test files.

```clojure
(defrecord MockStoragePort [files]
  ports/IStoragePort
  (upload-file [_ path content]
    (swap! files assoc path content)))

(defn create-mock-storage []
  (->MockStoragePort (atom {})))
```bash

---

## Test Coverage & Quality

### Measuring Coverage
We use `cloverage` to measure test coverage. Aim for 90%+ in `core/` and 75%+ overall.

```bash
# Run coverage report for the user library
clojure -M:test:coverage :user
```bash

### Linting Tests
Tests are code too! Always lint your test directory.

```bash
clojure -M:clj-kondo --lint libs/user/test
```bash

---

## TDD Workflow

1. **Start Watch Mode**: `clojure -M:test:db/h2 --watch :my-module`
2. **Write a Failing Test**: Create the test in `test/boundary/my_module/core/feature_test.clj`.
3. **Watch it Fail**: The test runner should report a failure.
4. **Implement the Logic**: Write the code in `src/boundary/my_module/core/feature.clj`.
5. **Watch it Pass**: The test runner will automatically re-run and show green.
6. **Refactor**: Clean up the code, keeping the tests passing.

---

## Best Practices

### DO ✅
- **Isolate tests**: Each test should be independent and clean up after itself.
- **Use H2 for speed**: Use the `:h2` alias for fast in-memory database testing.
- **Test happy and sad paths**: Ensure failures are handled gracefully.
- **Keep it pure**: Aim for 100% coverage of pure functions in the `core/` layer.
- **Assert on structures**: When testing UI, assert on the Hiccup vector structure before converting to string.
- **Use meaningful names**: `test-user-creation-with-invalid-email` is better than `test-1`.
- **Use tree-seq for deep HTML checks**: If you need to find a nested element in a Hiccup structure.

### DON'T ❌
- **Don't mock what you don't own**: Don't mock third-party libraries; mock your own ports.
- **Don't use `def` for test state**: Use `let` or dynamic vars + `binding`.
- **Don't skip linting**: Run `clj-kondo` on your tests as well as your source code.
- **Don't hardcode secrets**: Use environment variables or `.env` files.
- **Don't test private functions**: Test the public API of your namespaces.
- **Don't ignore intermittent failures**: "Flaky" tests indicate race conditions or shared state. Fix them immediately.

---

## Troubleshooting

### "JWT_SECRET not configured"
Many auth tests require a 32-character JWT secret.
```bash
export JWT_SECRET="test-secret-32-chars-minimum-length"
```bash

### "Table not found"
Ensure your integration tests are running migrations or creating the necessary schema in a `:once` fixture. Check your H2 connection string (e.g., `mem:test;DB_CLOSE_DELAY=-1`).

### "REPL state stale"
If your tests pass in the CLI but fail in the REPL (or vice versa), reset your system:
```clojure
(integrant.repl/halt)
(integrant.repl/go)
```

### "Snapshot Mismatch"
If a snapshot test fails after an intentional change:
1. Verify the diff in the test output.
2. If correct, run with `UPDATE_SNAPSHOTS=true`.

### "Arity Error in Tests"
If you change a function signature in `core/` or a protocol in `ports.clj`, you must update all calls in your tests. Use `grep` or your IDE's "Find Usages" to locate them.

---

*Last updated: 2026-01-26*
*Documentation version: 1.0.0*

---

## See also

- [Admin Testing Guide](admin-testing.md) - Comprehensive admin UI testing strategies
- [Database Setup](database-setup.md) - Database configuration for testing
- [Authentication Guide](authentication.md) - Testing auth and MFA flows
- [IDE Setup](ide-setup.md) - Configure your test runner in your IDE

