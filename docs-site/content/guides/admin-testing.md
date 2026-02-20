---
title: "Admin Testing Guide"
weight: 60
description: "Complete testing guide for the Admin module covering unit, integration, and contract tests with accessibility testing"
---

# Admin Module Testing Guide

**Version**: 1.0.0  
**Last Updated**: 2026-01-09  
**Status**: Production Ready

## Table of Contents

1. [Overview](#overview)
2. [Test Architecture](#test-architecture)
3. [Test Categories](#test-categories)
4. [Running Tests](#running-tests)
5. [Writing New Tests](#writing-new-tests)
6. [Testing Patterns](#testing-patterns)
7. [Accessibility Testing](#accessibility-testing)
8. [Coverage Goals](#coverage-goals)
9. [Troubleshooting](#troubleshooting)

---

## Overview

The Admin module has comprehensive test coverage across three layers:

- **Unit Tests**: Pure functions (core/*) - Fast, no I/O
- **Integration Tests**: Services with mocked dependencies (shell/service)
- **Contract Tests**: HTTP endpoints with real database (shell/http)

**Current Coverage**: ~78% (lines), 90+ test cases

---

## Test architecture

### Directory structure

```text
test/boundary/admin/
├── core/
│   ├── permissions_test.clj         # 19 tests - Role-based access control
│   ├── schema_introspection_test.clj # 9 tests - DB metadata parsing
│   └── ui_test.clj                  # 27 tests - UI component generation ★ NEW
└── shell/
    ├── http_test.clj                # 16 tests - HTTP endpoint behavior
    ├── service_test.clj             # 15 tests - CRUD operations with H2
    └── schema_repository_test.clj   # 11 tests - Entity config management
```

### Test organization by layer

| Layer | Files | Purpose | Dependencies |
|-------|-------|---------|--------------|
| **Core** | `core/*_test.clj` | Pure business logic | None (pure functions) |
| **Shell** | `shell/service_test.clj` | Service orchestration | H2 in-memory DB |
| **Shell** | `shell/http_test.clj` | HTTP contracts | H2 + minimal HTTP handler |
| **Shell** | `shell/schema_repository_test.clj` | Config loading | H2 DB metadata |

---

## Test categories

### 1. Unit Tests (`:unit` metadata)

**Location**: `test/boundary/admin/core/*_test.clj`

**Characteristics**:
- ✅ Pure functions only (data in, data out)
- ✅ No I/O, no database, no HTTP
- ✅ Fast execution (<100ms per test)
- ✅ Deterministic (same input → same output)

**Example - UI Component Test**:
```clojure
(deftest render-field-value-test
  (testing "Render boolean values"
    (let [result (ui/render-field-value :active true {:type :boolean})]
      (is (vector? result))  ; Returns Hiccup vector
      (is (= :span (first result)))
      (is (str/includes? (str result) "Yes")))))
```text

**Run Unit Tests**:
```bash
clojure -M:test:db/h2 --focus-meta :unit
```bash

### 2. Integration Tests (`:integration` metadata)

**Location**: `test/boundary/admin/shell/service_test.clj`

**Characteristics**:
- ✅ Service layer with real H2 database
- ✅ Tests CRUD operations, pagination, sorting, search
- ✅ Isolated test data (setup/teardown fixtures)
- ⚠️ Slower than unit tests (database operations)

**Example - Service Test**:
```clojure
(deftest list-entities-pagination-test
  (testing "Pagination with page-size parameter"
    (create-test-users! 10)  ; Create test data
    (let [result (ports/list-entities *admin-service* :test-users 
                                      {:page 1 :page-size 5})]
      (is (= 5 (count (:items result))))
      (is (= 1 (:page-number result)))
      (is (= 10 (:total-count result))))))
```text

**Run Integration Tests**:
```bash
clojure -M:test:db/h2 --focus-meta :integration
```bash

### 3. Contract Tests (`:contract` metadata)

**Location**: `test/boundary/admin/shell/http_test.clj`

**Characteristics**:
- ✅ HTTP endpoint behavior with Ring requests
- ✅ Real database (H2) + HTTP handler
- ✅ Tests authentication, authorization, validation
- ✅ HTMX response headers and fragments

**Example - HTTP Test**:
```clojure
(deftest entity-list-endpoint-test
  (testing "Admin can view entity list"
    (create-test-user! "alice@example.com" "Alice" true)
    (let [request (make-request :get "/web/admin/test-users" admin-user)
          response (*handler* request)]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "alice@example.com")))))
```text

**Run Contract Tests**:
```bash
clojure -M:test:db/h2 --focus-meta :contract
```text

### 4. Accessibility Tests ★ NEW

**Location**: `test/boundary/admin/core/ui_test.clj` (Section at bottom)

**Tests**:
- ✅ Form labels and `for` attributes
- ✅ ARIA labels on icon buttons
- ✅ Semantic HTML (`<nav>`, `<table>`, `<form>`)
- ✅ Required field indicators (`*` and `:required` attribute)
- ✅ Error message association with fields
- ✅ Color + text for status indicators (not color alone)
- ✅ Keyboard navigation support
- ✅ Heading hierarchy (h1, h2, h3)
- ✅ Descriptive link text

**Example - Accessibility Test**:
```clojure
(deftest accessibility-aria-labels-test
  (testing "Icon buttons have aria-label"
    (let [page (ui/entity-list-page :users [sample-record] config {...})
          page-str (str page)]
      (is (str/includes? page-str "aria-label"))
      (is (str/includes? page-str "Search")))))
```bash

---

## Running tests

### Quick commands

```bash
# All admin tests (unit + integration + contract)
clojure -M:test:db/h2 --focus-meta :admin

# Unit tests only (fastest)
clojure -M:test:db/h2 --focus-meta :unit

# Specific test file
clojure -M:test:db/h2 --focus boundary.admin.core.ui-test

# Specific test function
clojure -M:test:db/h2 --focus boundary.admin.core.ui-test/render-field-value-test

# Watch mode (re-run on file changes)
clojure -M:test:db/h2 --watch --focus-meta :unit
```bash

### Environment setup

### Test output

```
--- unit (clojure.test) ---------------------------
boundary.admin.core.ui-test
  render-field-value-test
    Render boolean values  ✓
    Render UUID values     ✓
  accessibility-aria-labels-test
    Icon buttons have aria-label  ✓

27 tests, 232 assertions, 0 failures.

Top 3 slowest kaocha.type/var (0,02466 seconds, 28,3% of total time)
  boundary.admin.core.ui-test/htmx-attributes-test
    0,01519 seconds boundary/admin/core/ui_test.clj:700
```bash

---

## Writing new tests

### 1. Unit Tests for Pure Functions

**When**: Testing `src/boundary/admin/core/*.clj` functions

**Template**:
```clojure
(ns boundary.admin.core.my-feature-test
  (:require [boundary.admin.core.my-feature :as feature]
            [clojure.test :refer [deftest is testing]]))

^{:kaocha.testable/meta {:unit true :admin true}}

(deftest my-function-test
  (testing "Descriptive test name"
    (let [input {:foo "bar"}
          result (feature/my-function input)]
      (is (= expected result)))))
```bash

**Guidelines**:
- ✅ Use descriptive test names (what behavior is tested)
- ✅ One `deftest` per function, multiple `testing` blocks for scenarios
- ✅ Test happy path + edge cases (nil, empty, invalid)
- ✅ Use sample fixtures at top of file for consistency
- ❌ No `def` inside `deftest` (use `let` instead)
- ❌ No I/O, side effects, or mutable state

### 2. Integration Tests for Services

**When**: Testing `src/boundary/admin/shell/service.clj` with database

**Template**:
```clojure
(ns boundary.admin.shell.my-service-test
  (:require [boundary.admin.ports :as ports]
            [boundary.platform.shell.adapters.database.factory :as db-factory]
            [clojure.test :refer [deftest is testing use-fixtures]]))

^{:kaocha.testable/meta {:integration true :admin true}}

(def test-db-config
  {:adapter :h2
   :database-path "mem:my_test;DB_CLOSE_DELAY=-1"
   :pool {:minimum-idle 1 :maximum-pool-size 3}})

(defonce ^:dynamic *db-ctx* nil)
(defonce ^:dynamic *service* nil)

(defn setup-test-system! []
  (let [db-ctx (db-factory/db-context test-db-config)]
    (db/execute-update! db-ctx {:raw "CREATE TABLE ..."})
    (alter-var-root #'*db-ctx* (constantly db-ctx))
    (alter-var-root #'*service* (constantly (create-service db-ctx)))))

(defn teardown-test-system! []
  (when *db-ctx*
    (db-factory/close-db-context! *db-ctx*)))

(use-fixtures :once
  (fn [f]
    (setup-test-system!)
    (f)
    (teardown-test-system!)))

(deftest my-integration-test
  (testing "Service with real database"
    (let [result (ports/my-operation *service* {:input "data"})]
      (is (= expected result)))))
```bash

**Guidelines**:
- ✅ Use H2 in-memory database (fast, isolated)
- ✅ Create/drop tables in fixtures
- ✅ Clean data between tests (`:each` fixture)
- ✅ Use dynamic vars (`^:dynamic *db-ctx*`) for test state
- ✅ Test pagination, sorting, filtering, validation

### 3. HTTP Contract Tests

**When**: Testing `src/boundary/admin/shell/http.clj` endpoints

**Template**:
```clojure
(deftest my-endpoint-test
  (testing "GET /web/admin/my-entity"
    (let [request (make-request :get "/web/admin/my-entity" admin-user
                                {:query {"page" "1"}})
          response (*handler* request)]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "expected-content")))))
```bash

**Guidelines**:
- ✅ Test authentication (admin vs regular user vs nil)
- ✅ Test query parameters (pagination, sorting, search)
- ✅ Test response status codes (200, 403, 404, 422)
- ✅ Test HTMX headers (`HX-Trigger`, `HX-Target`)
- ✅ Validate HTML response contains expected data

### 4. UI Component Tests

**When**: Testing `src/boundary/admin/core/ui.clj` Hiccup generation

**Template**:
```clojure
(deftest my-component-test
  (testing "Component generates correct Hiccup"
    (let [component (ui/my-component {:data "value"})
          component-str (str component)]
      ;; Structure assertions
      (is (vector? component))
      (is (= :div.my-class (first component)))
      
      ;; Content assertions
      (is (str/includes? component-str "expected-text"))
      
      ;; Accessibility assertions
      (is (str/includes? component-str "aria-label")))))
```bash

**Guidelines**:
- ✅ Test Hiccup structure (vector, element type, classes)
- ✅ Test content rendering (text, values, formatting)
- ✅ Test conditional rendering (permissions, empty states)
- ✅ Test HTMX attributes (`hx-get`, `hx-post`, `hx-trigger`)
- ✅ Test accessibility (labels, ARIA, semantic HTML)

---

## Testing patterns

### Pattern 1: Fixtures for Test Data

**Problem**: Repeated test data setup across tests  
**Solution**: Define fixtures at top of file

```clojure
(def sample-user
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :email "test@example.com"
   :name "Test User"
   :role :admin
   :active true})

(def sample-entity-config
  {:label "Users"
   :list-fields [:email :name :active]
   :search-fields [:email :name]
   :hide-fields #{:password-hash}})

(deftest my-test
  (testing "Using fixtures"
    (let [result (process sample-user sample-entity-config)]
      (is (some? result)))))
```bash

### Pattern 2: Helper Functions

**Problem**: Repeated test operations (create user, make request)  
**Solution**: Define helper functions

```clojure
(defn create-test-user!
  ([email name]
   (create-test-user! email name true))
  ([email name active]
   (let [user-data {:id (UUID/randomUUID)
                    :email email
                    :name name
                    :active active}]
     (db/execute-one! *db-ctx* {:insert-into :users :values [user-data]})
     user-data)))

(deftest my-test
  (testing "With helper function"
    (create-test-user! "alice@example.com" "Alice")
    (create-test-user! "bob@example.com" "Bob")
    ;; ... assertions
    ))
```bash

### Pattern 3: Tree-Seq for Nested Hiccup

**Problem**: Checking nested Hiccup structure  
**Solution**: Use `tree-seq` to find nested elements

```clojure
(deftest nested-element-test
  (testing "Page contains nested table"
    (let [page (ui/entity-list-page ...)]
      ;; Find nested :table.data-table element
      (is (some #(and (vector? %) (= :table.data-table (first %)))
                (tree-seq vector? seq page))))))
```bash

### Pattern 4: String Conversion for Content Checks

**Problem**: Checking if Hiccup contains text/attributes  
**Solution**: Convert to string and use `str/includes?`

```clojure
(deftest content-check-test
  (testing "Component contains expected text"
    (let [component (ui/my-component {:label "Users"})
          component-str (str component)]
      (is (str/includes? component-str "Users"))
      (is (str/includes? component-str "aria-label")))))
```bash

---

## Accessibility testing

### Why test accessibility?

1. **Legal Compliance**: WCAG 2.1 Level AA requirements
2. **Inclusive UX**: Usable by screen readers, keyboard-only users
3. **Quality Indicator**: Good accessibility → good code structure
4. **Early Detection**: Catch issues before manual testing

### Accessibility checklist

Test our UI against these criteria:

#### ✅ Form Labels
- [ ] Every input has associated `<label>` with `for` attribute
- [ ] Checkboxes and radios have labels
- [ ] Label text is descriptive (not just "Field 1")

```clojure
(deftest form-labels-test
  (let [widget (ui/render-field-widget :email "test@example.com" 
                                       {:widget :email-input :label "Email"} nil)]
    (is (str/includes? (str widget) ":label"))
    (is (str/includes? (str widget) ":for"))))
```bash

#### ✅ ARIA Labels
- [ ] Icon-only buttons have `aria-label`
- [ ] Actions without visible text have descriptive labels
- [ ] Complex widgets have appropriate ARIA attributes

```clojure
(deftest aria-labels-test
  (let [button (ui/icon-button :search)]
    (is (str/includes? (str button) "aria-label"))
    (is (str/includes? (str button) "Search"))))
```bash

#### ✅ Semantic HTML
- [ ] Use `<nav>` for navigation, not `<div>`
- [ ] Use `<table>` for data tables, not layout
- [ ] Use `<form>` for forms, not button click handlers
- [ ] Use `<button>` for actions, not `<div onclick>`

```clojure
(deftest semantic-html-test
  (let [sidebar (ui/admin-sidebar ...)]
    (is (some #(= :nav.admin-sidebar-nav (first %))
              (tree-seq vector? seq sidebar)))))
```text

#### ✅ Required Fields
- [ ] Visual indicator (`*` or "required" text)
- [ ] HTML `required` attribute on input
- [ ] Validation error messages when empty

```clojure
(deftest required-fields-test
  (let [widget (ui/render-field-widget :email "" {:required true} nil)]
    (is (str/includes? (str widget) "*"))  ; Visual indicator
    (is (str/includes? (str widget) ":required true"))))  ; HTML attribute
```text

#### ✅ Error Messages
- [ ] Errors displayed near the field (not just at top)
- [ ] Error styling (`has-errors` class)
- [ ] Descriptive error text (not just "Invalid")

```clojure
(deftest error-messages-test
  (let [widget (ui/render-field-widget :email "" {:required true} 
                                       ["Required field"])]
    (is (str/includes? (str widget) "Required field"))
    (is (str/includes? (str widget) "has-errors"))))
```bash

#### ✅ Color + Text for Status
- [ ] Boolean status shows "Yes"/"No", not just green/red
- [ ] Status badges have text, not just color
- [ ] Icons have text alternatives

```clojure
(deftest color-contrast-test
  (let [status (ui/render-field-value :active true {:type :boolean})]
    (is (str/includes? (str status) "Yes"))  ; Text, not just color
    (is (str/includes? (str status) "badge"))))  ; Class for styling
```bash

#### ✅ Keyboard Navigation
- [ ] Buttons have `type="button"` or `type="submit"`
- [ ] Form inputs are in logical tab order
- [ ] Interactive elements are focusable

```clojure
(deftest keyboard-navigation-test
  (let [form (ui/entity-form ...)]
    (is (str/includes? (str form) ":type \"submit\""))))
```bash

#### ✅ Heading Hierarchy
- [ ] Only one `<h1>` per page
- [ ] Headings in order (h1 → h2 → h3, not h1 → h3)
- [ ] Headings describe content sections

```clojure
(deftest heading-hierarchy-test
  (let [page (ui/admin-home ...)]
    (is (str/includes? (str page) ":h1"))  ; Has main heading
    (is (str/includes? (str page) ":h2"))))  ; Has subsections
```bash

---

## Coverage Goals

### Current Coverage (2026-01-09)

| File | Lines | Tests | Coverage | Status |
|------|-------|-------|----------|--------|
| `core/permissions.clj` | 532 | 19 tests | ✅ 100% | Excellent |
| `core/schema_introspection.clj` | 250 | 9 tests | ✅ ~90% | Good |
| `core/ui.clj` | 807 | 27 tests | ✅ ~85% | Excellent ★ NEW |
| `shell/service.clj` | 600 | 15 tests | ✅ ~75% | Good |
| `shell/http.clj` | 400 | 16 tests | ✅ ~80% | Good |
| `shell/schema_repository.clj` | 200 | 11 tests | ✅ ~90% | Excellent |
| **TOTAL** | **4,305** | **97 tests** | **~78%** | **Good** |

**Improvement**: Coverage increased from 59% → 78% (+19%) with addition of UI tests and accessibility tests.

### Coverage targets

- **Core (Pure Functions)**: 90%+ coverage
- **Shell (Services)**: 75%+ coverage
- **HTTP Endpoints**: 80%+ coverage (critical paths)

### What's NOT covered (Low Priority)

- ⚠️ `ports.clj` - Protocol definitions (no logic to test)
- ⚠️ `schema.clj` - Malli schemas (validated by usage)
- ⚠️ `module_wiring.clj` - Integrant wiring (integration tested)

---

## Troubleshooting

### Issue: Tests fail with "JWT_SECRET not configured"

**Cause**: Environment variable not set

**Solution**:
```bash
export JWT_SECRET="test-secret-key-minimum-32-characters"
clojure -M:test:db/h2 --focus-meta :unit
```bash

### Issue: Tests fail with "Table not found"

**Cause**: Database setup fixture not running or table name mismatch

**Solution**:
1. Check test has `(use-fixtures :once setup/teardown)`
2. Verify table name matches entity name (kebab-case → snake_case)
3. Check H2 table creation SQL in fixture

```clojure
;; Correct: Entity :test-users → Table test_users
(db/execute-update! ctx {:raw "CREATE TABLE test_users (...)"})
```bash

### Issue: Tests pass individually but fail when run together

**Cause**: Shared mutable state or database pollution

**Solution**:
1. Use `:each` fixture to clean data between tests
2. Avoid `def` for mutable state (use `^:dynamic` + `alter-var-root`)
3. Ensure database cleanup in teardown

```clojure
(defn with-clean-tables [f]
  (when *db-ctx*
    (db/execute-update! *db-ctx* {:raw "DELETE FROM test_users"}))
  (f))

(use-fixtures :each with-clean-tables)
```bash

### Issue: REPL tests different from CLI tests

**Cause**: REPL state from previous test runs

**Solution**:
```clojure
;; In REPL
(require '[integrant.repl :as ig-repl])
(ig-repl/halt)  ; Stop system
(ig-repl/go)    ; Fresh start
```

### Issue: Arity errors after adding parameters

**Cause**: Function signature changed but tests not updated

**Solution**:
1. Check function signature in source: `(defn create-service [db-ctx config] ...)`
2. Update test calls: `(create-service db-ctx {:pagination {...}})`
3. Search for all usages: `grep -r "create-service" test/`

---

## Best practices summary

### DO ✅

- Write tests for all public functions in `core/`
- Use descriptive test names (behavior, not implementation)
- Test happy path + edge cases (nil, empty, invalid)
- Test accessibility (labels, ARIA, semantic HTML)
- Use fixtures for test data
- Use helper functions for repeated operations
- Clean up test data between tests
- Run linter after writing tests: `clojure -M:clj-kondo --lint test/`

### DON'T ❌

- Don't skip tests for "simple" functions (they change!)
- Don't test implementation details (test behavior)
- Don't use `def` inside `deftest` (use `let`)
- Don't share mutable state between tests
- Don't commit tests with hardcoded secrets
- Don't test private functions directly (test through public API)

---

## Additional resources

- [Boundary Testing Strategy](../testing.md) - Overall testing philosophy
- [Kaocha Documentation](https://cljdoc.org/d/lambdaisland/kaocha/) - Test runner
- [clojure.test Guide](https://clojure.org/guides/testing) - Built-in test framework
- [WCAG 2.1 Guidelines](https://www.w3.org/WAI/WCAG21/quickref/) - Accessibility standards

---

**Questions?** See [CONTRIBUTING.md](../../CONTRIBUTING.md) or ask in #engineering Slack channel.
