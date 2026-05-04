# Admin User Split-Table Fix Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three bugs in admin user management so that user lists, updates, and bulk-deletes work correctly with the split `auth_users`/`users` table design.

**Architecture:** All fixes are in `service.clj` and the two users.edn config files. No new abstractions. A new integration test namespace covers the real `auth_users`/`users` schema to guard against regressions.

**Tech Stack:** Clojure 1.12, next.jdbc, HoneySQL, Kaocha, H2 (test), Integrant

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `resources/conf/dev/admin/users.edn` | Modify | Add `:soft-delete true` |
| `resources/conf/test/admin/users.edn` | Modify | Add `:soft-delete true` |
| `libs/admin/src/boundary/admin/shell/service.clj` | Modify | Fix DML function + bulk-delete table |
| `libs/admin/test/boundary/admin/shell/admin_user_operations_test.clj` | Create | Integration tests against real schema |

---

## Task 1: Write integration test scaffolding

**Files:**
- Create: `libs/admin/test/boundary/admin/shell/admin_user_operations_test.clj`

- [ ] **Step 1: Create test namespace with schema setup**

```clojure
(ns boundary.admin.shell.admin-user-operations-test
  "Integration tests for admin user operations against the real auth_users/users schema.

   This namespace tests the complete admin service behavior for the split-table
   user entity: updates route fields to the correct table, soft-delete targets
   auth_users, bulk-delete uses auth_users, and list queries exclude deleted rows.

   Contrast with split-table-update-test which uses synthetic tables (test_auth /
   test_profiles). These tests use the actual production schema."
  (:require [boundary.admin.ports :as ports]
            [boundary.admin.shell.service :as service]
            [boundary.admin.shell.schema-repository :as schema-repo]
            [boundary.platform.shell.adapters.database.factory :as db-factory]
            [boundary.platform.shell.adapters.database.common.execution :as db]
            [boundary.observability.logging.shell.adapters.no-op :as logging-no-op]
            [boundary.observability.errors.shell.adapters.no-op :as error-reporting-no-op]
            [boundary.test.logging :refer [with-silent-logging]]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.util UUID]
           [java.time Instant]))

^{:kaocha.testable/meta {:integration true :admin true}}

;; =============================================================================
;; Config
;; =============================================================================

(def ^:private test-db-config
  {:adapter :h2
   :database-path nil
   :pool {:minimum-idle 1
          :maximum-pool-size 3}})

;; Entity config mirrors resources/conf/dev/admin/users.edn (and test).
;; Uses :soft-delete true so the service enables deleted_at filtering and
;; routes soft-deletes to :auth_users via :soft-delete-table.
(def ^:private admin-config
  {:enabled? true
   :base-path "/web/admin"
   :require-role :admin
   :entity-discovery {:mode :allowlist
                      :allowlist #{:admin-users}}
   :entities
   {:admin-users
    {:label           "Admin Users"
     :list-fields     [:email :name :role :active :created-at]
     :search-fields   [:email :name]
     :hide-fields     #{:deleted-at}
     :readonly-fields #{:id :mfa-enabled :created-at :updated-at}
     :table-name      :users
     :soft-delete     true
     :fields          {:email      {:type :string  :label "Email"}
                       :name       {:type :string  :label "Name"}
                       :role       {:type :enum    :label "Role"
                                    :options [[:admin "Admin"] [:user "User"]]}
                       :active     {:type :boolean :label "Active"}
                       :created-at {:type :instant :label "Created"}}
     :split-table-update {:secondary-table  :auth_users
                          :secondary-fields #{:email :active}}
     :query-overrides
     {:from          [[:auth_users :a]]
      :join          [[:users :u] [:= :a.id :u.id]]
      :select        [:a.id :a.email :a.active
                      :a.created_at :a.updated_at :a.deleted_at
                      :u.name :u.role]
      :field-aliases {:id         :a.id
                      :email      :a.email
                      :active     :a.active
                      :deleted-at :a.deleted_at
                      :created-at :a.created_at
                      :name       :u.name
                      :role       :u.role}
      :soft-delete-table :auth_users}}}
   :pagination {:default-page-size 50
                :max-page-size 200}})

;; =============================================================================
;; Setup / Teardown
;; =============================================================================

(defonce ^:dynamic *db-ctx* nil)
(defonce ^:dynamic *admin-service* nil)

(defn- create-tables! [db-ctx]
  ;; auth_users is the global identity table (owns email, active, deleted_at)
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS auth_users (
           id           UUID         PRIMARY KEY,
           email        VARCHAR(255) NOT NULL UNIQUE,
           active       BOOLEAN      NOT NULL DEFAULT TRUE,
           mfa_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
           created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
           updated_at   TIMESTAMP,
           deleted_at   TIMESTAMP,
           password_hash VARCHAR(255))"})
  ;; users is the tenant-profile table (owns name, role)
  (db/execute-update!
   db-ctx
   {:raw "CREATE TABLE IF NOT EXISTS users (
           id         UUID        PRIMARY KEY,
           name       VARCHAR(255) NOT NULL,
           role       VARCHAR(50)  NOT NULL DEFAULT 'user',
           tenant_id  UUID,
           FOREIGN KEY (id) REFERENCES auth_users(id))"}))

(defn- drop-tables! [db-ctx]
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS users"})
  (db/execute-update! db-ctx {:raw "DROP TABLE IF EXISTS auth_users"}))

(defn- setup! []
  (let [db-config (assoc test-db-config
                         :database-path
                         (str "mem:admin_user_ops_test_" (UUID/randomUUID)
                              ";DB_CLOSE_DELAY=-1"))
        db-ctx   (db-factory/db-context db-config)
        logger   (logging-no-op/create-logging-component {})
        errors   (error-reporting-no-op/create-error-reporting-component {})
        schema   (schema-repo/create-schema-repository db-ctx admin-config)
        svc      (service/create-admin-service db-ctx schema logger errors admin-config)]
    (create-tables! db-ctx)
    (alter-var-root #'*db-ctx*       (constantly db-ctx))
    (alter-var-root #'*admin-service* (constantly svc))))

(defn- teardown! []
  (when *db-ctx*
    (drop-tables! *db-ctx*)
    (db-factory/close-db-context! *db-ctx*)
    (alter-var-root #'*db-ctx*       (constantly nil))
    (alter-var-root #'*admin-service* (constantly nil))))

(defn- clean-tables [f]
  (when *db-ctx*
    (db/execute-update! *db-ctx* {:raw "DELETE FROM users"})
    (db/execute-update! *db-ctx* {:raw "DELETE FROM auth_users"}))
  (f))

(use-fixtures :once (fn [f] (setup!) (f) (teardown!)))
(use-fixtures :each clean-tables)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- insert-user!
  "Insert matching rows into auth_users and users. Returns UUID."
  ([email name] (insert-user! email name true "user"))
  ([email name active role]
   (let [id      (UUID/randomUUID)
         now-str (.toString (Instant/now))]
     (db/execute-update! *db-ctx*
                         {:raw (str "INSERT INTO auth_users (id, email, active, created_at) "
                                    "VALUES ('" id "', '" email "', " active ", '" now-str "')")})
     (db/execute-update! *db-ctx*
                         {:raw (str "INSERT INTO users (id, name, role) "
                                    "VALUES ('" id "', '" name "', '" role "')")})
     id)))

(defn- fetch-auth [id]
  (db/execute-one! *db-ctx* {:select [:*] :from [:auth_users] :where [:= :id id]}))

(defn- fetch-profile [id]
  (db/execute-one! *db-ctx* {:select [:*] :from [:users] :where [:= :id id]}))
```

- [ ] **Step 2: Run existing tests to establish baseline**

```bash
clojure -M:test:db/h2 :admin
```
Expected: existing tests pass (do not regress). New namespace doesn't exist yet.

---

## Task 2: Write update tests (cover `execute-one!` / `execute-update!` fix)

**Files:**
- Modify: `libs/admin/test/boundary/admin/shell/admin_user_operations_test.clj`

- [ ] **Step 1: Append update tests to the namespace**

```clojure
;; =============================================================================
;; Update tests
;; =============================================================================

(deftest update-primary-field-persists-to-users-table
  (testing "Updating :name (primary field) writes to users table"
    (let [id (insert-user! "primary@test.com" "OldName")]
      (ports/update-entity *admin-service* :admin-users id {:name "NewName"})
      (is (= "NewName" (:name (fetch-profile id)))
          "name must be updated in users table")))

  (testing "Updating :role (primary field) writes to users table"
    (let [id (insert-user! "role@test.com" "RoleUser" true "user")]
      (ports/update-entity *admin-service* :admin-users id {:role "admin"})
      (is (= "admin" (:role (fetch-profile id)))
          "role must be updated in users table"))))

(deftest update-secondary-field-persists-to-auth-users-table
  (testing "Updating :email (secondary field) writes to auth_users table"
    (let [id (insert-user! "old@test.com" "EmailUser")]
      (ports/update-entity *admin-service* :admin-users id {:email "new@test.com"})
      (is (= "new@test.com" (:email (fetch-auth id)))
          "email must be updated in auth_users table")))

  (testing "Updating :active (secondary field) writes to auth_users table"
    (let [id (insert-user! "active@test.com" "ActiveUser" true "user")]
      (ports/update-entity *admin-service* :admin-users id {:active false})
      (is (false? (:active (fetch-auth id)))
          "active must be updated in auth_users table"))))

(deftest update-mixed-fields-updates-both-tables-atomically
  (testing "Updating fields across both tables in one call"
    (let [id (insert-user! "mixed@test.com" "MixedUser")]
      (ports/update-entity *admin-service* :admin-users id
                           {:name "Updated" :email "mixed-new@test.com" :active false})
      (is (= "Updated" (:name (fetch-profile id)))
          "name must persist to users")
      (is (= "mixed-new@test.com" (:email (fetch-auth id)))
          "email must persist to auth_users")
      (is (false? (:active (fetch-auth id)))
          "active must persist to auth_users"))))

(deftest update-returns-full-joined-record
  (testing "update-entity return value includes fields from both tables"
    (let [id     (insert-user! "return@test.com" "ReturnUser")
          result (ports/update-entity *admin-service* :admin-users id {:name "ReturnNew"})]
      (is (some? result) "must return a record")
      (is (= "ReturnNew" (:name result)) "returned record must reflect updated name")
      (is (some? (:email result)) "returned record must include email from auth_users"))))

(deftest inline-edit-secondary-field-routes-to-auth-users
  (testing "update-entity-field for :email writes to auth_users"
    (let [id (insert-user! "inline-email@test.com" "InlineUser")]
      (ports/update-entity-field *admin-service* :admin-users id :email "inline-new@test.com")
      (is (= "inline-new@test.com" (:email (fetch-auth id)))
          "email must persist to auth_users via inline edit")))

  (testing "update-entity-field for :active writes to auth_users"
    (let [id (insert-user! "inline-active@test.com" "InlineActive" true "user")]
      (ports/update-entity-field *admin-service* :admin-users id :active false)
      (is (false? (:active (fetch-auth id)))
          "active must persist to auth_users via inline edit"))))

(deftest inline-edit-primary-field-routes-to-users
  (testing "update-entity-field for :name writes to users"
    (let [id (insert-user! "inline-name@test.com" "Before")]
      (ports/update-entity-field *admin-service* :admin-users id :name "After")
      (is (= "After" (:name (fetch-profile id)))
          "name must persist to users via inline edit"))))

(deftest transaction-rollback-on-secondary-failure
  (with-silent-logging
    (testing "When secondary UPDATE fails, primary UPDATE rolls back"
      (let [id-a (insert-user! "tx-a@test.com" "User A")
            _    (insert-user! "tx-b@test.com" "User B")]
        ;; Try to set A's email to B's (UNIQUE constraint violation)
        (is (thrown? Exception
                     (ports/update-entity *admin-service* :admin-users id-a
                                          {:email "tx-b@test.com" :name "Collided"})))
        ;; A's name must still be "User A" — primary UPDATE rolled back
        (is (= "User A" (:name (fetch-profile id-a))))
        (is (= "tx-a@test.com" (:email (fetch-auth id-a))))))))
```

- [ ] **Step 2: Run update tests**

```bash
clojure -M:test:db/h2 --focus boundary.admin.shell.admin-user-operations-test
```
Expected: All update tests pass. If any fail, the root cause is in `service.clj` (update path).

---

## Task 3: Write soft-delete and list tests

**Files:**
- Modify: `libs/admin/test/boundary/admin/shell/admin_user_operations_test.clj`

- [ ] **Step 1: Append soft-delete and list tests**

```clojure
;; =============================================================================
;; Soft-delete and list tests
;; =============================================================================

(deftest list-excludes-soft-deleted-users
  (testing "Soft-deleted users do not appear in list"
    (let [live-id    (insert-user! "live@test.com" "Live User")
          deleted-id (insert-user! "deleted@test.com" "Deleted User")]
      ;; Soft-delete one user via the service (goes to auth_users.deleted_at)
      (ports/delete-entity *admin-service* :admin-users deleted-id)
      (let [result  (ports/list-entities *admin-service* :admin-users {})
            records (:records result)
            ids     (set (map :id records))]
        (is (contains? ids live-id)
            "live user must appear in list")
        (is (not (contains? ids deleted-id))
            "soft-deleted user must NOT appear in list")))))

(deftest delete-entity-soft-deletes-on-auth-users
  (testing "delete-entity sets deleted_at on auth_users, not users"
    (let [id (insert-user! "softdel@test.com" "SoftDel")]
      (ports/delete-entity *admin-service* :admin-users id)
      ;; auth_users.deleted_at must be set
      (let [auth-row (fetch-auth id)]
        (is (some? (:deleted_at auth-row))
            "auth_users.deleted_at must be set after soft-delete"))
      ;; users row must still exist (not hard-deleted)
      (let [profile-row (fetch-profile id)]
        (is (some? profile-row)
            "users row must still exist after soft-delete")))))

(deftest delete-entity-sets-active-false-on-soft-delete
  (testing "delete-entity sets active=false on auth_users during soft-delete"
    (let [id (insert-user! "deactivate@test.com" "ActiveUser" true "user")]
      (ports/delete-entity *admin-service* :admin-users id)
      (let [auth-row (fetch-auth id)]
        (is (false? (:active auth-row))
            "active must be false after soft-delete")))))
```

- [ ] **Step 2: Run list/soft-delete tests**

```bash
clojure -M:test:db/h2 --focus boundary.admin.shell.admin-user-operations-test
```
Expected: All soft-delete and list tests pass (service already handles soft-delete correctly for single-delete via `delete-entity`).

---

## Task 4: Write bulk-delete tests (these WILL fail before the fix)

**Files:**
- Modify: `libs/admin/test/boundary/admin/shell/admin_user_operations_test.clj`

- [ ] **Step 1: Append bulk-delete tests**

```clojure
;; =============================================================================
;; Bulk-delete tests
;; =============================================================================

(deftest bulk-delete-soft-deletes-on-auth-users
  (testing "bulk-delete-entities sets deleted_at on auth_users rows"
    (let [id-1 (insert-user! "bulk1@test.com" "Bulk One")
          id-2 (insert-user! "bulk2@test.com" "Bulk Two")
          id-3 (insert-user! "bulk3@test.com" "Bulk Three - keep")]
      (ports/bulk-delete-entities *admin-service* :admin-users [id-1 id-2])
      ;; deleted_at must be set on auth_users for id-1 and id-2
      (let [auth-1 (fetch-auth id-1)
            auth-2 (fetch-auth id-2)
            auth-3 (fetch-auth id-3)]
        (is (some? (:deleted_at auth-1))
            "auth_users.deleted_at must be set for bulk-deleted user 1")
        (is (some? (:deleted_at auth-2))
            "auth_users.deleted_at must be set for bulk-deleted user 2")
        (is (nil? (:deleted_at auth-3))
            "auth_users.deleted_at must NOT be set for kept user")))))

(deftest bulk-delete-does-not-delete-users-rows
  (testing "bulk-delete-entities does not hard-delete users table rows"
    (let [id-1 (insert-user! "nodelbulk1@test.com" "NoDel One")
          id-2 (insert-user! "nodelbulk2@test.com" "NoDel Two")]
      (ports/bulk-delete-entities *admin-service* :admin-users [id-1 id-2])
      ;; users rows must still exist (FK integrity)
      (is (some? (fetch-profile id-1))
          "users row must still exist after bulk soft-delete")
      (is (some? (fetch-profile id-2))
          "users row must still exist after bulk soft-delete"))))

(deftest bulk-deleted-users-excluded-from-list
  (testing "Users bulk-deleted do not appear in list"
    (let [id-1 (insert-user! "bulklist1@test.com" "BulkList One")
          id-2 (insert-user! "bulklist2@test.com" "BulkList Two")
          id-3 (insert-user! "bulklist3@test.com" "BulkList Three - keep")]
      (ports/bulk-delete-entities *admin-service* :admin-users [id-1 id-2])
      (let [result  (ports/list-entities *admin-service* :admin-users {})
            ids     (set (map :id (:records result)))]
        (is (not (contains? ids id-1)) "bulk-deleted user must not appear in list")
        (is (not (contains? ids id-2)) "bulk-deleted user must not appear in list")
        (is (contains? ids id-3)       "non-deleted user must appear in list")))))
```

- [ ] **Step 2: Run bulk-delete tests (expect FAILURE)**

```bash
clojure -M:test:db/h2 --focus boundary.admin.shell.admin-user-operations-test
```
Expected: `bulk-delete-soft-deletes-on-auth-users` and related tests FAIL. The error will be a SQL exception because `bulk-delete-entities` targets `:users` (which has no `deleted_at` column) instead of `:auth_users`.

- [ ] **Step 3: Commit failing tests**

```bash
git add libs/admin/test/boundary/admin/shell/admin_user_operations_test.clj
git commit -m "test(admin): add integration tests for admin user split-table operations"
```

---

## Task 5: Fix `bulk-delete-entities` — wrong soft-delete table

**Files:**
- Modify: `libs/admin/src/boundary/admin/shell/service.clj`

The bug is at lines 608–640. `bulk-delete-entities` never calls `resolve-query-config`, so `soft-delete-table` is never bound. The soft-delete branch uses `table-name` (`:users`) instead of `soft-delete-table` (`:auth_users`).

- [ ] **Step 1: Read the current `bulk-delete-entities` implementation**

Current code (lines 608–641 of `service.clj`):

```clojure
(bulk-delete-entities [_ entity-name ids]
  (persist-interceptors/execute-persistence-operation
   :admin-bulk-delete-entities
   {:entity (name entity-name) :count (count ids)}
   (fn [{:keys [_params]}]
     (let [entity-config (ports/get-entity-config schema-provider entity-name)
           table-name (:table-name entity-config)
           primary-key (:primary-key entity-config :id)
           soft-delete? (:soft-delete entity-config false)

           ;; Convert UUIDs to strings at database boundary
           id-strings (mapv type-conversion/uuid->string ids)
           now-str (type-conversion/instant->string (Instant/now))

           ;; Check if entity has an 'active' field to set on soft-delete
           has-active-field? (contains? (:fields entity-config) :active)
           soft-delete-data-kebab (cond-> {:deleted-at now-str}
                                    has-active-field? (assoc :active false))
           ;; Convert to snake_case for database
           soft-delete-data (case-conversion/kebab-case->snake-case-map soft-delete-data-kebab)

           query (if soft-delete?
                   {:update table-name                     ;; BUG: should be soft-delete-table
                    :set soft-delete-data
                    :where [:in primary-key id-strings]}
                   {:delete-from table-name
                    :where [:in primary-key id-strings]})

           affected-count (db/execute-update! db-ctx query)]

       {:success-count affected-count
        :failed-count (- (count ids) affected-count)
        :errors []}))
   db-ctx))
```

- [ ] **Step 2: Apply the fix**

Replace the `let` bindings block inside `bulk-delete-entities`. Add `resolve-query-config` call to obtain `soft-delete-table`, then use it in the soft-delete query branch:

```clojure
(bulk-delete-entities [_ entity-name ids]
  (persist-interceptors/execute-persistence-operation
   :admin-bulk-delete-entities
   {:entity (name entity-name) :count (count ids)}
   (fn [{:keys [_params]}]
     (let [entity-config (ports/get-entity-config schema-provider entity-name)
           table-name (:table-name entity-config)
           primary-key (:primary-key entity-config :id)
           soft-delete? (:soft-delete entity-config false)
           {:keys [soft-delete-table]} (resolve-query-config entity-config)

           ;; Convert UUIDs to strings at database boundary
           id-strings (mapv type-conversion/uuid->string ids)
           now-str (type-conversion/instant->string (Instant/now))

           ;; Check if entity has an 'active' field to set on soft-delete
           has-active-field? (contains? (:fields entity-config) :active)
           soft-delete-data-kebab (cond-> {:deleted-at now-str}
                                    has-active-field? (assoc :active false))
           ;; Convert to snake_case for database
           soft-delete-data (case-conversion/kebab-case->snake-case-map soft-delete-data-kebab)

           query (if soft-delete?
                   {:update soft-delete-table             ;; FIXED: use auth_users not users
                    :set soft-delete-data
                    :where [:in primary-key id-strings]}
                   {:delete-from table-name
                    :where [:in primary-key id-strings]})

           affected-count (db/execute-update! db-ctx query)]

       {:success-count affected-count
        :failed-count (- (count ids) affected-count)
        :errors []}))
   db-ctx))
```

- [ ] **Step 3: Run bulk-delete tests**

```bash
clojure -M:test:db/h2 --focus boundary.admin.shell.admin-user-operations-test
```
Expected: All bulk-delete tests PASS.

- [ ] **Step 4: Run full admin test suite**

```bash
clojure -M:test:db/h2 :admin
```
Expected: All tests pass. No regressions.

- [ ] **Step 5: Commit**

```bash
git add libs/admin/src/boundary/admin/shell/service.clj
git commit -m "fix(admin): bulk-delete-entities uses soft-delete-table instead of table-name"
```

---

## Task 6: Fix `update-entity` and `update-entity-field` — use `execute-update!` for DML

**Files:**
- Modify: `libs/admin/src/boundary/admin/shell/service.clj`

`execute-one!` = `(first (execute-query! ...))`. This is semantically a SELECT operation. For DML, the correct function is `execute-update!` which uses the right JDBC path and returns an integer row count. Using `execute-one!` for DML works in H2 (test) but is semantically wrong and may behave differently in PostgreSQL or future next.jdbc versions.

- [ ] **Step 1: Fix `update-entity` split-table branch (lines 436–443)**

Current (lines 436–443):
```clojure
(db/with-transaction* db-ctx
  (fn [tx]
    (when (seq primary-db)
      (db/execute-one! tx {:update table-name
                           :set    primary-db
                           :where  [:= primary-key id-str]}))
    (when (seq secondary-db)
      (db/execute-one! tx {:update secondary-table
                           :set    secondary-db
                           :where  [:= primary-key id-str]}))))
```

Change both `execute-one!` to `execute-update!`:
```clojure
(db/with-transaction* db-ctx
  (fn [tx]
    (when (seq primary-db)
      (db/execute-update! tx {:update table-name
                              :set    primary-db
                              :where  [:= primary-key id-str]}))
    (when (seq secondary-db)
      (db/execute-update! tx {:update secondary-table
                              :set    secondary-db
                              :where  [:= primary-key id-str]}))))
```

- [ ] **Step 2: Fix `update-entity-field` (line 519)**

Current:
```clojure
_ (db/execute-one! db-ctx update-query)
```

Change to:
```clojure
_ (db/execute-update! db-ctx update-query)
```

- [ ] **Step 3: Run all update tests**

```bash
clojure -M:test:db/h2 --focus boundary.admin.shell.admin-user-operations-test
```
Expected: All tests pass.

- [ ] **Step 4: Run full admin test suite**

```bash
clojure -M:test:db/h2 :admin
```
Expected: All tests pass including existing `split_table_update_test`.

- [ ] **Step 5: Commit**

```bash
git add libs/admin/src/boundary/admin/shell/service.clj
git commit -m "fix(admin): use execute-update! for DML in update-entity and update-entity-field"
```

---

## Task 7: Add `:soft-delete true` to users.edn configs

**Files:**
- Modify: `resources/conf/dev/admin/users.edn`
- Modify: `resources/conf/test/admin/users.edn`

Without `:soft-delete true`:
- `soft-delete? = false` throughout `service.clj`
- List queries include soft-deleted users (no `WHERE deleted_at IS NULL`)
- Single and bulk delete issue hard DELETE against `:users` instead of soft-delete against `:auth_users`

- [ ] **Step 1: Add `:soft-delete true` to dev config**

In `resources/conf/dev/admin/users.edn`, add `:soft-delete true` after `:table-name :users`:

```clojure
:table-name      :users
:soft-delete     true
:readonly-fields #{:id :mfa-enabled :created-at :updated-at}
```

- [ ] **Step 2: Add `:soft-delete true` to test config**

Same change in `resources/conf/test/admin/users.edn`:

```clojure
:table-name      :users
:soft-delete     true
:readonly-fields #{:id :mfa-enabled :created-at :updated-at}
```

- [ ] **Step 3: Validate configs**

```bash
bb doctor
```
Expected: No errors.

- [ ] **Step 4: Run full admin test suite**

```bash
clojure -M:test:db/h2 :admin
```
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add resources/conf/dev/admin/users.edn resources/conf/test/admin/users.edn
git commit -m "fix(admin): add :soft-delete true to users entity config"
```

---

## Task 8: Final verification

- [ ] **Step 1: Run all tests**

```bash
clojure -M:test:db/h2
```
Expected: All test suites pass.

- [ ] **Step 2: Run linter**

```bash
clojure -M:clj-kondo --lint libs/admin/src libs/admin/test
```
Expected: No new warnings.

- [ ] **Step 3: Run quality checks**

```bash
bb check
```
Expected: All checks pass.

- [ ] **Step 4: Summary of changes**

| Bug | Fix | Location |
|---|---|---|
| Soft-deleted users appear in admin list | Add `:soft-delete true` | `resources/conf/*/admin/users.edn` |
| Bulk-delete corrupts data (wrong table) | Use `soft-delete-table` from `resolve-query-config` | `service.clj` `bulk-delete-entities` |
| DML uses wrong function | `execute-one!` → `execute-update!` in two places | `service.clj` `update-entity` + `update-entity-field` |
| No regression safety net | New integration test namespace | `admin_user_operations_test.clj` |
