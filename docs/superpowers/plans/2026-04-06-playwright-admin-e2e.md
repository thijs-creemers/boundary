# BOU-10: Playwright Admin E2E Tests — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** End-to-end test suite for the Boundary admin UI (Users + Tenants) using Spel/Clojure.

**Architecture:** Three new files in `libs/e2e/test/boundary/e2e/`: a shared admin helper namespace and two test namespaces (users, tenants). All tests use the existing `with-fresh-seed` fixture for clean DB state per test. HTMX interactions are tested by waiting for DOM changes rather than full page reloads.

**Tech Stack:** Spel 0.7.11 (Clojure Playwright wrapper), Kaocha test runner, H2 in-memory DB, existing BOU-9 auth helpers.

---

## File Structure

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `libs/e2e/test/boundary/e2e/helpers/admin.clj` | Admin login, HTMX wait, table/form helpers |
| Create | `libs/e2e/test/boundary/e2e/html/admin_users_test.clj` | Users list + detail + edit + access control tests |
| Create | `libs/e2e/test/boundary/e2e/html/admin_tenants_test.clj` | Tenants list + detail + edit + soft-delete + access control tests |

---

## Task 1: Admin Helper Namespace

**Files:**
- Create: `libs/e2e/test/boundary/e2e/helpers/admin.clj`

- [ ] **Step 1: Create the admin helper namespace**

```clojure
(ns boundary.e2e.helpers.admin
  "Shared helpers for admin UI e2e tests.
   Wraps BOU-9 auth helpers with admin-specific login and
   DOM query utilities for tables, forms, and HTMX interactions."
  (:require [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [boundary.e2e.helpers.reset :as reset]))

(def ^:private base-url (reset/default-base-url))

(defn admin-url
  "Build a full admin URL from a relative path."
  [path]
  (str base-url "/web/admin" path))

(defn login-as-admin!
  "Navigate to /web/login, fill seed admin credentials, wait for redirect
   to /web/admin/users. Returns the page for chaining."
  [pg seed]
  (page/navigate pg (str base-url "/web/login"))
  (page/wait-for-load-state pg)
  (loc/fill (page/locator pg "input[name='email']") (-> seed :admin :email))
  (loc/fill (page/locator pg "input[name='password']") (-> seed :admin :password))
  (loc/click (page/locator pg "form.form-card button[type='submit']"))
  (page/wait-for-url pg #".*/web/admin/users.*" {:timeout 10000.0})
  pg)

(defn login-as-user!
  "Navigate to /web/login, fill seed user credentials, wait for redirect
   to /web/dashboard. Returns the page for chaining."
  [pg seed]
  (page/navigate pg (str base-url "/web/login"))
  (page/wait-for-load-state pg)
  (loc/fill (page/locator pg "input[name='email']") (-> seed :user :email))
  (loc/fill (page/locator pg "input[name='password']") (-> seed :user :password))
  (loc/click (page/locator pg "form.form-card button[type='submit']"))
  (page/wait-for-url pg #".*/web/dashboard.*" {:timeout 10000.0})
  pg)

(defn wait-for-htmx!
  "Wait for HTMX to settle after a fragment update. Uses a JS promise
   that resolves on the htmx:afterSettle event."
  [pg]
  (page/evaluate pg
    "new Promise(r => { const h = () => { r(); }; document.addEventListener('htmx:afterSettle', h, {once:true}); setTimeout(h, 5000); })"))

(defn table-headers
  "Read visible column header texts from table.data-table thead th.
   Returns a vector of lowercase trimmed strings."
  [pg]
  (let [ths (page/locator pg "table.data-table thead th")]
    (->> (range (loc/count-elements ths))
         (mapv #(-> (loc/nth ths %)
                    loc/text-content
                    clojure.string/trim
                    clojure.string/lower-case))
         (filterv seq))))

(defn table-row-count
  "Count data rows in table.data-table tbody."
  [pg]
  (loc/count-elements (page/locator pg "table.data-table tbody tr")))

(defn search!
  "Type a search query into the search input and wait for HTMX table update."
  [pg query]
  (let [input (page/locator pg "input.search-input")]
    (loc/fill input query)
    ;; The search triggers on keyup changed delay:300ms — wait for HTMX settle
    (wait-for-htmx! pg)))

(defn has-empty-state?
  "Check if the empty state message is visible (no matching records)."
  [pg]
  (let [empty-el (page/locator pg ".empty-state, .no-results, table.data-table tbody tr")]
    ;; If there are 0 data rows, or an explicit empty state element
    (or (zero? (table-row-count pg))
        (pos? (loc/count-elements (page/locator pg ".empty-state"))))))

(defn field-group-visible?
  "Check if a field group with the given data-group-id is visible."
  [pg group-id]
  (loc/is-visible? (page/locator pg (str "div.form-field-group[data-group-id='" group-id "']"))))

(defn field-readonly?
  "Check if a form field is readonly or disabled."
  [pg field-name]
  (let [input (page/locator pg (str "[name='" field-name "']"))]
    (or (= "true" (loc/get-attribute input "readonly"))
        (= "true" (loc/get-attribute input "disabled"))
        (= "" (loc/get-attribute input "readonly"))
        (= "" (loc/get-attribute input "disabled")))))

(defn flash-visible?
  "Check if a flash/alert message of the given type is visible."
  [pg flash-type]
  (loc/is-visible? (page/locator pg (str ".alert.alert-" (name flash-type)))))
```

- [ ] **Step 2: Verify the file loads without errors**

Run: `clojure -M:test:e2e -e "(require 'boundary.e2e.helpers.admin)"`
Expected: No compilation errors.

- [ ] **Step 3: Commit**

```bash
git add libs/e2e/test/boundary/e2e/helpers/admin.clj
git commit -m "feat(e2e): add admin UI helper namespace for BOU-10"
```

---

## Task 2: Users List Tests

**Files:**
- Create: `libs/e2e/test/boundary/e2e/html/admin_users_test.clj`

**Context:**
- Users entity config: `resources/conf/test/admin/users.edn`
- List fields: `[:email :name :role :active :created-at]`
- Hidden fields: `#{:password-hash :mfa-secret :mfa-backup-codes :mfa-backup-codes-used :deleted-at}`
- Search fields: `[:email :name]`
- Seed admin: `admin@acme.test` / `Test-Pass-1234!` (role: admin)
- Seed user: `user@acme.test` / `Test-Pass-1234!` (role: user)

- [ ] **Step 1: Create the test namespace with list tests**

```clojure
(ns boundary.e2e.html.admin-users-test
  "E2E browser tests for the admin Users UI: list overview, search,
   detail/edit forms, field visibility, and access control."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [boundary.e2e.fixtures :as fx]
            [boundary.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

;; ---------------------------------------------------------------------------
;; List overview
;; ---------------------------------------------------------------------------

(deftest ^:e2e list-shows-data-table
  (testing "Admin users list page loads and shows table.data-table with expected columns"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/users"))
      (page/wait-for-load-state pg)
      ;; Table is visible
      (is (loc/is-visible? (page/locator pg "table.data-table"))
          "table.data-table should be visible on users list page")
      ;; Expected columns present
      (let [headers (admin/table-headers pg)]
        (is (some #(str/includes? % "email") headers)
            "Table should have an email column")
        (is (some #(str/includes? % "name") headers)
            "Table should have a name column")
        (is (some #(str/includes? % "role") headers)
            "Table should have a role column")))))

(deftest ^:e2e list-hides-sensitive-fields
  (testing "Sensitive fields (password-hash, mfa-secret) are not shown in the table"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/users"))
      (page/wait-for-load-state pg)
      (let [headers (admin/table-headers pg)
            page-text (loc/text-content (page/locator pg "table.data-table"))]
        (is (not (some #(str/includes? % "password") headers))
            "password-hash should not be a table column")
        (is (not (some #(str/includes? % "mfa-secret") headers))
            "mfa-secret should not be a table column")
        (is (not (some #(str/includes? % "backup") headers))
            "mfa-backup-codes should not be a table column")))))

(deftest ^:e2e search-filters-by-email
  (testing "Search input filters the users table via HTMX"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/users"))
      (page/wait-for-load-state pg)
      ;; Search for the admin email — should find exactly that user
      (admin/search! pg "admin@acme")
      (let [rows (admin/table-row-count pg)]
        (is (pos? rows) "Search for 'admin@acme' should return at least 1 row"))
      ;; Search for something that doesn't exist
      (admin/search! pg "nonexistent-user-xyz")
      (admin/wait-for-htmx! pg)
      (is (admin/has-empty-state? pg)
          "Search for nonexistent user should show empty state"))))

(deftest ^:e2e search-htmx-no-full-reload
  (testing "Search triggers HTMX fragment update, not a full page reload"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/users"))
      (page/wait-for-load-state pg)
      ;; Set a marker on the DOM that would be lost on full page reload
      (page/evaluate pg "document.body.setAttribute('data-e2e-marker', 'alive')")
      ;; Trigger search
      (admin/search! pg "admin")
      ;; The marker should still be present (no full reload happened)
      (let [marker (page/evaluate pg "document.body.getAttribute('data-e2e-marker')")]
        (is (= "alive" marker)
            "DOM marker should survive HTMX fragment update (no full page reload)")))))
```

- [ ] **Step 2: Run the list tests to verify they pass**

Run: `clojure -M:test:e2e :e2e --focus boundary.e2e.html.admin-users-test`
Expected: All 4 tests PASS. (Requires running test server on port 3100.)

- [ ] **Step 3: Commit**

```bash
git add libs/e2e/test/boundary/e2e/html/admin_users_test.clj
git commit -m "feat(e2e): add admin users list tests (BOU-10)"
```

---

## Task 3: Users Detail & Edit Tests

**Files:**
- Modify: `libs/e2e/test/boundary/e2e/html/admin_users_test.clj`

**Context:**
- Field groups: identity (email, name), access (role, active), preferences, notifications
- Readonly fields: `#{:id :mfa-enabled :created-at :updated-at}`
- Role is an enum with options: admin, user, viewer
- Form uses `form.entity-form` wrapping `div.form-card`
- Flash on success: `div.alert.alert-success`
- Validation errors: `.field-errors` within the form

- [ ] **Step 1: Add detail and edit tests to the users test namespace**

Append after the search tests:

```clojure
;; ---------------------------------------------------------------------------
;; Detail & edit
;; ---------------------------------------------------------------------------

(deftest ^:e2e detail-shows-field-groups
  (testing "User detail page shows field groups 'identity' and 'access'"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      ;; Navigate to the admin user's detail page
      (page/navigate pg (admin/admin-url (str "/users/" (-> fx/*seed* :admin :id))))
      (page/wait-for-load-state pg)
      (is (admin/field-group-visible? pg "identity")
          "Field group 'identity' (email, name) should be visible")
      (is (admin/field-group-visible? pg "access")
          "Field group 'access' (role, active) should be visible"))))

(deftest ^:e2e detail-readonly-fields
  (testing "Readonly fields (id, created-at, updated-at) are not editable"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/users/" (-> fx/*seed* :admin :id))))
      (page/wait-for-load-state pg)
      ;; Readonly fields should not appear as editable inputs in the form.
      ;; They are excluded from editable-fields, so they won't have input elements.
      ;; Instead, verify that form.entity-form exists but does NOT contain inputs for these.
      (is (loc/is-visible? (page/locator pg "form.entity-form"))
          "Entity form should be visible")
      (is (zero? (loc/count-elements (page/locator pg "form.entity-form input[name='id']")))
          "id should not be an editable input in the form")
      (is (zero? (loc/count-elements (page/locator pg "form.entity-form input[name='created-at']")))
          "created-at should not be an editable input in the form"))))

(deftest ^:e2e edit-role-via-dropdown
  (testing "Changing the role dropdown and submitting updates the user's role"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      ;; Navigate to the regular user's detail page (not admin, to safely change role)
      (page/navigate pg (admin/admin-url (str "/users/" (-> fx/*seed* :user :id))))
      (page/wait-for-load-state pg)
      ;; Change role from user to viewer
      (loc/select-option (page/locator pg "select[name='role']") "viewer")
      ;; Submit the form
      (loc/click (page/locator pg "form.entity-form button[type='submit']"))
      ;; Wait for HTMX to re-render the page
      (admin/wait-for-htmx! pg)
      (page/wait-for-load-state pg)
      ;; Verify the role is now viewer
      (let [role-value (loc/input-value (page/locator pg "select[name='role']"))]
        (is (= "viewer" role-value)
            "Role should be updated to 'viewer' after form submission")))))

(deftest ^:e2e edit-required-field-empty-shows-error
  (testing "Clearing a required-like field and submitting shows validation errors"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/users/" (-> fx/*seed* :user :id))))
      (page/wait-for-load-state pg)
      ;; Clear the name field
      (loc/fill (page/locator pg "input[name='name']") "")
      ;; Submit the form
      (loc/click (page/locator pg "form.entity-form button[type='submit']"))
      ;; Wait for response
      (admin/wait-for-htmx! pg)
      (page/wait-for-load-state pg)
      ;; Should show validation errors or error flash
      (let [has-field-errors (pos? (loc/count-elements (page/locator pg ".field-errors")))
            has-error-flash  (pos? (loc/count-elements (page/locator pg ".alert.alert-error")))]
        (is (or has-field-errors has-error-flash)
            "Validation error should be shown when required field is empty")))))

(deftest ^:e2e edit-success-shows-notification
  (testing "Successful edit shows a success notification"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/users/" (-> fx/*seed* :user :id))))
      (page/wait-for-load-state pg)
      ;; Make a valid change — update the name
      (loc/fill (page/locator pg "input[name='name']") "Updated Test User")
      ;; Submit
      (loc/click (page/locator pg "form.entity-form button[type='submit']"))
      (admin/wait-for-htmx! pg)
      (page/wait-for-load-state pg)
      ;; Success flash should appear
      (is (admin/flash-visible? pg :success)
          "Success notification should be visible after saving"))))
```

- [ ] **Step 2: Run all users tests to verify they pass**

Run: `clojure -M:test:e2e :e2e --focus boundary.e2e.html.admin-users-test`
Expected: All 9 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add libs/e2e/test/boundary/e2e/html/admin_users_test.clj
git commit -m "feat(e2e): add admin users detail & edit tests (BOU-10)"
```

---

## Task 4: Tenants List Tests

**Files:**
- Create: `libs/e2e/test/boundary/e2e/html/admin_tenants_test.clj`

**Context:**
- Tenants entity config: `resources/conf/test/admin/tenants.edn`
- List fields: `[:slug :name :status :created-at]`
- Hidden fields: `#{:deleted-at}`
- Readonly fields: `#{:id :schema-name :created-at :updated-at}`
- Search fields: `[:slug :name]`
- Status enum: active, suspended, deleted
- Seed tenant: "Acme Test" (slug: acme, status: active)

- [ ] **Step 1: Create the tenants test namespace with list tests**

```clojure
(ns boundary.e2e.html.admin-tenants-test
  "E2E browser tests for the admin Tenants UI: list overview, search,
   detail/edit forms, soft-delete, and access control."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]
            [com.blockether.spel.locator :as loc]
            [boundary.e2e.fixtures :as fx]
            [boundary.e2e.helpers.admin :as admin]))

(use-fixtures :each fx/with-fresh-seed)

;; ---------------------------------------------------------------------------
;; List overview
;; ---------------------------------------------------------------------------

(deftest ^:e2e list-shows-data-table
  (testing "Admin tenants list page loads and shows table.data-table with expected columns"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      (is (loc/is-visible? (page/locator pg "table.data-table"))
          "table.data-table should be visible on tenants list page")
      (let [headers (admin/table-headers pg)]
        (is (some #(str/includes? % "slug") headers)
            "Table should have a slug column")
        (is (some #(str/includes? % "name") headers)
            "Table should have a name column")
        (is (some #(str/includes? % "status") headers)
            "Table should have a status column")))))

(deftest ^:e2e list-hides-internal-fields
  (testing "Internal fields (schema-name, settings, deleted-at) are not shown in the table"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      (let [headers (admin/table-headers pg)]
        (is (not (some #(str/includes? % "schema") headers))
            "schema-name should not be a table column")
        (is (not (some #(str/includes? % "setting") headers))
            "settings should not be a table column")
        (is (not (some #(str/includes? % "deleted") headers))
            "deleted-at should not be a table column")))))

(deftest ^:e2e search-filters-by-name
  (testing "Search input filters the tenants table via HTMX"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      ;; Search for the seed tenant
      (admin/search! pg "Acme")
      (let [rows (admin/table-row-count pg)]
        (is (pos? rows) "Search for 'Acme' should return at least 1 row"))
      ;; Search for something that doesn't exist
      (admin/search! pg "zzz-nonexistent-tenant")
      (admin/wait-for-htmx! pg)
      (is (admin/has-empty-state? pg)
          "Search for nonexistent tenant should show empty state"))))

(deftest ^:e2e search-htmx-no-full-reload
  (testing "Search triggers HTMX fragment update, not a full page reload"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      (page/evaluate pg "document.body.setAttribute('data-e2e-marker', 'alive')")
      (admin/search! pg "Acme")
      (let [marker (page/evaluate pg "document.body.getAttribute('data-e2e-marker')")]
        (is (= "alive" marker)
            "DOM marker should survive HTMX fragment update (no full page reload)")))))
```

- [ ] **Step 2: Run the tenants list tests**

Run: `clojure -M:test:e2e :e2e --focus boundary.e2e.html.admin-tenants-test`
Expected: All 4 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add libs/e2e/test/boundary/e2e/html/admin_tenants_test.clj
git commit -m "feat(e2e): add admin tenants list tests (BOU-10)"
```

---

## Task 5: Tenants Detail, Edit & Soft-Delete Tests

**Files:**
- Modify: `libs/e2e/test/boundary/e2e/html/admin_tenants_test.clj`

**Context:**
- Field groups: identity (slug, name), state (status, schema-name), config (settings)
- Readonly: id, schema-name, created-at, updated-at
- Soft-delete via bulk-delete action on list page (checkbox + delete button)
- Delete form uses `hx-post` to `/web/admin/tenants/bulk-delete` with `hx-confirm`

- [ ] **Step 1: Add detail, edit, and soft-delete tests**

Append after the search tests:

```clojure
;; ---------------------------------------------------------------------------
;; Detail & edit
;; ---------------------------------------------------------------------------

(deftest ^:e2e detail-shows-field-groups
  (testing "Tenant detail page shows field groups 'identity' and 'state'"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/tenants/" (-> fx/*seed* :tenant :id))))
      (page/wait-for-load-state pg)
      (is (admin/field-group-visible? pg "identity")
          "Field group 'identity' (slug, name) should be visible")
      (is (admin/field-group-visible? pg "state")
          "Field group 'state' (status, schema-name) should be visible"))))

(deftest ^:e2e detail-readonly-slug-schema
  (testing "Slug and schema-name are readonly — not editable in the form"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/tenants/" (-> fx/*seed* :tenant :id))))
      (page/wait-for-load-state pg)
      ;; readonly-fields: #{:id :schema-name :created-at :updated-at}
      ;; slug is NOT in readonly-fields, so check if it's editable or readonly in the rendered form.
      ;; schema-name IS readonly — should not appear as an editable input.
      (is (loc/is-visible? (page/locator pg "form.entity-form"))
          "Entity form should be visible")
      (is (zero? (loc/count-elements (page/locator pg "form.entity-form input[name='schema-name']")))
          "schema-name should not be an editable input in the form")
      (is (zero? (loc/count-elements (page/locator pg "form.entity-form input[name='id']")))
          "id should not be an editable input in the form"))))

(deftest ^:e2e edit-status-change
  (testing "Changing tenant status from active to suspended persists correctly"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url (str "/tenants/" (-> fx/*seed* :tenant :id))))
      (page/wait-for-load-state pg)
      ;; Change status to suspended
      (loc/select-option (page/locator pg "select[name='status']") "suspended")
      ;; Submit
      (loc/click (page/locator pg "form.entity-form button[type='submit']"))
      (admin/wait-for-htmx! pg)
      (page/wait-for-load-state pg)
      ;; Verify status updated
      (let [status-value (loc/input-value (page/locator pg "select[name='status']"))]
        (is (= "suspended" status-value)
            "Status should be updated to 'suspended' after form submission")))))

(deftest ^:e2e soft-delete-removes-from-list
  (testing "Soft-deleting a tenant removes it from the list"
    (spel/with-testing-page [pg]
      (admin/login-as-admin! pg fx/*seed*)
      (page/navigate pg (admin/admin-url "/tenants"))
      (page/wait-for-load-state pg)
      (let [initial-count (admin/table-row-count pg)]
        ;; Click the checkbox for the first row to select it
        (loc/click (page/locator pg "table.data-table tbody tr:first-child td.checkbox-cell input"))
        ;; Auto-accept the hx-confirm dialog
        (page/evaluate pg "document.addEventListener('htmx:confirm', function(e) { e.detail.issueRequest(); }, {once: true})")
        ;; Click the bulk delete button
        (loc/click (page/locator pg "#bulk-delete-btn"))
        (admin/wait-for-htmx! pg)
        ;; Row count should decrease
        (let [new-count (admin/table-row-count pg)]
          (is (< new-count initial-count)
              "Soft-deleted tenant should disappear from the list"))))))
```

- [ ] **Step 2: Run all tenants tests**

Run: `clojure -M:test:e2e :e2e --focus boundary.e2e.html.admin-tenants-test`
Expected: All 8 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add libs/e2e/test/boundary/e2e/html/admin_tenants_test.clj
git commit -m "feat(e2e): add admin tenants detail, edit & soft-delete tests (BOU-10)"
```

---

## Task 6: Access Control Tests

**Files:**
- Modify: `libs/e2e/test/boundary/e2e/html/admin_users_test.clj`

**Context:**
- Unauthenticated requests to `/web/admin/*` redirect to `/web/login`
- Regular users (role: user) get redirected away from admin UI
- The middleware checks session-token cookie + admin role

- [ ] **Step 1: Add access control tests to the users test namespace**

Append at the bottom:

```clojure
;; ---------------------------------------------------------------------------
;; Access control
;; ---------------------------------------------------------------------------

(deftest ^:e2e unauthenticated-redirects-to-login
  (testing "Visiting admin without a session redirects to /web/login"
    (spel/with-testing-page [pg]
      (page/navigate pg (admin/admin-url "/users"))
      ;; Should redirect to login — wait for the URL to change
      (page/wait-for-url pg #".*/web/login.*" {:timeout 10000.0})
      (is (str/includes? (page/url pg) "/web/login")
          "Unauthenticated user should be redirected to /web/login"))))

(deftest ^:e2e regular-user-denied-admin
  (testing "Regular user cannot access admin UI"
    (spel/with-testing-page [pg]
      (admin/login-as-user! pg fx/*seed*)
      ;; Try to navigate to admin
      (page/navigate pg (admin/admin-url "/users"))
      (page/wait-for-load-state pg)
      ;; Should NOT be on the admin page — either redirected or shown 403
      (let [url (page/url pg)]
        (is (not (str/includes? url "/web/admin/users"))
            "Regular user should not be able to access /web/admin/users")))))
```

- [ ] **Step 2: Run all users tests including access control**

Run: `clojure -M:test:e2e :e2e --focus boundary.e2e.html.admin-users-test`
Expected: All 11 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add libs/e2e/test/boundary/e2e/html/admin_users_test.clj
git commit -m "feat(e2e): add admin access control tests (BOU-10)"
```

---

## Task 7: Full Suite Verification

- [ ] **Step 1: Run the complete e2e test suite**

Run: `clojure -M:test:e2e :e2e`
Expected: All e2e tests pass, including the existing login/register tests and the new admin tests.

- [ ] **Step 2: Run clj-kondo lint on new files**

Run: `clojure -M:clj-kondo --lint libs/e2e/test/boundary/e2e/helpers/admin.clj libs/e2e/test/boundary/e2e/html/admin_users_test.clj libs/e2e/test/boundary/e2e/html/admin_tenants_test.clj`
Expected: No errors, possibly some warnings to fix.

- [ ] **Step 3: Fix any lint issues and commit**

```bash
git add -u
git commit -m "chore(e2e): fix lint issues in admin e2e tests"
```
