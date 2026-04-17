# Login E2E Implementation Plan (Clojure + spel)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an end-to-end test suite that covers `/web/login`, `/web/register`, and `/api/v1/auth/*` flows for the Boundary platform, runnable via `bb e2e` locally and in CI, with clean H2 state per test.

**Architecture:** A test-only `POST /test/reset` HTTP endpoint (mounted only when a profile flag is set) truncates H2 and re-seeds baseline tenant/users via production services. End-to-end tests run serially against a single app instance, using [spel](https://github.com/Blockether/spel) — an idiomatic Clojure wrapper around Playwright Java — for both browser automation and API testing. A `use-fixtures :each` hook resets DB state before every test.

**Tech Stack:** Clojure throughout — no JavaScript/TypeScript/Node.js/npm. Core pieces: Integrant, reitit, next.jdbc, HoneySQL, Babashka (`bb e2e`), Kaocha (test runner), `com.blockether/spel` (Playwright Java wrapper), `one-time` (TOTP, already in repo), `clj-http` (HTTP client, already in repo), GitHub Actions.

**Why Clojure+spel instead of TypeScript+@playwright/test:** keeps the monorepo single-ecosystem (JVM/Clojure), avoids introducing Node/npm/TypeScript to dev and CI environments, integrates with the existing Kaocha runner and `^:unit` / `^:integration` / `^:contract` / `^:e2e` metadata convention, and reuses already-vetted Clojars dependencies (`one-time`, `clj-http`) for TOTP and HTTP.

**Spec:** `docs/superpowers/specs/2026-04-05-playwright-login-e2e-design.md`

---

## Reference facts

Engineer: read these before starting. They are already verified in the repo.

- **Login route:** `/web/login` GET/POST, defined at `libs/user/src/boundary/user/shell/http.clj:495-500`. Handler `web-handlers/login-page-handler` (GET, `web_handlers.clj:306`) and `web-handlers/login-submit-handler` (POST, `web_handlers.clj:323`).
- **Register route:** `/web/register` GET/POST, same `http.clj`, handlers `register-page-handler` (`web_handlers.clj:481`) and `register-submit-handler`.
- **Login form template:** `libs/user/src/boundary/user/core/ui.clj:659` `login-form`. Class `form-card ui-form-shell`. Fields: `email`, `password`, `remember` (checkbox), `return-to` (hidden).
- **MFA second-step form:** `ui.clj:722` `mfa-login-form`. Field `mfa-code`, plus hidden `email`, `password`, `remember`, `return-to`.
- **Register form template:** `ui.clj:800` `register-form`. Fields: `name`, `email`, `password`.
- **Session cookie:** `session-token` (httpOnly, path `/`, max-age = 30 days if `remember=on`, no max-age otherwise — session cookie). Set in `web_handlers.clj:379-388`.
- **`remembered-email` cookie:** set when `remember=on` on successful login, max-age 30 days, read back by `login-page-handler` to prefill `:email` + check `:remember` (`web_handlers.clj:310-316`).
- **Default redirect after login:** admins → `/web/admin/users`, others → `/web/dashboard` (`web_handlers.clj:352-356`).
- **API auth routes (VERIFIED from http.clj:338-468):** all API routes are prefixed with `/api/v1` by `boundary.platform.shell.http.versioning/apply-versioning`. Real paths:
  - `POST /api/v1/auth/login` — body `{email, password, deviceInfo?}` — schema is `:closed`, does **not** accept `mfaCode`.
  - `POST /api/v1/auth/register` — body `{email, password, name, ...}`.
  - `POST /api/v1/auth/mfa/setup` — authenticated, no body — returns `{secret, qrCodeUrl, backupCodes, issuer, accountName}`.
  - `POST /api/v1/auth/mfa/enable` — authenticated, body `{secret, backupCodes, verificationCode}` — all three required, schema is `:closed`.
  - `POST /api/v1/auth/mfa/disable` — authenticated, **no body schema** — just disables for the authenticated user.
  - `GET /api/v1/auth/mfa/status` — authenticated.
  - `POST /api/v1/sessions` — create session by userId or by {email,password}.
  - `GET /api/v1/sessions/:token` — validate session.
  - `DELETE /api/v1/sessions/:token` — invalidate session.
  - **There is NO `GET /api/v1/sessions` list endpoint.**
  - Unversioned `/api/auth/login` etc. exist only as 307 redirects to `/api/v1/...`.
- **MFA + API login (CRITICAL):** MFA-gated login cannot be tested via the JSON API — no `mfaCode` field exists in the login schema. MFA second-step is only implemented in the HTML `mfa-login-form` flow on `/web/login`. Tests for MFA-during-login belong in `html/web_login_test.clj`, not any API spec.
- **User service:** `libs/user/src/boundary/user/shell/service.clj` — `UserService` record, `register-user` (line 116).
- **Tenant service:** `libs/tenant/src/boundary/tenant/shell/service.clj` — `create-new-tenant` (line 62).
- **Config:** Aero-based, loaded via `src/boundary/config.clj`. Profile env var `BND_ENV` (`test`/`dev`/`prod`/`acc`). Test config at `resources/conf/test/config.edn`.
- **Router composition:** `src/boundary/config.clj`, function `user-module-config` — merges user/tenant/membership/admin/workflow/search routes into the Integrant `:boundary/http-handler` component.
- **H2 in tests:** `:boundary/h2 {:memory true :pool {...}}` in `resources/conf/test/config.edn`. JDBC URL `jdbc:h2:mem:boundary;DB_CLOSE_DELAY=-1`.
- **H2 truncate gotcha:** H2 does not support `TRUNCATE ... CASCADE`. Use `SET REFERENTIAL_INTEGRITY FALSE` → `TRUNCATE TABLE <t>` per table → `SET REFERENTIAL_INTEGRITY TRUE`.
- **Tables:** `users`, `sessions`, `audit_logs`, `tenants`, `tenant_memberships`, `tenant_member_invites`.
- **FC/IS checker:** `libs/tools/src/boundary/tools/check_fcis.clj`. Scans `libs/*/src/boundary/*/core/**`. Anything under `src/boundary/test_support/core/**` is **not** in that glob by default — the check will silently not cover it. Task 2 addresses this.
- **CI file:** `.github/workflows/ci.yml`. Existing jobs: `lint`, `build-ui-assets`, `docs-lint`.
- **Babashka tasks:** `bb.edn`. Pattern uses `(apply <ns>/-main *command-line-args*)` or `(clojure "-M:<alias>")`.

---

## File structure

### New files

```
src/boundary/test_support/
├── core.clj                  # Pure: baseline seed spec data
└── shell/
    ├── reset.clj             # Side-effecting: truncate + seed via prod services
    └── handler.clj           # HTTP wrapper: POST /test/reset

resources/conf/test/config.edn       # MODIFY: add :test/reset-endpoint-enabled? true

src/boundary/config.clj               # MODIFY: conditionally mount test-support component + route

test/boundary/test_support/
└── core_test.clj             # :unit tests for seed-spec
test/boundary/test_support/shell/
└── reset_test.clj            # :contract tests against H2

bb.edn                               # MODIFY: add e2e, run-e2e-server tasks
deps.edn                              # MODIFY: add :e2e alias with spel dep
tests.edn                             # MODIFY: add :e2e kaocha suite

libs/e2e/                             # NEW sub-library — test code only
├── deps.edn                          # spel + kaocha + one-time + clj-http
├── src/boundary/e2e/README.md        # placeholder; no production code here
└── test/boundary/e2e/
    ├── helpers/
    │   ├── reset.clj                 # POST /test/reset via clj-http
    │   ├── users.clj                 # login/register/MFA via spel api + clj-http
    │   └── cookies.clj               # Set-Cookie parsing + HttpOnly assertions
    ├── fixtures.clj                  # use-fixtures :each + spel with-testing-page helper
    ├── totp.clj                      # one-time wrapper (thin)
    ├── api/
    │   ├── auth_login_test.clj       # 3 tests
    │   ├── auth_register_test.clj    # 3 tests
    │   ├── auth_mfa_test.clj         # 4 tests (setup, enable-ok, enable-wrong, disable+status)
    │   └── auth_sessions_test.clj    # 5 tests (validate, revoke, unauth, lockout, hash-leak)
    └── html/
        ├── web_login_test.clj        # ~10 tests incl. MFA second-step form
        └── web_register_test.clj     # 3 tests

.github/workflows/ci.yml              # MODIFY: add e2e job
```

### Why `src/boundary/test_support/` and not `libs/test-support/`

This code must never ship as a library consumers depend on. Placing it under the main app `src/` makes that impossible by construction. Trade-off: the `bb check:fcis` default globs only scan `libs/*/src/boundary/*/core/**` — see Task 2.

---

## Phase 1 — Test-support server-side (reset endpoint)

### Task 1: Baseline seed spec (pure core)

**Files:**
- Create: `src/boundary/test_support/core.clj`
- Test: `test/boundary/test_support/core_test.clj`

- [ ] **Step 1: Write failing test**

Create `test/boundary/test_support/core_test.clj`:

```clojure
(ns boundary.test-support.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.test-support.core :as tsc]))

(deftest ^:unit baseline-seed-spec-test
  (testing "returns tenant + admin + regular user"
    (let [spec (tsc/baseline-seed-spec)]
      (is (= "acme" (-> spec :tenant :slug)))
      (is (= "Acme Test" (-> spec :tenant :name)))
      (is (= "admin@acme.test" (-> spec :admin :email)))
      (is (= :admin (-> spec :admin :role)))
      (is (= "user@acme.test" (-> spec :user :email)))
      (is (= :user (-> spec :user :role)))
      (is (every? #(>= (count (:password %)) 12)
                  [(:admin spec) (:user spec)])))))

(deftest ^:unit empty-seed-spec-test
  (testing "empty seed has no entities"
    (is (= {} (tsc/empty-seed-spec)))))
```

- [ ] **Step 2: Run test, verify it fails**

```bash
clojure -M:test:db/h2 --focus boundary.test-support.core-test
```

Expected: FAIL, "Could not locate boundary/test_support/core__init.class".

- [ ] **Step 3: Write minimal implementation**

Create `src/boundary/test_support/core.clj`:

```clojure
(ns boundary.test-support.core
  "Pure seed specifications for Playwright e2e tests.

   This namespace is FC-pure: no I/O, no logging, no DB. It only describes
   what the baseline seed should look like. The shell side (reset.clj)
   translates these specs into actual persistence operations via the
   production user and tenant services.")

(def ^:private default-password "Test-Pass-1234!")

(defn baseline-seed-spec
  "Returns a data description of the baseline test fixture: one tenant
   with one admin and one regular user. All passwords are identical and
   intentionally plain text so test helpers can log in with them."
  []
  {:tenant {:slug "acme" :name "Acme Test" :status :active}
   :admin  {:email    "admin@acme.test"
            :name     "Admin User"
            :password default-password
            :role     :admin}
   :user   {:email    "user@acme.test"
            :name     "Regular User"
            :password default-password
            :role     :user}})

(defn empty-seed-spec
  "Returns an empty seed — used by tests that need a pristine DB."
  []
  {})
```

- [ ] **Step 4: Run test, verify it passes**

```bash
clojure -M:test:db/h2 --focus boundary.test-support.core-test
```

Expected: PASS, 2 tests, 5 assertions.

- [ ] **Step 5: Commit**

```bash
git add src/boundary/test_support/core.clj test/boundary/test_support/core_test.clj
git commit -m "Add pure baseline seed spec for e2e test support"
```

---

### Task 2: Extend FC/IS checker to cover test-support

**Files:**
- Modify: `libs/tools/src/boundary/tools/check_fcis.clj`
- Test: `libs/tools/test/boundary/tools/check_fcis_test.clj` (create if missing, else extend)

- [ ] **Step 1: Read the current FC/IS checker to find the scan root**

```bash
```

Read `libs/tools/src/boundary/tools/check_fcis.clj` top-to-bottom. Find the function that enumerates paths/files. It is probably globbing `libs/*/src/boundary/*/core/**/*.clj` or similar.

- [ ] **Step 2: Write failing test**

Create or extend `libs/tools/test/boundary/tools/check_fcis_test.clj`:

```clojure
(ns boundary.tools.check-fcis-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.tools.check-fcis :as fcis]))

(deftest ^:unit includes-test-support-core
  (testing "the FC/IS scanner includes src/boundary/test_support/core"
    (let [scanned-paths (fcis/core-source-paths)]  ;; function to add
      (is (some #(re-find #"test_support/core" %) scanned-paths)))))
```

Run:
```bash
clojure -M:test:db/h2 --focus boundary.tools.check-fcis-test
```
Expected: FAIL (function `core-source-paths` does not exist).

- [ ] **Step 3: Implement**

In `check_fcis.clj`:

1. Extract the existing path-enumeration logic into a public `core-source-paths` function.
2. Add `src/boundary/test_support/core.clj` (and any future test-support cores) to the set of scanned paths. Simplest: hardcode an extra glob `src/boundary/test_support/core.clj` alongside the existing `libs/*/src/boundary/*/core/**/*.clj`.

If the current checker already has an extensible config data structure, add the path there. If not, the simplest change is a concat in the path-gathering function.

- [ ] **Step 4: Run test**

```bash
clojure -M:test:db/h2 --focus boundary.tools.check-fcis-test
bb check:fcis
```

Expected: test passes; `bb check:fcis` still passes against the existing codebase (no regressions).

- [ ] **Step 5: Commit**

```bash
git add libs/tools/src/boundary/tools/check_fcis.clj libs/tools/test/boundary/tools/check_fcis_test.clj
git commit -m "Extend FC/IS checker to scan src/boundary/test_support/core"
```

---

### Task 3: Truncate helper (shell, contract test)

**Files:**
- Create: `src/boundary/test_support/shell/reset.clj`
- Test: `test/boundary/test_support/shell/reset_test.clj`

- [ ] **Step 1: Write failing contract test**

Create `test/boundary/test_support/shell/reset_test.clj`:

```clojure
(ns boundary.test-support.shell.reset-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [boundary.test-support.shell.reset :as reset]
            [boundary.test.fixtures :as fixtures]))  ;; existing fixture ns

;; NOTE to engineer: look at how other contract tests bootstrap a transactional
;; H2 datasource. Example: libs/user/test/boundary/user/shell/persistence_test.clj.
;; Reuse the same pattern (fixture, integrant component, or direct jdbc/get-datasource).

(use-fixtures :each fixtures/h2-datasource)  ;; placeholder — match the convention
(def ^:dynamic *ds* nil)

(deftest ^:contract truncate-all-removes-rows
  (testing "truncate! empties users, tenants, sessions"
    (jdbc/execute! *ds* ["INSERT INTO tenants (id, name, slug, status) VALUES (?, ?, ?, ?)"
                         (random-uuid) "Tmp" "tmp" "active"])
    (is (= 1 (-> (jdbc/execute-one! *ds* ["SELECT COUNT(*) AS c FROM tenants"]) :c)))
    (reset/truncate-all! *ds*)
    (is (= 0 (-> (jdbc/execute-one! *ds* ["SELECT COUNT(*) AS c FROM tenants"]) :c)))))
```

**Engineer note:** The exact fixture import name above is a placeholder. Before writing the test, read one existing contract test (e.g. `libs/user/test/boundary/user/shell/persistence_test.clj`) to learn the real H2 datasource bootstrap pattern this repo uses, and mirror it exactly. Do not invent a new fixture pattern.

- [ ] **Step 2: Run test, verify it fails**

```bash
clojure -M:test:db/h2 --focus boundary.test-support.shell.reset-test
```

Expected: FAIL (namespace does not exist).

- [ ] **Step 3: Implement `truncate-all!`**

Create `src/boundary/test_support/shell/reset.clj`:

```clojure
(ns boundary.test-support.shell.reset
  "Side-effecting reset of the H2 test database.
   Safe to call only in the :test profile."
  (:require [next.jdbc :as jdbc]
            [clojure.tools.logging :as log]))

(def ^:private tables-in-truncation-order
  ;; Order does not matter since we disable referential integrity,
  ;; but listing child tables first documents the foreign-key graph.
  ["sessions"
   "audit_logs"
   "tenant_memberships"
   "tenant_member_invites"
   "users"
   "tenants"])

(defn truncate-all!
  "Truncates every table the e2e suite might touch. Uses H2's
   SET REFERENTIAL_INTEGRITY FALSE because H2 does not support
   TRUNCATE ... CASCADE."
  [ds]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute! tx ["SET REFERENTIAL_INTEGRITY FALSE"])
    (try
      (doseq [t tables-in-truncation-order]
        (jdbc/execute! tx [(str "TRUNCATE TABLE " t)]))
      (finally
        (jdbc/execute! tx ["SET REFERENTIAL_INTEGRITY TRUE"]))))
  (log/debug "test-support: truncated all tables"))
```

- [ ] **Step 4: Run test, verify pass**

```bash
clojure -M:test:db/h2 --focus boundary.test-support.shell.reset-test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/boundary/test_support/shell/reset.clj test/boundary/test_support/shell/reset_test.clj
git commit -m "Add H2 truncate helper for test-support reset"
```

---

### Task 4: Seed helper (calls production services)

**Files:**
- Modify: `src/boundary/test_support/shell/reset.clj`
- Test: `test/boundary/test_support/shell/reset_test.clj`

- [ ] **Step 1: Write failing test**

Append to `reset_test.clj`:

```clojure
(deftest ^:contract seed-baseline-creates-entities
  (testing "seed-baseline! creates tenant + admin + user + returns IDs"
    (reset/truncate-all! *ds*)
    (let [user-svc   (fixtures/user-service *ds*)     ;; look up real constructor
          tenant-svc (fixtures/tenant-service *ds*)
          result (reset/seed-baseline! {:user-service user-svc
                                        :tenant-service tenant-svc})]
      (is (some? (-> result :tenant :id)))
      (is (= "admin@acme.test" (-> result :admin :email)))
      (is (= :admin (-> result :admin :role)))
      (is (some? (-> result :admin :id)))
      (is (= "user@acme.test" (-> result :user :email)))
      ;; Verify persistence: admin row exists and is queryable
      (is (= 2 (-> (jdbc/execute-one! *ds* ["SELECT COUNT(*) AS c FROM users"])
                   :c))))))
```

**Engineer note:** Read `libs/user/src/boundary/user/shell/service.clj:116` (`register-user`) to learn the exact input/output shape, and `libs/tenant/src/boundary/tenant/shell/service.clj:62` (`create-new-tenant`). `seed-baseline!` must call those exact production functions so any schema drift breaks this test.

- [ ] **Step 2: Run test, verify it fails**

- [ ] **Step 3: Implement**

Add to `src/boundary/test_support/shell/reset.clj`:

```clojure
(require '[boundary.test-support.core :as core]
         '[boundary.user.ports :as user-ports]
         '[boundary.tenant.ports :as tenant-ports])

(defn seed-baseline!
  "Creates baseline entities via production services. Returns the created
   entities with their generated IDs for tests to reference."
  [{:keys [user-service tenant-service]}]
  (let [spec     (core/baseline-seed-spec)
        tenant   (tenant-ports/create-new-tenant tenant-service
                                                 (select-keys (:tenant spec) [:slug :name]))
        admin    (user-ports/register-user user-service (:admin spec))
        user     (user-ports/register-user user-service (:user spec))]
    {:tenant tenant
     :admin  (assoc admin :password (-> spec :admin :password))
     :user   (assoc user  :password (-> spec :user  :password))}))
```

**Engineer note:** the `:password` is re-attached to the returned map so the HTTP handler can pass it out over the wire for tests to log in with. The `:password-hash` must NOT be returned — double-check `register-user`'s output does not include it (it is a security requirement). If it does, `dissoc` it explicitly.

- [ ] **Step 4: Run test**

Expected: PASS. Verify manually by inspecting the assertion on `users` row count.

- [ ] **Step 5: Commit**

```bash
git add src/boundary/test_support/shell/reset.clj test/boundary/test_support/shell/reset_test.clj
git commit -m "Add baseline seed via production user + tenant services"
```

---

### Task 5: HTTP handler for POST /test/reset

**Files:**
- Create: `src/boundary/test_support/shell/handler.clj`
- Test: `test/boundary/test_support/shell/handler_test.clj`

- [ ] **Step 1: Write failing test**

```clojure
(ns boundary.test-support.shell.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.test-support.shell.handler :as h]
            [ring.mock.request :as mock]))

(deftest ^:unit reset-handler-returns-seeded-entities
  (testing "handler truncates and seeds, returns JSON with fixture IDs"
    (let [truncate-calls (atom 0)
          seed-calls     (atom 0)
          fake-deps {:truncate! (fn [_] (swap! truncate-calls inc))
                     :seed!     (fn [_] (swap! seed-calls inc)
                                        {:tenant {:id "T-1" :slug "acme"}
                                         :admin  {:id "A-1" :email "admin@acme.test"
                                                  :password "Test-Pass-1234!"}
                                         :user   {:id "U-1" :email "user@acme.test"
                                                  :password "Test-Pass-1234!"}})}
          handler (h/make-reset-handler fake-deps)
          req (-> (mock/request :post "/test/reset")
                  (assoc :body-params {:seed "baseline"}))
          resp (handler req)]
      (is (= 200 (:status resp)))
      (is (= 1 @truncate-calls))
      (is (= 1 @seed-calls))
      (is (= "acme" (-> resp :body :seeded :tenant :slug)))
      (is (= "admin@acme.test" (-> resp :body :seeded :admin :email)))
      (is (nil? (-> resp :body :seeded :admin :password-hash))
          "password-hash must never leak"))))

(deftest ^:unit reset-handler-empty-seed-skips-seeding
  (testing "with seed=empty, handler truncates but does not seed"
    (let [seed-calls (atom 0)
          fake-deps {:truncate! (fn [_] nil)
                     :seed!     (fn [_] (swap! seed-calls inc) {})}
          handler (h/make-reset-handler fake-deps)
          req (assoc (mock/request :post "/test/reset") :body-params {:seed "empty"})
          resp (handler req)]
      (is (= 200 (:status resp)))
      (is (= 0 @seed-calls)))))
```

- [ ] **Step 2: Run, verify fail**

- [ ] **Step 3: Implement**

Create `src/boundary/test_support/shell/handler.clj`:

```clojure
(ns boundary.test-support.shell.handler
  "HTTP wrapper for POST /test/reset. Guarded at mount-time by profile flag."
  (:require [clojure.tools.logging :as log]))

(defn make-reset-handler
  "Returns a ring handler that truncates, optionally seeds, and returns JSON.

   deps: {:truncate! (fn [_]) :seed! (fn [_])}
   Both functions receive the full deps map so they can pull services as needed."
  [deps]
  (fn [request]
    (try
      (let [seed-kind (or (get-in request [:body-params :seed])
                          (get-in request [:params :seed])
                          "baseline")
            _        ((:truncate! deps) deps)
            seeded   (if (= "empty" seed-kind)
                       {}
                       ((:seed! deps) deps))]
        (log/info "test-reset invoked" {:seed seed-kind})
        {:status 200
         :body   {:ok true :seeded seeded}})
      (catch Throwable t
        (log/error t "test-reset failed")
        {:status 500
         :body   {:ok false :error (.getMessage t)}}))))
```

- [ ] **Step 4: Run test, verify pass**

- [ ] **Step 5: Commit**

```bash
git add src/boundary/test_support/shell/handler.clj test/boundary/test_support/shell/handler_test.clj
git commit -m "Add /test/reset HTTP handler with password-hash leak guard"
```

---

### Task 6: Conditional wiring into Integrant system

**Files:**
- Modify: `resources/conf/test/config.edn`
- Modify: `src/boundary/config.clj`

- [ ] **Step 1: Enable flag in test config**

Add to `resources/conf/test/config.edn`:

```edn
:test/reset-endpoint-enabled? true
```

Place it at the top-level of the config map (same level as `:boundary/h2`, `:boundary/logging`, etc.).

- [ ] **Step 2: Read current router assembly**

Read `src/boundary/config.clj` and find `user-module-config` (the function that merges route groups). Understand the data shape the router expects — it's typically `{:api [...] :web [...] :static [...]}` per the exploration notes.

- [ ] **Step 3: Mount route conditionally**

In the appropriate place in `src/boundary/config.clj` (where routes are assembled from service components), add:

```clojure
(defn- test-support-routes
  "Returns a vector of routes for the /test/reset endpoint when the
   :test/reset-endpoint-enabled? flag is true. Returns nil otherwise.

   Fails loudly on startup if the flag is true in a non-test profile."
  [{:keys [profile] :as config} {:keys [user-service tenant-service datasource]}]
  (when (get config :test/reset-endpoint-enabled?)
    (when-not (#{:test :dev} profile)
      (throw (ex-info "test-support /test/reset endpoint cannot be enabled outside :test or :dev profiles"
                      {:profile profile})))
    (let [handler (handler/make-reset-handler
                   {:truncate! (fn [_] (reset/truncate-all! datasource))
                    :seed!     (fn [_] (reset/seed-baseline!
                                        {:user-service user-service
                                         :tenant-service tenant-service}))})]
      [{:path    "/test/reset"
        :meta    {:no-doc true}
        :methods {:post {:handler handler
                         :summary "(test only) Reset H2 + seed baseline"}}}])))
```

Then wire the returned routes into the main route bag wherever user/tenant/admin routes are concatenated. The exact call site depends on the existing structure of `config.clj` — read it and mirror.

**Engineer note:** `profile` may not currently be part of the config map passed to `user-module-config`. If not, thread it in — the value is already known at startup (from `BND_ENV`).

Requires new require lines at top of `config.clj`:

```clojure
[boundary.test-support.shell.handler :as test-reset-handler]
[boundary.test-support.shell.reset :as test-reset]
```

- [ ] **Step 4: Write failing integration test**

Create `test/boundary/test_support/wiring_test.clj`:

```clojure
(ns boundary.test-support.wiring-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.config :as config]))

(deftest ^:integration reset-route-mounted-in-test-profile
  (testing "test profile mounts /test/reset route"
    (let [cfg (config/load-config :test)]  ;; use whatever loader exists
      (is (true? (:test/reset-endpoint-enabled? cfg))))))

(deftest ^:integration reset-disabled-in-prod
  (testing "prod profile does not mount /test/reset (flag absent or false)"
    (let [cfg (config/load-config :prod)]
      (is (not (true? (:test/reset-endpoint-enabled? cfg)))))))
```

**Engineer note:** `config/load-config`'s exact signature/name may differ; adapt after reading `src/boundary/config.clj`.

- [ ] **Step 5: Run tests**

```bash
clojure -M:test:db/h2 --focus boundary.test-support.wiring-test
clojure -M:test:db/h2 :unit :integration
```

Expected: PASS. Full unit+integration suite still green.

- [ ] **Step 6: Commit**

```bash
git add src/boundary/config.clj resources/conf/test/config.edn test/boundary/test_support/wiring_test.clj
git commit -m "Wire /test/reset route behind :test profile flag"
```

---

### Task 7: `bb doctor` guard against flag in non-test configs

**Files:**
- Modify: `libs/tools/src/boundary/tools/doctor.clj`
- Test: `libs/tools/test/boundary/tools/doctor_test.clj`

- [ ] **Step 1: Write failing test**

Extend `libs/tools/test/boundary/tools/doctor_test.clj`:

```clojure
(deftest reset-endpoint-flag-must-not-be-enabled-in-prod
  (testing "doctor warns when :test/reset-endpoint-enabled? true in prod config"
    (let [result (doctor/check-config {:profile :prod
                                       :config {:test/reset-endpoint-enabled? true}})]
      (is (some #(re-find #"reset-endpoint-enabled" %) (:errors result))))))
```

(Adapt field names to whatever signature `doctor/check-config` currently has.)

- [ ] **Step 2: Run**

```bash
bb test:tools
```

Expected: FAIL on the new test.

- [ ] **Step 3: Implement**

Add a check to `doctor.clj`'s config validation pass that, for any non-`:test` / non-`:dev` profile, rejects `:test/reset-endpoint-enabled? true` with an error message like "`:test/reset-endpoint-enabled?` must not be true in <profile> profile — it exposes a DB-truncating endpoint".

- [ ] **Step 4: Run tests**

```bash
bb test:tools
bb doctor --env all --ci
```

Both pass.

- [ ] **Step 5: Commit**

```bash
git add libs/tools/src/boundary/tools/doctor.clj libs/tools/test/boundary/tools/doctor_test.clj
git commit -m "Add doctor check: reset endpoint flag forbidden outside test/dev"
```

---

## Phase 2 — Babashka task and dev server entry

### Task 8: `bb run-e2e-server` task

**Files:**
- Modify: `bb.edn`

- [ ] **Step 1: Add task**

Edit `bb.edn`, add under `:tasks`:

```clojure
run-e2e-server {:doc "Start the app on :3100 in :test profile for Playwright e2e tests"
                :task (let [pb (-> (ProcessBuilder. ["clojure" "-M:run"
                                                     "--port" "3100"])
                                   (.inheritIO))]
                        (.environment pb) (doto (.environment pb)
                                            (.put "BND_ENV" "test"))
                        (.waitFor (.start pb)))}
```

**Engineer note:** Verify the actual run alias (`:run`, `:app`, etc.) by reading `deps.edn` for the Integrant entrypoint. Adjust `-M:run` accordingly. Also verify how the app accepts the HTTP port — via CLI arg, env var, or config override. Adjust if needed (e.g., `PORT=3100` env var instead of `--port`).

- [ ] **Step 2: Manual smoke test**

```bash
bb run-e2e-server &
sleep 10
curl -sf http://localhost:3100/web/login >/dev/null && echo OK || echo FAIL
curl -X POST -H "Content-Type: application/json" -d '{"seed":"baseline"}' http://localhost:3100/test/reset
kill %1
```

Expected: login page returns 200, reset endpoint returns `{"ok":true, ...}`.

- [ ] **Step 3: Commit**

```bash
git add bb.edn
git commit -m "Add bb run-e2e-server task for Playwright to orchestrate"
```

---

### Task 9: `bb e2e` task (Clojure/spel orchestration)

**Files:**
- Modify: `bb.edn`

The `bb e2e` task orchestrates the full e2e run: start the app in `:test` profile on port 3100 in the background, wait for it to respond on `/web/login`, run the kaocha `:e2e` suite, tear down the server. No separate `webServer` manager like Playwright's — it's all orchestrated here.

- [ ] **Step 1: Add task**

Add under `:tasks` in `bb.edn`:

```clojure
e2e {:doc "Run end-to-end Clojure/spel tests against a :test-profile server on port 3100"
     :task
     (let [port     "3100"
           base-url (str "http://localhost:" port)
           env      (into {} (System/getenv))
           proc     (babashka.process/process
                     {:env        (assoc env "BND_ENV" "test")
                      :out        :inherit
                      :err        :inherit
                      :shutdown   babashka.process/destroy-tree}
                     ["clojure" "-M:run" "--port" port])]
       (try
         ;; Wait up to 60s for /web/login to return 200
         (loop [n 0]
           (let [resp (try (slurp (str base-url "/web/login"))
                           (catch Exception _ nil))]
             (cond
               resp                     (println "e2e: server up on" base-url)
               (> n 60)                 (throw (ex-info "e2e: server never came up" {:base base-url}))
               :else                    (do (Thread/sleep 1000) (recur (inc n))))))
         ;; Run the :e2e kaocha suite with the :e2e alias active.
         (let [exit (:exit (babashka.process/shell {:continue true}
                                                   "clojure" "-M:test:e2e" ":e2e"))]
           (when-not (zero? exit)
             (System/exit exit)))
         (finally
           (babashka.process/destroy-tree proc))))}
```

**Engineer note:** verify the actual run alias before running. If `:run` doesn't exist, check `deps.edn` for the Integrant entrypoint alias (it may be `:app`, `:main`, or invoked via `-m boundary.main`). Adjust the command vector accordingly. Also verify how port selection works — if the app reads config instead of `--port`, export a `PORT=3100` env var or set it in `resources/conf/test/config.edn`.

- [ ] **Step 2: Verify help output**

```bash
bb tasks | grep e2e
```

Expected: `e2e` listed with its doc.

- [ ] **Step 3: Smoke test (without any e2e tests yet)**

Since no e2e tests exist yet at this stage, `bb e2e` will invoke kaocha with the `:e2e` suite and report 0 tests run. That's expected — it's only validating that the orchestration works. Run it:

```bash
bb e2e
```

Expected outcome: server starts on 3100, kaocha reports "0 tests", server is torn down, exit 0. If `clojure -M:run` does not exist or the server fails to start, fix it before continuing.

- [ ] **Step 4: Commit**

```bash
git add bb.edn
git commit -m "Add bb e2e task — orchestrate kaocha :e2e suite against test-profile server"
```

---

## Phase 3 — Clojure/spel test scaffolding

All e2e test code lives under `libs/e2e/test/boundary/e2e/`. The `libs/e2e/` sub-library and its `deps.edn` (with the `com.blockether/spel` dependency), the root `:e2e` alias, and the `:e2e` kaocha suite entry in `tests.edn` are set up in a single foundation task (Task 10). After that, Tasks 11-16 fill in helpers and the shared fixture.

### Task 10: `libs/e2e` scaffold + spel dependency

**Files:**
- Create: `libs/e2e/deps.edn`
- Create: `libs/e2e/src/boundary/e2e/README.md` (placeholder)
- Modify: `deps.edn` (add `:e2e` alias)
- Modify: `tests.edn` (add `:e2e` kaocha suite + register `:e2e` in focus-meta)

**Rationale:** isolating spel under its own sub-library via an opt-in `:e2e` alias keeps the hundreds of MB of Playwright Java browser JARs off the classpath for normal `clojure -M:test` runs. Only `clojure -M:test:e2e :e2e` (or `bb e2e`) pulls them in.

- [ ] **Step 1: Create `libs/e2e/deps.edn`**

```clojure
{:paths ["src"]

 :deps  {org.clojure/clojure        {:mvn/version "1.12.4"}

         ;; Clojure wrapper around Playwright Java.
         com.blockether/spel        {:mvn/version "0.7.11"}

         ;; Monorepo libraries the e2e suite references.
         boundary/user              {:local/root "../user"}
         boundary/tenant            {:local/root "../tenant"}}

 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps  {lambdaisland/kaocha {:mvn/version "1.91.1392"}
                       com.h2database/h2   {:mvn/version "2.4.240"}
                       clj-http/clj-http   {:mvn/version "3.13.1"}
                       one-time/one-time   {:mvn/version "0.8.0"
                                            :exclusions  [com.github.kenglxn.qrgen/javase]}}
         :main-opts   ["-m" "kaocha.runner"]}}}
```

- [ ] **Step 2: Create placeholder README** at `libs/e2e/src/boundary/e2e/README.md`:

```markdown
# libs/e2e — End-to-end test suite

No production Clojure source lives here. All code is under `test/boundary/e2e/`.
```

(The empty `src/` directory exists so `bb check:deps` and other lib-structure tooling treats `libs/e2e` consistently with the other 22 libraries.)

- [ ] **Step 3: Add `:e2e` alias to root `deps.edn`**

Under `:aliases`, append (after any existing alias block):

```clojure
:e2e {:extra-paths ["libs/e2e/src" "libs/e2e/test"]
      :extra-deps  {com.blockether/spel {:mvn/version "0.7.11"}}}
```

Activating this alias composes with `:test` so `clojure -M:test:e2e :e2e` runs the e2e suite with spel on the classpath.

- [ ] **Step 4: Add `:e2e` suite to `tests.edn`**

Append to the `:tests` vector (after the `:i18n` entry, before the closing `]`):

```clojure
{:id :e2e
 :test-paths ["libs/e2e/test"]
 :ns-patterns ["boundary.e2e.*-test"]}
```

Also add `:e2e` to the `:kaocha.plugin.filter/focus-meta` vector so `^:e2e` metadata filtering works.

- [ ] **Step 5: Dep resolution smoke test**

```bash
clojure -P -M:test         # unchanged behaviour — should NOT download spel/playwright
clojure -P -M:test:e2e     # should download com.blockether/spel + playwright driver bundle
```

Expected: first command is fast and does not mention Playwright; second downloads Playwright Java + spel on first run.

- [ ] **Step 6: Kaocha config smoke test**

```bash
clojure -M:test --print-config | grep -A2 ':id :e2e'
```

Expected: shows the `:e2e` suite in the kaocha config output.

- [ ] **Step 7: Commit**

```bash
git add libs/e2e deps.edn tests.edn
git commit -m "Scaffold libs/e2e with spel Clojars dependency and :e2e kaocha suite"
```

---

### Task 11: `reset.clj` helper

**Files:**
- Create: `libs/e2e/test/boundary/e2e/helpers/reset.clj`
- Create: `libs/e2e/test/boundary/e2e/helpers/reset_test.clj`

Wraps `POST /test/reset` for the e2e suite. Used by the `fixtures.clj` auto-fixture in Task 15 to reset state before every e2e test. Pure HTTP via `clj-http` — no spel involvement at this layer.

- [ ] **Step 1: Write failing unit test**

```clojure
(ns boundary.e2e.helpers.reset-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.e2e.helpers.reset :as reset]))

(deftest ^:unit base-url-defaults-to-localhost-3100
  (testing "default base URL matches the bb e2e server"
    (is (= "http://localhost:3100" (reset/default-base-url)))))

(deftest ^:unit parses-seed-response-shape
  (testing "parse-seed-response returns a SeedResult with :tenant :admin :user"
    (let [body {:ok     true
                :seeded {:tenant {:id "T-1" :slug "acme"}
                         :admin  {:id "A-1" :email "admin@acme.test"
                                  :password "Test-Pass-1234!"}
                         :user   {:id "U-1" :email "user@acme.test"
                                  :password "Test-Pass-1234!"}}}
          result (reset/parse-seed-response body)]
      (is (= "acme" (-> result :tenant :slug)))
      (is (= "admin@acme.test" (-> result :admin :email)))
      (is (= "user@acme.test" (-> result :user :email))))))
```

Run `clojure -M:test:e2e --focus boundary.e2e.helpers.reset-test` and verify it fails (namespace does not exist).

- [ ] **Step 2: Implement** `libs/e2e/test/boundary/e2e/helpers/reset.clj`:

```clojure
(ns boundary.e2e.helpers.reset
  "Client-side helper for POST /test/reset. Called from e2e fixtures
   before every test to force a clean DB + baseline seed."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(defn default-base-url []
  "http://localhost:3100")

(defn parse-seed-response [body]
  (:seeded body))

(defn reset-db!
  "POSTs to /test/reset on the running e2e server and returns the parsed
   SeedResult (tenant/admin/user with IDs + plain-text passwords).

   Options:
     :base-url  (default http://localhost:3100)
     :seed      :baseline | :empty  (default :baseline)

   Throws ex-info on non-200 or on :ok false in the body."
  ([] (reset-db! {}))
  ([{:keys [base-url seed] :or {base-url (default-base-url) seed :baseline}}]
   (let [resp (http/post (str base-url "/test/reset")
                         {:content-type :json
                          :accept       :json
                          :body         (json/generate-string {:seed (name seed)})
                          :throw-exceptions false
                          :as           :json})]
     (when-not (= 200 (:status resp))
       (throw (ex-info "POST /test/reset failed"
                       {:status (:status resp) :body (:body resp)})))
     (when-not (:ok (:body resp))
       (throw (ex-info "test/reset returned ok=false"
                       {:body (:body resp)})))
     (parse-seed-response (:body resp)))))
```

- [ ] **Step 3: Run unit tests, verify green.**

- [ ] **Step 4: Commit**

```bash
git add libs/e2e/test/boundary/e2e/helpers/reset.clj \
        libs/e2e/test/boundary/e2e/helpers/reset_test.clj
git commit -m "Add reset helper for POST /test/reset in e2e suite"
```

---

### Task 12: `cookies.clj` helper

**Files:**
- Create: `libs/e2e/test/boundary/e2e/helpers/cookies.clj`
- Create: `libs/e2e/test/boundary/e2e/helpers/cookies_test.clj`

Parses `Set-Cookie` headers from `clj-http` / spel API responses and asserts HttpOnly on session-token.

- [ ] **Step 1: Write failing unit test**

```clojure
(ns boundary.e2e.helpers.cookies-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.e2e.helpers.cookies :as cookies]))

(deftest ^:unit parse-session-token-from-set-cookie
  (testing "returns the token value when Set-Cookie has session-token with HttpOnly"
    (let [headers {"set-cookie" ["session-token=abc123; Path=/; HttpOnly"
                                 "other-cookie=foo; Path=/"]}]
      (is (= "abc123" (cookies/session-token headers))))))

(deftest ^:unit reject-session-token-without-httponly
  (testing "throws when session-token is set without HttpOnly flag"
    (let [headers {"set-cookie" ["session-token=abc123; Path=/"]}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (cookies/session-token headers))))))

(deftest ^:unit remembered-email-parsing
  (testing "returns remembered-email cookie value when present"
    (let [headers {"set-cookie" ["remembered-email=user%40acme.test; Path=/; Max-Age=2592000"]}]
      (is (= "user@acme.test" (cookies/remembered-email headers))))))
```

- [ ] **Step 2: Implement** `libs/e2e/test/boundary/e2e/helpers/cookies.clj`:

```clojure
(ns boundary.e2e.helpers.cookies
  "Set-Cookie parsing for e2e assertions. session-token must always be HttpOnly."
  (:require [clojure.string :as str]))

(defn- set-cookie-strings [headers]
  (let [raw (or (get headers "set-cookie")
                (get headers "Set-Cookie"))]
    (cond
      (nil? raw) []
      (string? raw) [raw]
      :else raw)))

(defn- find-cookie [headers cookie-name]
  (some (fn [line]
          (when (str/starts-with? (str/lower-case line) (str (str/lower-case cookie-name) "="))
            line))
        (set-cookie-strings headers)))

(defn session-token
  "Parses the session-token value from response headers, asserting HttpOnly.
   Throws ex-info if absent or if HttpOnly is missing."
  [headers]
  (let [line (find-cookie headers "session-token")]
    (when-not line
      (throw (ex-info "session-token cookie not found in Set-Cookie"
                      {:headers headers})))
    (when-not (str/includes? (str/lower-case line) "httponly")
      (throw (ex-info "session-token missing HttpOnly flag"
                      {:cookie line})))
    (-> line (str/split #";") first (str/split #"=" 2) second)))

(defn remembered-email
  "Returns the URL-decoded value of the remembered-email cookie, or nil."
  [headers]
  (when-let [line (find-cookie headers "remembered-email")]
    (let [raw (-> line (str/split #";") first (str/split #"=" 2) second)]
      (java.net.URLDecoder/decode raw "UTF-8"))))

(defn no-session-token?
  "True if Set-Cookie does NOT set session-token."
  [headers]
  (nil? (find-cookie headers "session-token")))
```

- [ ] **Step 3: Run, verify tests pass.**

- [ ] **Step 4: Commit**

```bash
git add libs/e2e/test/boundary/e2e/helpers/cookies.clj \
        libs/e2e/test/boundary/e2e/helpers/cookies_test.clj
git commit -m "Add Set-Cookie parsing + HttpOnly assertion helpers for e2e"
```

---

### Task 13: `totp.clj` helper

**Files:**
- Create: `libs/e2e/test/boundary/e2e/helpers/totp.clj`
- Create: `libs/e2e/test/boundary/e2e/helpers/totp_test.clj`

Thin wrapper around `one-time.core/get-totp-token` (already in the monorepo, used by the user MFA code). Generates TOTP codes from a base32 secret.

- [ ] **Step 1: Verify one-time is on classpath**

```bash
clojure -M:test:e2e -e "(require 'one-time.core) (println (one-time.core/generate-secret-key))"
```

Expected: prints a base32 secret string.

- [ ] **Step 2: Write failing test**

```clojure
(ns boundary.e2e.helpers.totp-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.e2e.helpers.totp :as totp]))

(deftest ^:unit current-code-is-six-digits
  (let [secret "JBSWY3DPEHPK3PXP"]
    (let [code (totp/current-code secret)]
      (is (string? code))
      (is (re-matches #"\d{6}" code)))))
```

- [ ] **Step 3: Implement** `libs/e2e/test/boundary/e2e/helpers/totp.clj`:

```clojure
(ns boundary.e2e.helpers.totp
  "Generate TOTP codes for e2e MFA tests. Uses one-time, which is already
   used by the boundary.user MFA implementation."
  (:require [one-time.core :as ot]))

(defn current-code
  "Returns the current 6-digit TOTP code for a base32 secret."
  [secret]
  (format "%06d" (ot/get-totp-token secret)))

(defn fresh-code
  "Waits until we're at least `safety-ms` away from a TOTP window rollover,
   then returns a fresh code. Reduces flakiness near window boundaries."
  ([secret] (fresh-code secret 2000))
  ([secret safety-ms]
   (let [ms-into-window (mod (System/currentTimeMillis) 30000)
         ms-left        (- 30000 ms-into-window)]
     (when (< ms-left safety-ms)
       (Thread/sleep (long (+ ms-left 100))))
     (current-code secret))))
```

**Engineer note:** verify the exact `one-time.core` function name — it may be `get-totp-token`, `generate-token`, or `totp`. Run `clojure -M:test:e2e -e "(require 'one-time.core) (->> (ns-publics 'one-time.core) keys sort println)"` to list public vars.

- [ ] **Step 4: Run tests, commit**

```bash
git add libs/e2e/test/boundary/e2e/helpers/totp.clj \
        libs/e2e/test/boundary/e2e/helpers/totp_test.clj
git commit -m "Add TOTP helper using one-time for e2e MFA tests"
```

---

### Task 14: `users.clj` API helpers

**Files:**
- Create: `libs/e2e/test/boundary/e2e/helpers/users.clj`

Implements login/register/MFA management against `/api/v1/auth/*`. Uses `clj-http` for API calls because it gives direct access to response headers (needed for cookie assertions) and doesn't need a spel page context. No unit tests for this helper file — it's exercised end-to-end by the API spec namespaces in Phase 4.

- [ ] **Step 1: Implement**

```clojure
(ns boundary.e2e.helpers.users
  "API-level helpers for e2e tests: login, register, MFA enable/disable.
   Uses clj-http directly (not spel) because the e2e suite needs to inspect
   Set-Cookie headers and make fine-grained assertions on status codes."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [boundary.e2e.helpers.totp :as totp]
            [boundary.e2e.helpers.reset :as reset]))

(defn- api-post [path body & [{:keys [cookie] :as _opts}]]
  (http/post (str (reset/default-base-url) path)
             (cond-> {:content-type     :json
                      :accept           :json
                      :body             (json/generate-string body)
                      :throw-exceptions false
                      :as               :json}
               cookie (assoc-in [:headers "Cookie"] (str "session-token=" cookie)))))

(defn- api-get [path & [{:keys [cookie]}]]
  (http/get (str (reset/default-base-url) path)
            (cond-> {:accept           :json
                     :throw-exceptions false
                     :as               :json}
              cookie (assoc-in [:headers "Cookie"] (str "session-token=" cookie)))))

(defn login
  "POST /api/v1/auth/login — returns the full ring response including headers."
  [{:keys [email password]}]
  (api-post "/api/v1/auth/login" {:email email :password password}))

(defn register
  "POST /api/v1/auth/register — returns the full ring response."
  [{:keys [email password name]}]
  (api-post "/api/v1/auth/register" {:email email :password password :name name}))

(defn enable-mfa!
  "Runs the two-step MFA enable flow (setup → enable with TOTP) using the
   provided session-token. Returns the setup result `{:secret :backupCodes ...}`
   so tests can generate fresh codes or verify backup codes."
  [session-token]
  (let [setup-resp (api-post "/api/v1/auth/mfa/setup" {} {:cookie session-token})
        _ (when-not (= 200 (:status setup-resp))
            (throw (ex-info "mfa/setup failed" {:resp setup-resp})))
        setup (:body setup-resp)
        code (totp/current-code (:secret setup))
        enable-resp (api-post "/api/v1/auth/mfa/enable"
                              {:secret           (:secret setup)
                               :backupCodes      (:backupCodes setup)
                               :verificationCode code}
                              {:cookie session-token})]
    (when-not (= 200 (:status enable-resp))
      (throw (ex-info "mfa/enable failed" {:resp enable-resp})))
    setup))

(defn disable-mfa!
  "POST /api/v1/auth/mfa/disable — no body."
  [session-token]
  (api-post "/api/v1/auth/mfa/disable" {} {:cookie session-token}))

(defn mfa-status
  "GET /api/v1/auth/mfa/status — returns response with body."
  [session-token]
  (api-get "/api/v1/auth/mfa/status" {:cookie session-token}))
```

**Engineer note:** the real MFA setup endpoint may accept an empty body `{}` or may require nothing at all. Verify against `libs/user/src/boundary/user/shell/http.clj:443` and adjust the `api-post` call shape. Also confirm that `mfa-setup-handler` returns the exact field names `:secret`, `:backupCodes`, `:qrCodeUrl` — adjust the helper's destructuring if different.

- [ ] **Step 2: Commit**

```bash
git add libs/e2e/test/boundary/e2e/helpers/users.clj
git commit -m "Add e2e API user helpers (login, register, MFA enable/disable/status)"
```

---

### Task 15: `fixtures.clj` with `:each` DB reset

**Files:**
- Create: `libs/e2e/test/boundary/e2e/fixtures.clj`
- Create: `libs/e2e/test/boundary/e2e/smoke_test.clj` (minimal canary)

Kaocha `use-fixtures :each` that runs `reset/reset-db!` before every e2e test and stashes the result in a dynamic var `*seed*`. Individual test namespaces `use-fixtures :each with-fresh-seed` and read `*seed*` to get the tenant/admin/user entities.

- [ ] **Step 1: Implement**

```clojure
(ns boundary.e2e.fixtures
  "Shared test fixtures for e2e tests. Every e2e test namespace should:

     (use-fixtures :each fixtures/with-fresh-seed)

   and then read `*seed*` to access the baseline tenant/admin/user."
  (:require [boundary.e2e.helpers.reset :as reset]))

(def ^:dynamic *seed* nil)

(defn with-fresh-seed
  "Runs POST /test/reset before every test. Binds *seed* to the seed result
   for the duration of the test."
  [f]
  (let [seed (reset/reset-db!)]
    (binding [*seed* seed]
      (f))))
```

- [ ] **Step 2: Write smoke test**

```clojure
(ns boundary.e2e.smoke-test
  "Minimal canary: is the e2e server reachable and does /test/reset work?"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.e2e.fixtures :as fx]
            [clj-http.client :as http]
            [boundary.e2e.helpers.reset :as reset]))

(use-fixtures :each fx/with-fresh-seed)

(deftest ^:e2e server-reachable-and-seeded
  (testing "the e2e server is up and the seed fixture returned an admin"
    (is (= "admin@acme.test" (-> fx/*seed* :admin :email)))
    (let [resp (http/get (str (reset/default-base-url) "/web/login")
                         {:throw-exceptions false})]
      (is (= 200 (:status resp))))))
```

- [ ] **Step 3: Run via bb e2e**

```bash
bb e2e
```

Expected: server boots, kaocha loads `:e2e` suite, smoke test passes, server is torn down. This is the first end-to-end validation that the whole pipeline works.

- [ ] **Step 4: Commit**

```bash
git add libs/e2e/test/boundary/e2e/fixtures.clj \
        libs/e2e/test/boundary/e2e/smoke_test.clj
git commit -m "Add e2e :each fixture + smoke test"
```

---

### Task 16: (removed — merged into Task 15)

---

## Phase 4 — API test specs

All API tests use `use-fixtures :each fixtures/with-fresh-seed`, giving them a fresh baseline via `/test/reset` before every test. They hit `/api/v1/auth/*` and `/api/v1/sessions/*` directly via `clj-http` (wrapped in `helpers.users`). No browser involved at this layer.

### Task 17: `api/auth_login_test.clj`

**Files:**
- Create: `libs/e2e/test/boundary/e2e/api/auth_login_test.clj`

**Pre-step:** observe the real login response shape once before writing assertions:

```bash
bb run-e2e-server &
sleep 10
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"seed":"baseline"}' http://localhost:3100/test/reset
curl -s -i -X POST -H "Content-Type: application/json" \
  -d '{"email":"admin@acme.test","password":"Test-Pass-1234!"}' \
  http://localhost:3100/api/v1/auth/login
# kill the server after noting the response
```

- [ ] **Step 1: Write tests**

```clojure
(ns boundary.e2e.api.auth-login-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.e2e.fixtures :as fx]
            [boundary.e2e.helpers.users :as users]
            [boundary.e2e.helpers.cookies :as cookies]))

(use-fixtures :each fx/with-fresh-seed)

(deftest ^:e2e login-happy-sets-session-cookie
  (testing "valid credentials → 200 + HttpOnly session cookie + no password-hash leak"
    (let [{:keys [admin]} fx/*seed*
          resp (users/login {:email (:email admin) :password (:password admin)})]
      (is (= 200 (:status resp)))
      (is (string? (cookies/session-token (:headers resp))))
      (is (not (re-find #"(?i)password[-_]?hash" (pr-str (:body resp)))))
      (is (not (re-find #"(?i)password[-_]?hash" (or (slurp-safe resp) "")))))))

(defn- slurp-safe [resp]
  (try (str (:body resp)) (catch Exception _ nil)))

(deftest ^:e2e login-wrong-password-401
  (let [{:keys [admin]} fx/*seed*
        resp (users/login {:email (:email admin) :password "wrong"})]
    (is (= 401 (:status resp)))
    (is (cookies/no-session-token? (:headers resp)))))

(deftest ^:e2e login-unknown-email-401-no-enumeration
  (let [resp (users/login {:email "nobody@nowhere.test" :password "whatever"})]
    (is (= 401 (:status resp)))
    (let [body (str (:body resp))]
      (is (not (re-find #"(?i)not found|does not exist" body))))))
```

**Engineer note:** `slurp-safe` is defined after its use by design — move it above the first test before committing if you want to keep clj-kondo happy, or add a `declare` at the top. The placement is illustrative; clean it up.

- [ ] **Step 2: Run**

```bash
bb e2e
```

Only the newly added tests should fail/pass. Three tests pass → commit.

- [ ] **Step 3: Commit**

```bash
git add libs/e2e/test/boundary/e2e/api/auth_login_test.clj
git commit -m "Add API login e2e tests: happy, wrong password, unknown email"
```

---

### Task 18: `api/auth_register_test.clj`

**Files:**
- Create: `libs/e2e/test/boundary/e2e/api/auth_register_test.clj`

- [ ] **Step 1: Write tests**

```clojure
(ns boundary.e2e.api.auth-register-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [boundary.e2e.fixtures :as fx]
            [boundary.e2e.helpers.users :as users]))

(use-fixtures :each fx/with-fresh-seed)

(deftest ^:e2e register-happy-creates-user
  (let [resp (users/register {:email "new-user@acme.test"
                              :password "A-Strong-Pass-9999!"
                              :name "New User"})]
    (is (= 200 (:status resp)))
    (is (not (re-find #"(?i)password[-_]?hash" (pr-str (:body resp)))))))

(deftest ^:e2e register-duplicate-email-409
  (let [{:keys [admin]} fx/*seed*
        resp (users/register {:email (:email admin)
                              :password "Another-Strong-Pass-1!"
                              :name "Dup"})]
    (is (= 409 (:status resp)))))

(deftest ^:e2e register-weak-password-400
  (let [resp (users/register {:email "weakpass@acme.test"
                              :password "abc"
                              :name "Weak"})]
    (is (= 400 (:status resp)))
    (is (re-find #"(?i)password|policy|length" (pr-str (:body resp))))))
```

- [ ] **Step 2: Run, commit** if 3/3 green.

```bash
git add libs/e2e/test/boundary/e2e/api/auth_register_test.clj
git commit -m "Add API register e2e tests: happy, duplicate, weak password"
```

---

### Task 19: `api/auth_mfa_test.clj`

**Scope note:** `/api/v1/auth/login` does **not** accept `mfaCode` (schema is `:closed`). MFA-during-login is HTML-only and covered in Task 21. This spec only covers MFA management endpoints.

**Files:**
- Create: `libs/e2e/test/boundary/e2e/api/auth_mfa_test.clj`

- [ ] **Step 1: Write tests**

```clojure
(ns boundary.e2e.api.auth-mfa-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.e2e.fixtures :as fx]
            [boundary.e2e.helpers.users :as users]
            [boundary.e2e.helpers.cookies :as cookies]
            [clj-http.client :as http]
            [boundary.e2e.helpers.reset :as reset]))

(use-fixtures :each fx/with-fresh-seed)

(defn- login-and-token [user]
  (let [resp  (users/login {:email (:email user) :password (:password user)})
        token (cookies/session-token (:headers resp))]
    token))

(deftest ^:e2e mfa-setup-returns-secret-and-backup-codes
  (let [token (login-and-token (:user fx/*seed*))
        resp  (http/post (str (reset/default-base-url) "/api/v1/auth/mfa/setup")
                         {:headers {"Cookie" (str "session-token=" token)}
                          :throw-exceptions false
                          :as :json})]
    (is (= 200 (:status resp)))
    (is (string? (-> resp :body :secret)))
    (is (seq (-> resp :body :backupCodes)))))

(deftest ^:e2e mfa-enable-correct-code-flips-status
  (let [token (login-and-token (:user fx/*seed*))]
    (users/enable-mfa! token)
    (let [status (users/mfa-status token)]
      (is (= 200 (:status status)))
      (is (true? (-> status :body :enabled))))))

(deftest ^:e2e mfa-enable-wrong-code-rejected
  (let [token (login-and-token (:user fx/*seed*))
        setup (-> (http/post (str (reset/default-base-url) "/api/v1/auth/mfa/setup")
                             {:headers {"Cookie" (str "session-token=" token)}
                              :as :json :throw-exceptions false})
                  :body)
        resp (http/post (str (reset/default-base-url) "/api/v1/auth/mfa/enable")
                        {:headers {"Cookie" (str "session-token=" token)}
                         :content-type :json
                         :body (cheshire.core/generate-string
                                {:secret (:secret setup)
                                 :backupCodes (:backupCodes setup)
                                 :verificationCode "000000"})
                         :throw-exceptions false
                         :as :json})]
    (is (not= 200 (:status resp)))
    (let [status (users/mfa-status token)]
      (is (false? (-> status :body :enabled))))))

(deftest ^:e2e mfa-disable-turns-it-off
  (let [token (login-and-token (:user fx/*seed*))]
    (users/enable-mfa! token)
    (users/disable-mfa! token)
    (let [status (users/mfa-status token)]
      (is (false? (-> status :body :enabled))))))
```

Add `cheshire.core` to the require list if needed.

- [ ] **Step 2: Run, commit**

```bash
git add libs/e2e/test/boundary/e2e/api/auth_mfa_test.clj
git commit -m "Add API MFA management e2e tests: setup, enable, wrong code, disable"
```

---

### Task 20: `api/auth_sessions_test.clj`

**Scope note:** no `GET /api/v1/sessions` list endpoint. Tests use the session token from login and call `GET/DELETE /api/v1/sessions/:token` directly.

**Files:**
- Create: `libs/e2e/test/boundary/e2e/api/auth_sessions_test.clj`

**Pre-step:** verify the real lockout threshold before writing the lockout test:

```bash
grep -rn "max-attempts\|lockout\|failed-login" libs/user/src/boundary/user/core/
```

- [ ] **Step 1: Write tests**

```clojure
(ns boundary.e2e.api.auth-sessions-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.e2e.fixtures :as fx]
            [boundary.e2e.helpers.users :as users]
            [boundary.e2e.helpers.cookies :as cookies]
            [boundary.e2e.helpers.reset :as reset]
            [clj-http.client :as http]))

(use-fixtures :each fx/with-fresh-seed)

(defn- login-token [user]
  (cookies/session-token (:headers (users/login {:email (:email user) :password (:password user)}))))

(deftest ^:e2e validate-live-token
  (let [token (login-token (:user fx/*seed*))
        resp  (http/get (str (reset/default-base-url) "/api/v1/sessions/" token)
                        {:throw-exceptions false})]
    (is (= 200 (:status resp)))))

(deftest ^:e2e delete-session-makes-token-unusable
  (let [token (login-token (:user fx/*seed*))
        del   (http/delete (str (reset/default-base-url) "/api/v1/sessions/" token)
                           {:throw-exceptions false})
        _     (is (= 200 (:status del)))
        follow (http/get (str (reset/default-base-url) "/api/v1/sessions/" token)
                         {:throw-exceptions false})]
    (is (= 401 (:status follow)))))

(deftest ^:e2e protected-endpoint-without-token-is-401
  (let [resp (http/get (str (reset/default-base-url) "/api/v1/auth/mfa/status")
                       {:throw-exceptions false})]
    (is (= 401 (:status resp)))))

(deftest ^:e2e lockout-after-repeated-failures
  (let [{:keys [user]} fx/*seed*
        ;; ADJUST threshold based on pre-step lookup
        threshold 5]
    (dotimes [_ (+ threshold 2)]
      (users/login {:email (:email user) :password "wrong"}))
    (let [resp (users/login {:email (:email user) :password (:password user)})]
      (is (contains? #{401 423 429} (:status resp)))
      (is (re-find #"(?i)lock|attempts|try again|too many" (pr-str (:body resp)))))))

(deftest ^:e2e password-hash-never-appears-in-auth-responses
  (let [{:keys [admin]} fx/*seed*
        responses [(users/login {:email (:email admin) :password (:password admin)})
                   (users/register {:email "nb@acme.test" :password "Strong-Pass-12345!" :name "NB"})]]
    (doseq [r responses]
      (is (not (re-find #"(?i)password[-_]?hash" (pr-str (:body r))))))))
```

- [ ] **Step 2: Run, adjust threshold if needed, commit**

```bash
git add libs/e2e/test/boundary/e2e/api/auth_sessions_test.clj
git commit -m "Add API sessions + security e2e tests: validate, revoke, unauth, lockout, hash-leak"
```

---

## Phase 5 — HTML test specs

HTML tests use **spel** for browser automation. Pattern: `core/with-testing-page [pg] ...` opens a Chromium page, `page/navigate`, `page/fill`, `page/click` drive the form, and `page/title` / `page/locator` read state. Cookies are read from spel's page context.

### Task 21: `html/web_login_test.clj`

**Files:**
- Create: `libs/e2e/test/boundary/e2e/html/web_login_test.clj`

- [ ] **Step 1: Read spel's public API**

```bash
clojure -M:test:e2e -e "(require 'com.blockether.spel.core 'com.blockether.spel.page) \
                        (->> (ns-publics 'com.blockether.spel.page) keys sort println)"
```

Expected: list of functions like `navigate`, `fill`, `click`, `title`, `url`, `locator`, `wait-for-url`, etc. Note the actual names and argument shapes.

- [ ] **Step 2: Write tests**

```clojure
(ns boundary.e2e.html.web-login-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.e2e.fixtures :as fx]
            [boundary.e2e.helpers.users :as users]
            [boundary.e2e.helpers.reset :as reset]
            [boundary.e2e.helpers.totp :as totp]
            [boundary.e2e.helpers.cookies :as cookies]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]))

(use-fixtures :each fx/with-fresh-seed)

(defn- base [& path] (apply str (reset/default-base-url) path))

(deftest ^:e2e get-renders-login-form
  (spel/with-testing-page [pg]
    (page/navigate pg (base "/web/login"))
    (is (page/visible? pg "form.form-card[action='/web/login']"))
    (is (page/visible? pg "input[name='email']"))
    (is (page/visible? pg "input[name='password']"))
    (is (page/visible? pg "input[name='remember']"))))

(deftest ^:e2e happy-user-redirects-to-dashboard
  (let [{:keys [user]} fx/*seed*]
    (spel/with-testing-page [pg]
      (page/navigate pg (base "/web/login"))
      (page/fill pg "input[name='email']" (:email user))
      (page/fill pg "input[name='password']" (:password user))
      (page/click pg "button[type='submit']")
      (page/wait-for-url pg #".*/web/dashboard.*")
      (is (some? (page/cookie pg "session-token"))))))

(deftest ^:e2e happy-admin-redirects-to-admin-users
  (let [{:keys [admin]} fx/*seed*]
    (spel/with-testing-page [pg]
      (page/navigate pg (base "/web/login"))
      (page/fill pg "input[name='email']" (:email admin))
      (page/fill pg "input[name='password']" (:password admin))
      (page/click pg "button[type='submit']")
      (page/wait-for-url pg #".*/web/admin/users.*"))))

(deftest ^:e2e return-to-honoured
  (let [{:keys [user]} fx/*seed*]
    (spel/with-testing-page [pg]
      (page/navigate pg (base "/web/login?return-to=/web/dashboard/settings"))
      (page/fill pg "input[name='email']" (:email user))
      (page/fill pg "input[name='password']" (:password user))
      (page/click pg "button[type='submit']")
      (page/wait-for-url pg #".*/web/dashboard/settings.*"))))

(deftest ^:e2e remember-me-sets-remembered-email-cookie
  (let [{:keys [user]} fx/*seed*]
    (spel/with-testing-page [pg]
      (page/navigate pg (base "/web/login"))
      (page/fill pg "input[name='email']" (:email user))
      (page/fill pg "input[name='password']" (:password user))
      (page/check pg "input[name='remember']")
      (page/click pg "button[type='submit']")
      (page/wait-for-url pg #".*/web/dashboard.*")
      (is (= (:email user) (:value (page/cookie pg "remembered-email")))))))

(deftest ^:e2e remembered-email-prefills-form
  (let [{:keys [user]} fx/*seed*]
    (spel/with-testing-page [pg]
      ;; First login with remember
      (page/navigate pg (base "/web/login"))
      (page/fill pg "input[name='email']" (:email user))
      (page/fill pg "input[name='password']" (:password user))
      (page/check pg "input[name='remember']")
      (page/click pg "button[type='submit']")
      (page/wait-for-url pg #".*/web/dashboard.*")
      ;; Clear session cookie but keep remembered-email
      (page/delete-cookie pg "session-token")
      ;; Revisit login
      (page/navigate pg (base "/web/login"))
      (is (= (:email user) (page/input-value pg "input[name='email']"))))))

(deftest ^:e2e invalid-credentials-show-error-no-session
  (let [{:keys [user]} fx/*seed*]
    (spel/with-testing-page [pg]
      (page/navigate pg (base "/web/login"))
      (page/fill pg "input[name='email']" (:email user))
      (page/fill pg "input[name='password']" "wrong-password")
      (page/click pg "button[type='submit']")
      (is (page/visible? pg ".validation-errors"))
      (is (nil? (page/cookie pg "session-token"))))))

(deftest ^:e2e mfa-required-second-step-wrong-then-right
  (let [{:keys [user]} fx/*seed*
        ;; Arrange: enable MFA via API first
        login-resp (users/login {:email (:email user) :password (:password user)})
        token (cookies/session-token (:headers login-resp))
        setup (users/enable-mfa! token)]
    (spel/with-testing-page [pg]
      (page/navigate pg (base "/web/login"))
      (page/fill pg "input[name='email']" (:email user))
      (page/fill pg "input[name='password']" (:password user))
      (page/click pg "button[type='submit']")
      ;; Expect MFA second-step form
      (is (page/visible? pg "input[name='mfa-code']"))
      ;; Wrong code rejected
      (page/fill pg "input[name='mfa-code']" "000000")
      (page/click pg "button[type='submit']")
      (is (page/visible? pg ".validation-errors, .auth-error"))
      ;; Valid code succeeds
      (page/fill pg "input[name='mfa-code']" (totp/fresh-code (:secret setup)))
      (page/click pg "button[type='submit']")
      (page/wait-for-url pg #".*/web/dashboard.*"))))
```

**Engineer note:** the spel API calls above (`page/visible?`, `page/cookie`, `page/check`, `page/delete-cookie`, `page/input-value`, `page/wait-for-url`) are illustrative. Verify actual names against `(ns-publics 'com.blockether.spel.page)` and adjust. If spel returns a map/record for cookies rather than a value, destructure accordingly. The semantics are what matter; the names are easy to fix.

- [ ] **Step 3: Run tests**

```bash
bb e2e
```

First run likely flushes out API naming mismatches. Fix them until all 8 tests pass.

- [ ] **Step 4: Commit**

```bash
git add libs/e2e/test/boundary/e2e/html/web_login_test.clj
git commit -m "Add HTML login e2e tests via spel: happy, remember-me, return-to, MFA"
```

---

### Task 22: `html/web_register_test.clj`

**Files:**
- Create: `libs/e2e/test/boundary/e2e/html/web_register_test.clj`

- [ ] **Step 1: Write tests**

```clojure
(ns boundary.e2e.html.web-register-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [boundary.e2e.fixtures :as fx]
            [boundary.e2e.helpers.reset :as reset]
            [com.blockether.spel.core :as spel]
            [com.blockether.spel.page :as page]))

(use-fixtures :each fx/with-fresh-seed)

(defn- base [& path] (apply str (reset/default-base-url) path))

(deftest ^:e2e get-renders-register-form
  (spel/with-testing-page [pg]
    (page/navigate pg (base "/web/register"))
    (is (page/visible? pg "form.form-card[action='/web/register']"))
    (is (page/visible? pg "input[name='name']"))
    (is (page/visible? pg "input[name='email']"))
    (is (page/visible? pg "input[name='password']"))))

(deftest ^:e2e happy-creates-user-and-redirects
  (spel/with-testing-page [pg]
    (page/navigate pg (base "/web/register"))
    (page/fill pg "input[name='name']" "Fresh User")
    (page/fill pg "input[name='email']" "fresh@acme.test")
    (page/fill pg "input[name='password']" "A-Strong-Pass-9876!")
    (page/click pg "button[type='submit']")
    (page/wait-for-url pg #"^(?!.*/web/register).*$")
    (is (some? (page/cookie pg "session-token")))))

(deftest ^:e2e weak-password-shows-validation-errors
  (spel/with-testing-page [pg]
    (page/navigate pg (base "/web/register"))
    (page/fill pg "input[name='name']" "Weak")
    (page/fill pg "input[name='email']" "weak@acme.test")
    (page/fill pg "input[name='password']" "abc")
    (page/click pg "button[type='submit']")
    (is (re-find #"/web/register" (page/url pg)))
    (is (page/visible? pg ".validation-errors"))))
```

- [ ] **Step 2: Run, commit**

```bash
git add libs/e2e/test/boundary/e2e/html/web_register_test.clj
git commit -m "Add HTML register e2e tests via spel"
```

---

## Phase 6 — CI integration

### Task 23: Add e2e job to CI

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Add job**

Append to `.github/workflows/ci.yml` after the `docs-lint` job:

```yaml
  # =============================================================================
  # End-to-end Clojure/spel tests
  # =============================================================================
  e2e:
    name: E2E (spel)
    runs-on: ubuntu-latest
    needs: [lint, build-ui-assets]

    steps:
      - uses: actions/checkout@v6

      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Setup Clojure
        uses: DeLaGuardo/setup-clojure@13.5.2
        with:
          cli: 1.11.1.1347
          bb: latest

      - name: Cache Maven deps
        uses: actions/cache@v5
        with:
          path: |
            ~/.m2/repository
            ~/.gitlibs
          key: ${{ runner.os }}-clojure-${{ hashFiles('**/deps.edn') }}
          restore-keys: ${{ runner.os }}-clojure-

      - name: Cache Playwright browsers
        uses: actions/cache@v5
        with:
          path: ~/.cache/ms-playwright
          key: ${{ runner.os }}-playwright-spel-0.7.11

      - name: Warm deps and install Playwright browsers
        run: |
          clojure -P -M:test:e2e
          # spel uses Playwright Java which downloads browsers lazily on first use;
          # trigger once so the CI cache captures them.
          clojure -M:test:e2e -e "(require 'com.blockether.spel.core) \
            ((requiring-resolve 'com.blockether.spel.core/install-browsers!))" \
            || echo "browser install via direct API not available; playwright will download on first test run"

      - name: Run e2e suite
        run: bb e2e
        env:
          BND_ENV: test
          CI: "true"

      - name: Upload failure artifacts
        if: failure()
        uses: actions/upload-artifact@v7
        with:
          name: e2e-failures
          path: |
            target/spel/
            target/test-output/
          retention-days: 7
```

**Engineer note:** `install-browsers!` may not be the exact spel API — check the spel README / Clojars docs. If not available, the first real test run will download browsers; the cache step still catches them for subsequent runs.

- [ ] **Step 2: Validate YAML syntax**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))" && echo OK
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "Add e2e job to CI (spel + bb e2e)"
```

---

## Phase 7 — Final verification

### Task 24: Full local run

- [ ] **Step 1: Clean classpath + warm deps**

```bash
rm -rf .cpcache
clojure -P -M:test:e2e
```

- [ ] **Step 2: Run Clojure unit/integration/contract tests**

```bash
clojure -M:test
```

Expected: all existing tests pass, plus the new `:unit` / `:integration` / `:contract` tests added in Phase 1.

- [ ] **Step 3: Run lint and quality gates**

```bash
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test libs/tools/src
bb check:fcis
bb check:deps
bb check:placeholder-tests
bb doctor --env all --ci
```

Expected: all pass.

- [ ] **Step 4: Run the e2e suite end-to-end**

```bash
bb e2e
```

Expected: server starts, ~28 e2e tests pass, server tears down, exit 0. Wall time target: < 3 minutes.

- [ ] **Step 5: Sanity-check the branch**

```bash
git status
git log --oneline main..HEAD
```

Expected: clean working tree, ~24 commits on the branch.

- [ ] **Step 6: Final verification per superpowers:verification-before-completion**

Use `@superpowers:verification-before-completion` skill to confirm all the above commands exit 0 before claiming the plan is complete.

---

## DRY / YAGNI / TDD / commit notes

- **DRY:** `users/login`, `users/enable-mfa!`, `cookies/session-token`, the `with-fresh-seed` fixture — each defined once, reused everywhere. If a helper is used in only one test, inline it.
- **YAGNI:** single browser (Chromium, spel default), single worker (kaocha serial), no visual regression, no Allure reporting (spel supports it, but we don't need it yet), no seed configurability beyond `:baseline` / `:empty`.
- **TDD:** every Clojure task in Phase 1 and the helpers in Phase 3 writes a test before code. The API and HTML e2e specs (Phase 4-5) lock in already-existing server behaviour; if a test unexpectedly fails on first run, stop and investigate with `@superpowers:systematic-debugging` — likely a helper-level API mismatch, not a product bug.
- **Frequent commits:** one logical unit per commit (~22-24 commits total for the full plan).

## Skills to reference during execution

- `@superpowers:test-driven-development` for Phase 1 and helper tasks
- `@superpowers:systematic-debugging` if any test is mysteriously flaky
- `@superpowers:verification-before-completion` before declaring done
- `@superpowers:subagent-driven-development` to execute this plan
