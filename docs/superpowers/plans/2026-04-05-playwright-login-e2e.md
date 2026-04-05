# Playwright Login E2E Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Playwright end-to-end test suite that covers `/web/login`, `/web/register`, and `/api/auth/*` flows for the Boundary platform, runnable via `bb e2e` locally and in CI, with clean H2 state per test.

**Architecture:** A test-only `POST /test/reset` HTTP endpoint (mounted only when a profile flag is set) truncates H2 and re-seeds baseline tenant/users via production services. Playwright runs serially against a single app instance started by its own `webServer` config, with a custom `seed` auto-fixture that resets state before every test. TypeScript + `@playwright/test` + `otplib`.

**Tech Stack:** Clojure (test-support), Integrant, reitit, next.jdbc, HoneySQL, Babashka (`bb e2e`), TypeScript, `@playwright/test`, `otplib`, GitHub Actions.

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
- **MFA + API login (CRITICAL):** MFA-gated login cannot be tested via the JSON API — no `mfaCode` field exists in the login schema. MFA second-step is only implemented in the HTML `mfa-login-form` flow on `/web/login`. Tests for MFA-during-login belong in `web-login.spec.ts`, not any API spec.
- **User service:** `libs/user/src/boundary/user/shell/service.clj` — `UserService` record, `register-user` (line 116).
- **Tenant service:** `libs/tenant/src/boundary/tenant/shell/service.clj` — `create-new-tenant` (line 62).
- **Config:** Aero-based, loaded via `src/boundary/config.clj`. Profile env var `BND_ENV` (`test`/`dev`/`prod`/`acc`). Test config at `resources/conf/test/config.edn`.
- **Router composition:** `src/boundary/config.clj`, function `user-module-config` — merges user/tenant/membership/admin/workflow/search routes into the Integrant `:boundary/http-handler` component.
- **H2 in tests:** `:boundary/h2 {:memory true :pool {...}}` in `resources/conf/test/config.edn`. JDBC URL `jdbc:h2:mem:boundary;DB_CLOSE_DELAY=-1`.
- **H2 truncate gotcha:** H2 does not support `TRUNCATE ... CASCADE`. Use `SET REFERENTIAL_INTEGRITY FALSE` → `TRUNCATE TABLE <t>` per table → `SET REFERENTIAL_INTEGRITY TRUE`.
- **Tables:** `users`, `sessions`, `audit_logs`, `tenants`, `tenant_memberships`, `tenant_member_invites`.
- **FC/IS checker:** `boundary-tools/src/boundary/tools/check_fcis.clj`. Scans `libs/*/src/boundary/*/core/**`. Anything under `src/boundary/test_support/core/**` is **not** in that glob by default — the check will silently not cover it. Task 2 addresses this.
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

e2e/
├── package.json
├── tsconfig.json
├── playwright.config.ts
├── .gitignore
├── fixtures/
│   ├── app.ts                # test() extended with resetDb + api + seed auto-fixture
│   └── totp.ts               # otplib wrapper
├── helpers/
│   ├── reset.ts              # POST /test/reset wrapper
│   ├── users.ts              # createUserViaApi, loginViaApi, enableMfaForUser
│   └── cookies.ts            # session-token cookie assertions
└── tests/
    ├── html/
    │   ├── web-login.spec.ts       # ~10 tests
    │   └── web-register.spec.ts    # ~3 tests
    └── api/
        ├── auth-login.spec.ts      # 3 tests
        ├── auth-register.spec.ts   # 3 tests
        ├── auth-mfa.spec.ts        # 4 tests (setup, enable-ok, enable-wrong, status-after-disable)
        └── auth-sessions.spec.ts   # 4 tests (validate-ok, revoke, revoked-is-401, lockout, hash-leak)

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
- Modify: `boundary-tools/src/boundary/tools/check_fcis.clj`
- Test: `boundary-tools/test/boundary/tools/check_fcis_test.clj` (create if missing, else extend)

- [ ] **Step 1: Read the current FC/IS checker to find the scan root**

```bash
```

Read `boundary-tools/src/boundary/tools/check_fcis.clj` top-to-bottom. Find the function that enumerates paths/files. It is probably globbing `libs/*/src/boundary/*/core/**/*.clj` or similar.

- [ ] **Step 2: Write failing test**

Create or extend `boundary-tools/test/boundary/tools/check_fcis_test.clj`:

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
git add boundary-tools/src/boundary/tools/check_fcis.clj boundary-tools/test/boundary/tools/check_fcis_test.clj
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
        tenant   (tenant-ports/create-tenant tenant-service (:tenant spec))
        admin    (user-ports/register-user user-service
                                           (assoc (:admin spec) :tenant-id (:id tenant)))
        user     (user-ports/register-user user-service
                                           (assoc (:user spec) :tenant-id (:id tenant)))]
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
- Modify: `boundary-tools/src/boundary/tools/doctor.clj`
- Test: `boundary-tools/test/boundary/tools/doctor_test.clj`

- [ ] **Step 1: Write failing test**

Extend `boundary-tools/test/boundary/tools/doctor_test.clj`:

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
git add boundary-tools/src/boundary/tools/doctor.clj boundary-tools/test/boundary/tools/doctor_test.clj
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

### Task 9: `bb e2e` task

**Files:**
- Modify: `bb.edn`

- [ ] **Step 1: Add task**

Add under `:tasks`:

```clojure
e2e {:doc "Run Playwright end-to-end tests (starts server via playwright webServer)"
     :task (do (babashka.process/shell {:dir "e2e"} "npx" "playwright" "test"))}
```

- [ ] **Step 2: Verify help output**

```bash
bb tasks | grep e2e
```

Expected: `e2e` listed with its doc.

- [ ] **Step 3: Commit**

```bash
git add bb.edn
git commit -m "Add bb e2e task — runs Playwright suite"
```

(Task cannot be fully smoke-tested until Phase 3 finishes.)

---

## Phase 3 — Playwright scaffolding

### Task 10: `e2e/` project skeleton

**Files:**
- Create: `e2e/package.json`
- Create: `e2e/tsconfig.json`
- Create: `e2e/.gitignore`

- [ ] **Step 1: Create `e2e/package.json`**

```json
{
  "name": "boundary-e2e",
  "private": true,
  "version": "0.0.0",
  "scripts": {
    "test": "playwright test",
    "test:headed": "playwright test --headed",
    "report": "playwright show-report"
  },
  "devDependencies": {
    "@playwright/test": "^1.48.0",
    "@types/node": "^22.0.0",
    "otplib": "^12.0.1",
    "typescript": "^5.5.0"
  }
}
```

- [ ] **Step 2: Create `e2e/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "commonjs",
    "moduleResolution": "node",
    "strict": true,
    "esModuleInterop": true,
    "resolveJsonModule": true,
    "skipLibCheck": true,
    "types": ["node"]
  },
  "include": ["**/*.ts"],
  "exclude": ["node_modules", "playwright-report", "test-results"]
}
```

- [ ] **Step 3: Create `e2e/.gitignore`**

```
node_modules/
playwright-report/
test-results/
.playwright-cache/
```

- [ ] **Step 4: Install deps locally and verify**

```bash
cd e2e && npm install && cd ..
```

Expected: `e2e/node_modules/` created, no errors.

- [ ] **Step 5: Commit**

```bash
git add e2e/package.json e2e/tsconfig.json e2e/.gitignore e2e/package-lock.json
git commit -m "Scaffold e2e/ Playwright project (package.json, tsconfig, .gitignore)"
```

---

### Task 11: `playwright.config.ts`

**Files:**
- Create: `e2e/playwright.config.ts`

- [ ] **Step 1: Write config**

```ts
import { defineConfig, devices } from '@playwright/test';

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:3100';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: process.env.E2E_NO_WEBSERVER
    ? undefined
    : {
        command: 'cd .. && bb run-e2e-server',
        url: `${BASE_URL}/web/login`,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
        stdout: 'pipe',
        stderr: 'pipe',
      },
});
```

- [ ] **Step 2: Commit**

```bash
git add e2e/playwright.config.ts
git commit -m "Add Playwright config: single chromium project, serial, bb-managed server"
```

---

### Task 12: Reset helper

**Files:**
- Create: `e2e/helpers/reset.ts`

- [ ] **Step 1: Write helper**

```ts
import { APIRequestContext, expect } from '@playwright/test';

export type SeedKind = 'baseline' | 'empty';

export interface SeededAdmin {
  id: string;
  email: string;
  password: string;
  role: 'admin';
}

export interface SeededUser {
  id: string;
  email: string;
  password: string;
  role: 'user';
}

export interface SeededTenant {
  id: string;
  slug: string;
  name: string;
}

export interface SeedResult {
  tenant: SeededTenant;
  admin: SeededAdmin;
  user: SeededUser;
}

export async function resetDb(
  request: APIRequestContext,
  seed: SeedKind = 'baseline',
): Promise<SeedResult> {
  const res = await request.post('/test/reset', { data: { seed } });
  expect(
    res.ok(),
    `Failed to reset DB: ${res.status()} ${await res.text()}`,
  ).toBeTruthy();
  const body = (await res.json()) as { ok: boolean; seeded: SeedResult };
  expect(body.ok).toBe(true);
  return body.seeded;
}
```

- [ ] **Step 2: Commit**

```bash
git add e2e/helpers/reset.ts
git commit -m "Add reset helper for POST /test/reset"
```

---

### Task 13: Cookies helper

**Files:**
- Create: `e2e/helpers/cookies.ts`

- [ ] **Step 1: Write helper**

```ts
import { APIResponse, BrowserContext, expect } from '@playwright/test';

/**
 * Asserts that an APIResponse has a Set-Cookie header for session-token
 * with HttpOnly, and returns the raw token value.
 *
 * Uses headersArray() because headers() collapses duplicate Set-Cookie
 * values — Playwright docs recommend headersArray() for cookies.
 */
export function expectSessionCookie(response: APIResponse): string {
  const raw = response
    .headersArray()
    .filter((h) => h.name.toLowerCase() === 'set-cookie')
    .map((h) => h.value)
    .find((v) => v.startsWith('session-token='));
  expect(raw, `expected Set-Cookie: session-token=...; got headers: ${JSON.stringify(response.headersArray())}`).toBeTruthy();
  expect(raw!.toLowerCase()).toContain('httponly');
  const token = raw!.split(';')[0].split('=')[1];
  expect(token.length, 'session token should not be empty').toBeGreaterThan(0);
  return token;
}

export function expectNoSessionCookie(response: APIResponse): void {
  const raw = response
    .headersArray()
    .filter((h) => h.name.toLowerCase() === 'set-cookie')
    .map((h) => h.value)
    .find((v) => v.startsWith('session-token='));
  expect(raw, 'session-token cookie must not be set').toBeFalsy();
}

export async function getSessionCookieFromContext(
  context: BrowserContext,
): Promise<string | undefined> {
  const cookies = await context.cookies();
  return cookies.find((c) => c.name === 'session-token')?.value;
}

export async function getRememberedEmailCookie(
  context: BrowserContext,
): Promise<string | undefined> {
  const cookies = await context.cookies();
  return cookies.find((c) => c.name === 'remembered-email')?.value;
}
```

- [ ] **Step 2: Commit**

```bash
git add e2e/helpers/cookies.ts
git commit -m "Add cookie assertion helpers (Set-Cookie parsing, HttpOnly checks)"
```

---

### Task 14: TOTP helper

**Files:**
- Create: `e2e/fixtures/totp.ts`

- [ ] **Step 1: Write helper**

```ts
import { authenticator } from 'otplib';

/**
 * Generate the current TOTP code for a given base32 secret.
 * Uses default 30s window. Tests should be robust to window rollover;
 * if a test is flaky near window boundaries, consider generateAtDelta.
 */
export function currentTotp(secret: string): string {
  return authenticator.generate(secret);
}

/**
 * Wait until we're at least `safetyMs` away from a window rollover,
 * then return a fresh code. Reduces flakiness for time-sensitive tests.
 */
export async function freshTotp(
  secret: string,
  safetyMs = 2000,
): Promise<string> {
  const msIntoWindow = Date.now() % 30_000;
  const msLeft = 30_000 - msIntoWindow;
  if (msLeft < safetyMs) {
    await new Promise((r) => setTimeout(r, msLeft + 100));
  }
  return currentTotp(secret);
}
```

- [ ] **Step 2: Commit**

```bash
git add e2e/fixtures/totp.ts
git commit -m "Add TOTP helper using otplib"
```

---

### Task 15: API helpers (user/login/mfa)

**Files:**
- Create: `e2e/helpers/users.ts`

- [ ] **Step 1: Write helpers**

```ts
import { APIRequestContext, expect } from '@playwright/test';
import { currentTotp } from '../fixtures/totp';

export interface LoginBody {
  email: string;
  password: string;
  // NOTE: /api/v1/auth/login does NOT accept mfaCode (schema is :closed).
  // MFA second-step is only available via the HTML /web/login flow.
}

export async function loginViaApi(request: APIRequestContext, body: LoginBody) {
  return request.post('/api/v1/auth/login', { data: body });
}

export interface RegisterBody {
  email: string;
  password: string;
  name: string;
}

export async function registerViaApi(
  request: APIRequestContext,
  body: RegisterBody,
) {
  return request.post('/api/v1/auth/register', { data: body });
}

export interface MfaSetupResult {
  secret: string;
  backupCodes: string[];
  qrCodeUrl?: string;
}

/**
 * Enable MFA for a logged-in user. Returns the full setup result so callers
 * can generate TOTP codes and (if needed) reference backup codes later.
 *
 * The enable endpoint schema is :closed and requires the exact triple
 * {secret, backupCodes, verificationCode}.
 */
export async function enableMfaForUser(
  request: APIRequestContext,
  sessionToken: string,
): Promise<MfaSetupResult> {
  const setupRes = await request.post('/api/v1/auth/mfa/setup', {
    headers: { Cookie: `session-token=${sessionToken}` },
  });
  expect(setupRes.ok(), `mfa setup failed: ${setupRes.status()} ${await setupRes.text()}`).toBeTruthy();
  const setup = (await setupRes.json()) as MfaSetupResult;
  expect(typeof setup.secret).toBe('string');
  expect(Array.isArray(setup.backupCodes)).toBe(true);

  const verificationCode = currentTotp(setup.secret);
  const enableRes = await request.post('/api/v1/auth/mfa/enable', {
    headers: { Cookie: `session-token=${sessionToken}` },
    data: {
      secret: setup.secret,
      backupCodes: setup.backupCodes,
      verificationCode,
    },
  });
  expect(
    enableRes.ok(),
    `mfa enable failed: ${enableRes.status()} ${await enableRes.text()}`,
  ).toBeTruthy();
  return setup;
}

/** Disable MFA for a logged-in user. No request body. */
export async function disableMfaForUser(
  request: APIRequestContext,
  sessionToken: string,
) {
  const res = await request.post('/api/v1/auth/mfa/disable', {
    headers: { Cookie: `session-token=${sessionToken}` },
  });
  expect(res.ok(), `mfa disable failed: ${res.status()} ${await res.text()}`).toBeTruthy();
  return res;
}
```

**Engineer note:** The field names above (`secret`, `backupCodes`, `verificationCode`, `qrCodeUrl`) are taken directly from `libs/user/src/boundary/user/shell/http.clj:443-462`. If a test fails on first run because the setup response omits one of these, read the handler body in `web_handlers.clj` / the mfa-service implementation to see the real keys and adjust.

- [ ] **Step 2: Commit**

```bash
git add e2e/helpers/users.ts
git commit -m "Add API user helpers (login, register, MFA enable)"
```

---

### Task 16: Custom `test` fixture with seed auto-fixture

**Files:**
- Create: `e2e/fixtures/app.ts`

- [ ] **Step 1: Write fixture**

```ts
import { test as base, APIRequestContext } from '@playwright/test';
import { resetDb, SeedResult, SeedKind } from '../helpers/reset';

type Fixtures = {
  resetDb: (kind?: SeedKind) => Promise<SeedResult>;
  seed: SeedResult;
};

export const test = base.extend<Fixtures>({
  resetDb: async ({ request }, use) => {
    await use((kind = 'baseline') => resetDb(request, kind));
  },
  // Auto-fixture: run baseline reset before every test.
  seed: [
    async ({ request }, use) => {
      const s = await resetDb(request, 'baseline');
      await use(s);
    },
    { auto: true },
  ],
});

export { expect } from '@playwright/test';
export type { SeedResult } from '../helpers/reset';
```

- [ ] **Step 2: Write a smoke test that proves the fixture works**

Create `e2e/tests/_smoke.spec.ts`:

```ts
import { test, expect } from '../fixtures/app';

test('smoke: server reachable + seed fixture yields admin', async ({ request, seed }) => {
  expect(seed.admin.email).toBe('admin@acme.test');
  const res = await request.get('/web/login');
  expect(res.status()).toBe(200);
});
```

- [ ] **Step 3: Run the smoke test**

```bash
bb e2e -- _smoke.spec.ts
```

Expected: Playwright starts the server via `bb run-e2e-server`, the smoke test passes.

- [ ] **Step 4: Commit**

```bash
git add e2e/fixtures/app.ts e2e/tests/_smoke.spec.ts
git commit -m "Add Playwright seed auto-fixture + smoke test"
```

---

## Phase 4 — API test specs

All API tests use the `test` from `fixtures/app.ts`, which gives them a fresh baseline via the `seed` auto-fixture before every test.

### Task 17: `auth-login.spec.ts`

**Files:**
- Create: `e2e/tests/api/auth-login.spec.ts`

**Pre-step:** before writing the tests, run the server once and hit the endpoint manually to learn the real success-response shape:

```bash
bb run-e2e-server &
sleep 10
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"seed":"baseline"}' http://localhost:3100/test/reset
curl -s -i -X POST -H "Content-Type: application/json" \
  -d '{"email":"admin@acme.test","password":"Test-Pass-1234!"}' \
  http://localhost:3100/api/v1/auth/login
kill %1
```

Copy the observed JSON shape into your head (or a scratch note) so the assertions below are grounded, not guessed. The reference facts already confirm that a successful login sets `session-token` via `Set-Cookie`, but the JSON body structure is not documented and must be observed.

- [ ] **Step 1: Write tests**

```ts
import { test, expect } from '../../fixtures/app';
import { loginViaApi } from '../../helpers/users';
import { expectSessionCookie } from '../../helpers/cookies';

test.describe('POST /api/v1/auth/login', () => {
  test('happy: valid credentials return success + set session cookie', async ({ request, seed }) => {
    const res = await loginViaApi(request, {
      email: seed.admin.email,
      password: seed.admin.password,
    });
    expect(res.ok()).toBeTruthy();
    // API sets httpOnly cookie via Set-Cookie header
    expectSessionCookie(res);
    // Response MUST NOT leak password-hash in any casing
    const raw = await res.text();
    expect(raw).not.toMatch(/password[-_]?hash/i);
  });

  test('wrong password → 401, no session cookie', async ({ request, seed }) => {
    const res = await loginViaApi(request, {
      email: seed.admin.email,
      password: 'wrong-password',
    });
    expect(res.status()).toBe(401);
  });

  test('unknown email → 401 (no user enumeration)', async ({ request }) => {
    const res = await loginViaApi(request, {
      email: 'nobody@nowhere.test',
      password: 'whatever',
    });
    expect(res.status()).toBe(401);
    const body = await res.text();
    // Generic error — must not reveal whether the email exists
    expect(body.toLowerCase()).not.toContain('not found');
    expect(body.toLowerCase()).not.toContain('does not exist');
  });
});
```

- [ ] **Step 2: Run**

```bash
bb e2e -- api/auth-login.spec.ts
```

Expected: PASS 3/3. The test intentionally does not assert on a specific JSON body shape — the `Set-Cookie` check and 401 status codes are the contract we care about. DO NOT weaken assertions on `password-hash` absence or 401-on-wrong-creds; those are security requirements.

- [ ] **Step 3: Commit**

```bash
git add e2e/tests/api/auth-login.spec.ts
git commit -m "Add API login tests: happy, wrong password, unknown email"
```

---

### Task 18: `auth-register.spec.ts`

**Files:**
- Create: `e2e/tests/api/auth-register.spec.ts`

- [ ] **Step 1: Write tests**

```ts
import { test, expect } from '../../fixtures/app';
import { registerViaApi } from '../../helpers/users';

test.describe('POST /api/v1/auth/register', () => {
  test('happy: creates user, returns session token', async ({ request }) => {
    const res = await registerViaApi(request, {
      email: 'new-user@acme.test',
      password: 'A-Strong-Pass-9999!',
      name: 'New User',
    });
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(JSON.stringify(body)).not.toContain('password-hash');
  });

  test('duplicate email → 409', async ({ request, seed }) => {
    const res = await registerViaApi(request, {
      email: seed.admin.email,
      password: 'Another-Strong-Pass-1!',
      name: 'Dup',
    });
    expect(res.status()).toBe(409);
  });

  test('weak password → 400 with policy details', async ({ request }) => {
    const res = await registerViaApi(request, {
      email: 'weakpass@acme.test',
      password: 'abc',
      name: 'Weak',
    });
    expect(res.status()).toBe(400);
    const body = await res.json();
    // Policy errors should be present
    expect(JSON.stringify(body).toLowerCase()).toMatch(/password|policy|length/);
  });
});
```

- [ ] **Step 2: Run + fix assertions if exact error shape differs**

```bash
bb e2e -- api/auth-register.spec.ts
```

- [ ] **Step 3: Commit**

```bash
git add e2e/tests/api/auth-register.spec.ts
git commit -m "Add API register tests: happy, duplicate, weak password"
```

---

### Task 19: `auth-mfa.spec.ts`

**Scope note:** `/api/v1/auth/login` schema is `:closed` and does **not**
accept an `mfaCode` field. MFA-gated login via the JSON API does not
exist in this codebase; MFA second-step is handled exclusively by the
HTML `mfa-login-form` flow, covered by `web-login.spec.ts`. This API spec
covers only the MFA management endpoints.

**Files:**
- Create: `e2e/tests/api/auth-mfa.spec.ts`

- [ ] **Step 1: Write tests**

```ts
import { test, expect } from '../../fixtures/app';
import { loginViaApi, enableMfaForUser, disableMfaForUser } from '../../helpers/users';
import { expectSessionCookie } from '../../helpers/cookies';

test.describe('MFA management API', () => {
  test('setup returns secret + backup codes', async ({ request, seed }) => {
    const login = await loginViaApi(request, { email: seed.user.email, password: seed.user.password });
    const token = expectSessionCookie(login);
    const res = await request.post('/api/v1/auth/mfa/setup', {
      headers: { Cookie: `session-token=${token}` },
    });
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(typeof body.secret).toBe('string');
    expect(body.secret.length).toBeGreaterThan(0);
    expect(Array.isArray(body.backupCodes)).toBe(true);
    expect(body.backupCodes.length).toBeGreaterThan(0);
  });

  test('enable with correct verification code activates MFA (status reflects it)', async ({ request, seed }) => {
    const login = await loginViaApi(request, { email: seed.user.email, password: seed.user.password });
    const token = expectSessionCookie(login);
    await enableMfaForUser(request, token);

    const statusRes = await request.get('/api/v1/auth/mfa/status', {
      headers: { Cookie: `session-token=${token}` },
    });
    expect(statusRes.ok()).toBeTruthy();
    const status = await statusRes.json();
    // Field name is not documented — accept any truthy "enabled" indicator.
    expect(status.enabled ?? status.mfaEnabled ?? status['mfa-enabled']).toBeTruthy();
  });

  test('enable with wrong verification code is rejected', async ({ request, seed }) => {
    const login = await loginViaApi(request, { email: seed.user.email, password: seed.user.password });
    const token = expectSessionCookie(login);

    const setupRes = await request.post('/api/v1/auth/mfa/setup', {
      headers: { Cookie: `session-token=${token}` },
    });
    const setup = await setupRes.json();

    const enableRes = await request.post('/api/v1/auth/mfa/enable', {
      headers: { Cookie: `session-token=${token}` },
      data: {
        secret: setup.secret,
        backupCodes: setup.backupCodes,
        verificationCode: '000000',
      },
    });
    expect(enableRes.ok()).toBeFalsy();

    // Status should still be disabled
    const statusRes = await request.get('/api/v1/auth/mfa/status', {
      headers: { Cookie: `session-token=${token}` },
    });
    const status = await statusRes.json();
    expect(status.enabled ?? status.mfaEnabled ?? status['mfa-enabled']).toBeFalsy();
  });

  test('disable turns MFA off', async ({ request, seed }) => {
    const login = await loginViaApi(request, { email: seed.user.email, password: seed.user.password });
    const token = expectSessionCookie(login);
    await enableMfaForUser(request, token);
    await disableMfaForUser(request, token);

    const statusRes = await request.get('/api/v1/auth/mfa/status', {
      headers: { Cookie: `session-token=${token}` },
    });
    const status = await statusRes.json();
    expect(status.enabled ?? status.mfaEnabled ?? status['mfa-enabled']).toBeFalsy();
  });
});
```

- [ ] **Step 2: Run**

```bash
bb e2e -- api/auth-mfa.spec.ts
```

If the status response field name is something other than `enabled` / `mfaEnabled` / `mfa-enabled`, read `mfa-status-handler` (around `http.clj:314-332`) to learn the real key and tighten the assertion. Do not leave the triple-fallback in place long term — pick one.

- [ ] **Step 3: Commit**

```bash
git add e2e/tests/api/auth-mfa.spec.ts
git commit -m "Add API MFA management tests: setup, enable, wrong-code, disable"
```

---

### Task 20: `auth-sessions.spec.ts`

**Scope note:** there is no `GET /api/v1/sessions` list endpoint. Tests
obtain the session token from the login `Set-Cookie` header and call
`GET /api/v1/sessions/:token` to validate or `DELETE` to revoke it.

**Files:**
- Create: `e2e/tests/api/auth-sessions.spec.ts`

**Pre-step:** determine the real account-lockout threshold before writing the lockout test. Read `libs/user/src/boundary/user/core/` (grep for `max-attempts`, `lockout`, `failed-login`) to find the configured threshold and expected status code. Do not guess.

- [ ] **Step 1: Write tests**

```ts
import { test, expect } from '../../fixtures/app';
import { loginViaApi, registerViaApi } from '../../helpers/users';
import { expectSessionCookie } from '../../helpers/cookies';

test.describe('Sessions + security', () => {
  test('GET /api/v1/sessions/:token validates a live token', async ({ request, seed }) => {
    const login = await loginViaApi(request, { email: seed.user.email, password: seed.user.password });
    const token = expectSessionCookie(login);
    const res = await request.get(`/api/v1/sessions/${token}`);
    expect(res.ok()).toBeTruthy();
  });

  test('DELETE /api/v1/sessions/:token revokes the session; subsequent GET is 401', async ({ request, seed }) => {
    const login = await loginViaApi(request, { email: seed.user.email, password: seed.user.password });
    const token = expectSessionCookie(login);

    const del = await request.delete(`/api/v1/sessions/${token}`);
    expect(del.ok()).toBeTruthy();

    const followup = await request.get(`/api/v1/sessions/${token}`);
    expect(followup.status()).toBe(401);
  });

  test('MFA status without token → 401', async ({ request }) => {
    // /api/v1/auth/mfa/status requires authentication via middleware
    const res = await request.get('/api/v1/auth/mfa/status');
    expect(res.status()).toBe(401);
  });

  test('account lockout after repeated failures', async ({ request, seed }) => {
    // ADJUST THIS COUNT based on the pre-step lookup in libs/user/src/boundary/user/core/.
    // The loop count must equal or exceed the real threshold.
    const threshold = 5;  // PLACEHOLDER — replace with verified value
    for (let i = 0; i < threshold + 2; i++) {
      await loginViaApi(request, { email: seed.user.email, password: 'wrong' });
    }
    const res = await loginViaApi(request, { email: seed.user.email, password: seed.user.password });
    // Expect lockout — one of these status codes, with a clear error message
    expect([401, 423, 429]).toContain(res.status());
    const body = await res.text();
    expect(body.toLowerCase()).toMatch(/lock|attempts|try again|too many/);
  });

  test('password-hash never appears in any auth response', async ({ request, seed }) => {
    const responses = await Promise.all([
      loginViaApi(request, { email: seed.admin.email, password: seed.admin.password }),
      registerViaApi(request, { email: 'nb@acme.test', password: 'Strong-Pass-12345!', name: 'NB' }),
    ]);
    for (const r of responses) {
      const t = await r.text();
      expect(t).not.toMatch(/password[-_]?hash/i);
    }
  });
});
```

- [ ] **Step 2: Run**

```bash
bb e2e -- api/auth-sessions.spec.ts
```

If the lockout test fails because the threshold guess is wrong, adjust the constant. If `DELETE /api/v1/sessions/:token` returns something other than 2xx on success, read `invalidate-session-handler` in `web_handlers.clj` / `http.clj` for the real behaviour.

- [ ] **Step 3: Commit**

```bash
git add e2e/tests/api/auth-sessions.spec.ts
git commit -m "Add API sessions + security tests: validate, revoke, unauth, lockout, hash-leak"
```

---

## Phase 5 — HTML test specs

### Task 21: `web-login.spec.ts`

**Files:**
- Create: `e2e/tests/html/web-login.spec.ts`

- [ ] **Step 1: Write tests**

```ts
import { test, expect } from '../../fixtures/app';
import { getSessionCookieFromContext, getRememberedEmailCookie } from '../../helpers/cookies';

test.describe('/web/login HTML flow', () => {
  test('GET renders login form with email + password fields', async ({ page }) => {
    await page.goto('/web/login');
    const form = page.locator('form.form-card[action="/web/login"]');
    await expect(form).toBeVisible();
    await expect(form.locator('input[name="email"]')).toBeVisible();
    await expect(form.locator('input[name="password"]')).toBeVisible();
    await expect(form.locator('input[name="remember"]')).toBeVisible();
  });

  test('happy path: regular user is redirected to /web/dashboard', async ({ page, context, seed }) => {
    await page.goto('/web/login');
    await page.fill('input[name="email"]', seed.user.email);
    await page.fill('input[name="password"]', seed.user.password);
    await page.click('button[type="submit"]');
    await page.waitForURL('**/web/dashboard');
    const token = await getSessionCookieFromContext(context);
    expect(token).toBeTruthy();
  });

  test('happy path: admin user is redirected to /web/admin/users', async ({ page, seed }) => {
    await page.goto('/web/login');
    await page.fill('input[name="email"]', seed.admin.email);
    await page.fill('input[name="password"]', seed.admin.password);
    await page.click('button[type="submit"]');
    await page.waitForURL('**/web/admin/users');
  });

  test('return-to is honoured after successful login', async ({ page, seed }) => {
    await page.goto('/web/login?return-to=/web/dashboard/settings');
    await page.fill('input[name="email"]', seed.user.email);
    await page.fill('input[name="password"]', seed.user.password);
    await page.click('button[type="submit"]');
    await page.waitForURL('**/web/dashboard/settings');
  });

  test('remember-me sets remembered-email cookie', async ({ page, context, seed }) => {
    await page.goto('/web/login');
    await page.fill('input[name="email"]', seed.user.email);
    await page.fill('input[name="password"]', seed.user.password);
    await page.check('input[name="remember"]');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/web/dashboard');
    const remembered = await getRememberedEmailCookie(context);
    expect(remembered).toBe(seed.user.email);
  });

  test('remembered-email cookie prefills the form on next visit', async ({ page, context, seed }) => {
    // First login with remember
    await page.goto('/web/login');
    await page.fill('input[name="email"]', seed.user.email);
    await page.fill('input[name="password"]', seed.user.password);
    await page.check('input[name="remember"]');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/web/dashboard');
    // Clear session cookie but keep remembered-email
    const cookies = (await context.cookies()).filter((c) => c.name !== 'session-token');
    await context.clearCookies();
    await context.addCookies(cookies);
    // Revisit login page
    await page.goto('/web/login');
    await expect(page.locator('input[name="email"]')).toHaveValue(seed.user.email);
  });

  test('invalid credentials: re-render with error, no session cookie', async ({ page, context, seed }) => {
    await page.goto('/web/login');
    await page.fill('input[name="email"]', seed.user.email);
    await page.fill('input[name="password"]', 'wrong-password');
    await page.click('button[type="submit"]');
    // Stay on /web/login, show an error
    await expect(page).toHaveURL(/\/web\/login/);
    await expect(page.locator('.validation-errors')).toBeVisible();
    const token = await getSessionCookieFromContext(context);
    expect(token).toBeFalsy();
  });

  test('empty fields show validation errors', async ({ page }) => {
    await page.goto('/web/login');
    // Bypass browser-side required by dispatching the form without typing
    await page.evaluate(() => {
      const form = document.querySelector<HTMLFormElement>('form.form-card')!;
      form.noValidate = true;
      form.submit();
    });
    await expect(page).toHaveURL(/\/web\/login/);
    await expect(page.locator('.validation-errors').first()).toBeVisible();
  });

  test('MFA-required: second form is shown, wrong code rejected, valid code succeeds', async ({ page, request, seed }) => {
    // Arrange: enable MFA for seed.user via API
    const login = await request.post('/api/v1/auth/login', {
      data: { email: seed.user.email, password: seed.user.password },
    });
    const setCookie = login
      .headersArray()
      .find((h) => h.name.toLowerCase() === 'set-cookie' && h.value.startsWith('session-token='))!
      .value;
    const token = setCookie.split(';')[0].split('=')[1];

    const { enableMfaForUser } = await import('../../helpers/users');
    const { freshTotp } = await import('../../fixtures/totp');
    const mfa = await enableMfaForUser(request, token);

    // Act: login in the browser — must show MFA second-step form
    await page.goto('/web/login');
    await page.fill('input[name="email"]', seed.user.email);
    await page.fill('input[name="password"]', seed.user.password);
    await page.click('button[type="submit"]');
    await expect(page.locator('input[name="mfa-code"]')).toBeVisible();

    // Wrong code is rejected
    await page.fill('input[name="mfa-code"]', '000000');
    await page.click('button[type="submit"]');
    await expect(page.locator('.validation-errors, .auth-error')).toBeVisible();
    await expect(page).not.toHaveURL(/dashboard/);

    // Valid code succeeds
    const code = await freshTotp(mfa.secret);
    await page.fill('input[name="mfa-code"]', code);
    await page.click('button[type="submit"]');
    await page.waitForURL('**/web/dashboard');
  });
});
```

- [ ] **Step 2: Run**

```bash
bb e2e -- html/web-login.spec.ts
```

Expected: all tests pass. The MFA test is the most fragile — if it's flaky, review `freshTotp` and the auth handler's code-verification window.

- [ ] **Step 3: Commit**

```bash
git add e2e/tests/html/web-login.spec.ts
git commit -m "Add HTML login tests: happy, remember-me, return-to, invalid, MFA"
```

---

### Task 22: `web-register.spec.ts`

**Files:**
- Create: `e2e/tests/html/web-register.spec.ts`

- [ ] **Step 1: Write tests**

```ts
import { test, expect } from '../../fixtures/app';
import { getSessionCookieFromContext } from '../../helpers/cookies';

test.describe('/web/register HTML flow', () => {
  test('GET renders register form', async ({ page }) => {
    await page.goto('/web/register');
    const form = page.locator('form.form-card[action="/web/register"]');
    await expect(form).toBeVisible();
    await expect(form.locator('input[name="name"]')).toBeVisible();
    await expect(form.locator('input[name="email"]')).toBeVisible();
    await expect(form.locator('input[name="password"]')).toBeVisible();
  });

  test('happy path creates user + session cookie', async ({ page, context }) => {
    await page.goto('/web/register');
    await page.fill('input[name="name"]', 'Fresh User');
    await page.fill('input[name="email"]', 'fresh@acme.test');
    await page.fill('input[name="password"]', 'A-Strong-Pass-9876!');
    await page.click('button[type="submit"]');
    // Wait for redirect away from /web/register
    await page.waitForURL((u) => !u.pathname.endsWith('/web/register'));
    const token = await getSessionCookieFromContext(context);
    expect(token).toBeTruthy();
  });

  test('weak password shows per-field validation errors', async ({ page }) => {
    await page.goto('/web/register');
    await page.fill('input[name="name"]', 'Weak');
    await page.fill('input[name="email"]', 'weak@acme.test');
    await page.fill('input[name="password"]', 'abc');
    await page.click('button[type="submit"]');
    await expect(page).toHaveURL(/\/web\/register/);
    await expect(page.locator('.validation-errors').first()).toBeVisible();
  });
});
```

- [ ] **Step 2: Run**

```bash
bb e2e -- html/web-register.spec.ts
```

- [ ] **Step 3: Commit**

```bash
git add e2e/tests/html/web-register.spec.ts
git commit -m "Add HTML register tests: form render, happy path, weak password"
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
  # End-to-end Playwright tests
  # =============================================================================
  e2e:
    name: Playwright E2E
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

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: "22"

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
          key: ${{ runner.os }}-playwright-${{ hashFiles('e2e/package-lock.json') }}

      - name: Install e2e npm deps
        working-directory: e2e
        run: npm ci

      - name: Install Playwright browsers
        working-directory: e2e
        run: npx playwright install --with-deps chromium

      - name: Run e2e
        run: bb e2e
        env:
          BND_ENV: test
          CI: "true"

      - name: Upload Playwright report on failure
        if: failure()
        uses: actions/upload-artifact@v7
        with:
          name: playwright-report
          path: e2e/playwright-report/
          retention-days: 7
```

- [ ] **Step 2: Validate YAML**

```bash
# Cheap syntax check
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml'))" && echo OK
```

Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "Add e2e job to CI workflow"
```

---

## Phase 7 — Final verification

### Task 24: Full local run

- [ ] **Step 1: Clean build**

```bash
rm -rf e2e/node_modules e2e/playwright-report e2e/test-results
cd e2e && npm ci && npx playwright install chromium && cd ..
```

- [ ] **Step 2: Run unit + integration + contract tests**

```bash
clojure -M:test:db/h2
```

Expected: all existing tests still pass, plus new `:unit`, `:integration`, `:contract` tests added in Phase 1.

- [ ] **Step 3: Run lint**

```bash
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test boundary-tools/src
bb check:fcis
bb check:deps
bb check:placeholder-tests
bb doctor --env all --ci
```

Expected: all pass.

- [ ] **Step 4: Run Playwright suite end-to-end**

```bash
bb e2e
```

Expected: all ~32 tests pass. Total wall time < 3 minutes.

- [ ] **Step 5: Verify the smoke test file is removed or kept**

Decision: keep `_smoke.spec.ts` as a minimal "is the server up" canary — it's useful and cheap. If you prefer to remove it, `git rm` it and commit.

- [ ] **Step 6: Sanity-check nothing committed accidentally**

```bash
git status
git log --oneline main..HEAD
```

Expected: clean working tree, ~24 commits on the branch.

- [ ] **Step 7: Final verification per superpowers:verification-before-completion**

Use `@superpowers:verification-before-completion` skill to confirm:
- `bb e2e` exits 0
- `clojure -M:test:db/h2` exits 0
- `bb check:fcis`, `bb check:deps`, `bb doctor --env all --ci` all exit 0

Only then claim the plan is complete.

---

## DRY / YAGNI / TDD / commit notes

- **DRY:** `loginViaApi`, `enableMfaForUser`, `expectSessionCookie`, the `seed` fixture — each defined once, reused everywhere. If a helper is used in only one test, inline it.
- **YAGNI:** single browser (Chromium), single worker, no visual regression, no multi-tenant scenarios beyond what exists, no seed configurability beyond `baseline` / `empty`.
- **TDD:** every Clojure task (1-7) writes test before code. Playwright tasks (17-22) don't have a formal red-green cycle because the server behaviour already exists — the tests characterize and lock in existing behaviour. If a test unexpectedly fails on first run, stop and investigate with `@superpowers:systematic-debugging`.
- **Frequent commits:** one logical unit per commit (~24 commits for the plan).

## Skills to reference during execution

- `@superpowers:test-driven-development` for Phase 1 Clojure tasks
- `@superpowers:systematic-debugging` if any test is mysteriously flaky
- `@superpowers:verification-before-completion` before declaring done
- `@superpowers:subagent-driven-development` to execute this plan
