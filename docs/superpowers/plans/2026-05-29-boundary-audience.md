# Boundary Audience Segmentation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a declarative audience segmentation library (`boundary-audience`) with hybrid SQL+predicate evaluation, composable segments, two-layer caching, and a visual builder UI.

**Architecture:** Single library under `libs/audience/` following FC/IS pattern. Core layer has pure segment logic (`defaudience` macro, filter multimethods, AND/OR/NOT composition, compiler). Shell layer has persistence (next.jdbc), caching (boundary-cache + membership table), evaluation service (`IAudienceResolver`), HTTP handlers, and Integrant wiring. Builder UI uses Hiccup+HTMX for page chrome, ClojureScript+Replicant for interactive widgets.

**Tech Stack:** Clojure 1.12.4, Malli (validation), HoneySQL (query building), next.jdbc (DB), Integrant (DI), Hiccup+HTMX (server UI), ClojureScript+Replicant (builder widgets), Kaocha (tests), H2 (test DB)

**Spec:** `docs/superpowers/specs/2026-05-29-boundary-audience-design.md`
**ADR:** `dev-docs/adr/ADR-030-audience-segmentation.adoc`

---

## File Map

### New Files (Create)

| File | Responsibility |
|------|---------------|
| `libs/audience/deps.edn` | Library dependencies |
| `libs/audience/build.clj` | Maven build/deploy config |
| `libs/audience/src/boundary/audience/schema.clj` | Malli schemas: FilterDef, AudienceDefinition, SegmentResult, etc. |
| `libs/audience/src/boundary/audience/ports.clj` | Protocols: IAudienceResolver, IAudienceRepository, IAudienceCache, IUserDataSource |
| `libs/audience/src/boundary/audience/core/audience.clj` | `defaudience` macro, atom registry, helpers |
| `libs/audience/src/boundary/audience/core/filter.clj` | `filter->sql` / `filter->predicate` multimethods + 7 built-in types |
| `libs/audience/src/boundary/audience/core/composition.clj` | AND/OR/NOT resolution, circular ref detection |
| `libs/audience/src/boundary/audience/core/compiler.clj` | Compile segment → `{:sql-clauses [...] :predicates [...]}` |
| `libs/audience/src/boundary/audience/core/ui.clj` | Hiccup: segment-card, filter-badge, segment-list |
| `libs/audience/src/boundary/audience/shell/persistence.clj` | IAudienceRepository impl (next.jdbc + HoneySQL) |
| `libs/audience/src/boundary/audience/shell/cache.clj` | IAudienceCache impl (membership table + boundary-cache) |
| `libs/audience/src/boundary/audience/shell/service.clj` | IAudienceResolver impl (evaluation orchestrator) |
| `libs/audience/src/boundary/audience/shell/http.clj` | Ring routes: builder pages + API endpoints |
| `libs/audience/src/boundary/audience/shell/module_wiring.clj` | Integrant init-key/halt-key! |
| `libs/audience/test/boundary/audience/core/audience_test.clj` | Unit: macro, registry |
| `libs/audience/test/boundary/audience/core/filter_test.clj` | Unit: SQL gen, predicate compilation |
| `libs/audience/test/boundary/audience/core/composition_test.clj` | Unit: AND/OR/NOT, circular refs |
| `libs/audience/test/boundary/audience/core/compiler_test.clj` | Unit: execution plan generation |
| `libs/audience/test/boundary/audience/shell/persistence_test.clj` | Contract: DB round-trips (H2) |
| `libs/audience/test/boundary/audience/shell/cache_test.clj` | Integration: TTL, invalidation |
| `libs/audience/test/boundary/audience/shell/service_test.clj` | Integration: full eval pipeline |
| `libs/audience/test/boundary/audience/shell/security_test.clj` | Security: SQL injection, fn-value rejection |
| `resources/migrations/audience/20260529000000-audience-segments.up.sql` | DDL: audience_segments + audience_memberships |
| `resources/migrations/audience/20260529000000-audience-segments.down.sql` | DDL: drop tables |
| `resources/conf/dev/admin/audiences.edn` | Admin entity config |
| `resources/conf/test/admin/audiences.edn` | Admin entity config (test env) |
| `libs/audience/AGENTS.md` | Developer guide |

### Files to Modify

| File | Change |
|------|--------|
| `deps.edn` (root) | Add `"libs/audience/src" "libs/audience/test"` to `:paths` |
| `tests.edn` (root) | Add `:audience` test suite |
| `.clj-kondo/config.edn` | Add `defaudience` to `:lint-as` map |

---

## Task 1: Library Scaffold

**Files:**
- Create: `libs/audience/deps.edn`
- Create: `libs/audience/build.clj`
- Modify: `deps.edn` (root)
- Modify: `tests.edn` (root)
- Modify: `.clj-kondo/config.edn`

- [ ] **Step 1: Create `libs/audience/deps.edn`**

```clojure
{:paths ["src"]

 :deps  {org.clojure/clojure       {:mvn/version "1.12.4"}
         metosin/malli             {:mvn/version "0.20.1"}
         com.github.seancorfield/honeysql {:mvn/version "2.6.1230"}
         com.github.seancorfield/next.jdbc {:mvn/version "1.3.967"}
         cheshire/cheshire         {:mvn/version "6.2.0"}
         hiccup/hiccup             {:mvn/version "2.0.0"}
         org.clojure/tools.logging {:mvn/version "1.3.1"}}

 :aliases
 {:test      {:extra-paths ["test"]
              :extra-deps  {lambdaisland/kaocha {:mvn/version "1.91.1392"}}
              :main-opts   ["-m" "kaocha.runner"]}

  :clj-kondo {:replace-deps {clj-kondo/clj-kondo {:mvn/version "2026.04.15"}}
              :main-opts    ["-m" "clj-kondo.main"]}

  :build     {:replace-deps {io.github.clojure/tools.build {:git/tag "v0.10.13" :git/sha "3a3c177d"}
                             slipset/deps-deploy           {:mvn/version "0.2.3"}}
              :ns-default   build}}}
```

- [ ] **Step 2: Create `libs/audience/build.clj`**

Model after `libs/calendar/build.clj`. Use lib name `org.boundary-app/boundary-audience`, version matching current suite version (check `libs/calendar/build.clj` for exact version string). Description: `"Audience segmentation for Boundary framework: declarative segments, hybrid evaluation, composable filters, and visual builder UI"`.

- [ ] **Step 3: Add audience paths to root `deps.edn`**

Add after the last library paths entry (i18n/resources):

```clojure
"libs/audience/src" "libs/audience/test"       ;; Phase 21: Audience
```

- [ ] **Step 4: Add `:audience` test suite to `tests.edn`**

Add after the last per-library suite:

```clojure
{:id :audience
 :test-paths ["libs/audience/test"]
 :ns-patterns ["boundary.audience.*-test"]}
```

Also add `"libs/audience/src"` to the top-level `:kaocha/source-paths` and `"libs/audience/test"` to the `:unit` test suite `:test-paths`.

- [ ] **Step 5: Add `defaudience` to `.clj-kondo/config.edn`**

Add to the `:lint-as` map:

```clojure
boundary.audience.core.audience/defaudience clojure.core/def
```

- [ ] **Step 6: Create directory structure**

```bash
mkdir -p libs/audience/src/boundary/audience/core
mkdir -p libs/audience/src/boundary/audience/shell
mkdir -p libs/audience/test/boundary/audience/core
mkdir -p libs/audience/test/boundary/audience/shell
mkdir -p resources/migrations/audience
```

- [ ] **Step 7: Verify scaffold compiles**

```bash
clojure -M:test:db/h2 :audience
```

Expected: 0 tests, 0 assertions (no test files yet). Should not error.

- [ ] **Step 8: Commit**

```bash
git add libs/audience/deps.edn libs/audience/build.clj deps.edn tests.edn .clj-kondo/config.edn
git commit -m "feat(audience): scaffold library structure"
```

---

## Task 2: Schema + Ports

**Files:**
- Create: `libs/audience/src/boundary/audience/schema.clj`
- Create: `libs/audience/src/boundary/audience/ports.clj`

- [ ] **Step 1: Write schema.clj**

Implement all schemas from the spec: `FilterDef`, `SegmentRef`, `Composable` (with Malli recursive registry), `CacheConfig`, `AudienceDefinition`, `DynamicAudienceDefinition` (no fn values), `SegmentResult`, `MembershipRecord`. See spec section "Core Domain Model > Schemas" for exact definitions.

Key difference: `DynamicAudienceDefinition` uses `[:not fn?]` validator on `:value` field to prevent fn refs in DB-persisted segments.

- [ ] **Step 2: Write ports.clj**

Four protocols exactly as specified:
- `IAudienceResolver` — `resolve-audience` (2 arities), `member?`
- `IAudienceRepository` — `save-audience`, `find-audience`, `list-audiences` (2 arities), `delete-audience`
- `IAudienceCache` — `get-cached`, `put-cached`, `invalidate`, `invalidate-all`
- `IUserDataSource` — `query-users-sql`, `load-users`

Use docstrings matching spec. Follow pattern from `libs/search/src/boundary/search/ports.clj`.

- [ ] **Step 3: Verify compiles**

```bash
clojure -e "(require '[boundary.audience.schema] '[boundary.audience.ports])"
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add libs/audience/src/boundary/audience/schema.clj libs/audience/src/boundary/audience/ports.clj
git commit -m "feat(audience): add Malli schemas and port protocols"
```

---

## Task 3: `defaudience` Macro + Registry

**Files:**
- Create: `libs/audience/src/boundary/audience/core/audience.clj`
- Create: `libs/audience/test/boundary/audience/core/audience_test.clj`

- [ ] **Step 1: Write failing tests for audience_test.clj**

```clojure
(ns boundary.audience.core.audience-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.audience.core.audience :as audience]))

(use-fixtures :each
  (fn [f]
    (audience/clear-registry!)
    (f)))

(deftest defaudience-registers-definition
  (testing "defaudience creates var and registers by :id"
    (audience/defaudience test-segment
      {:id      :test-segment
       :label   "Test"
       :filters [{:type :demographics :field :plan :op :eq :value "free"}]})
    (is (= :test-segment (:id test-segment)))
    (is (= "Test" (:label (audience/get-audience :test-segment))))))

(deftest registry-operations
  (testing "list-audiences returns registered ids"
    (audience/register-audience! {:id :seg-a :label "A" :filters []})
    (audience/register-audience! {:id :seg-b :label "B" :filters []})
    (is (= #{:seg-a :seg-b} (set (audience/list-audiences)))))

  (testing "get-audience returns nil for unknown id"
    (is (nil? (audience/get-audience :nonexistent))))

  (testing "clear-registry! empties registry"
    (audience/register-audience! {:id :seg-c :label "C" :filters []})
    (audience/clear-registry!)
    (is (empty? (audience/list-audiences)))))
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.audience.core.audience-test
```

Expected: FAIL — namespace not found.

- [ ] **Step 3: Implement `core/audience.clj`**

Registry atom + 4 functions (`register-audience!`, `get-audience`, `list-audiences`, `clear-registry!`) + `defaudience` macro. Exactly as specified in the spec section "defaudience Macro & Registry".

- [ ] **Step 4: Run tests — verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.audience.core.audience-test
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add libs/audience/src/boundary/audience/core/audience.clj libs/audience/test/boundary/audience/core/audience_test.clj
git commit -m "feat(audience): defaudience macro with atom-backed registry"
```

---

## Task 4: Filter Multimethods + Built-in Types

**Files:**
- Create: `libs/audience/src/boundary/audience/core/filter.clj`
- Create: `libs/audience/test/boundary/audience/core/filter_test.clj`

- [ ] **Step 1: Write failing tests for filter_test.clj**

Test each built-in filter type for both `filter->sql` and `filter->predicate`:

```clojure
(ns boundary.audience.core.filter-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.audience.core.filter :as f]))

(deftest demographics-filter-sql
  (testing ":demographics generates HoneySQL equality clause"
    (let [result (f/filter->sql {:type :demographics :field :plan :op :eq :value "premium"})]
      (is (= [:= :plan "premium"] result))))

  (testing ":demographics :in generates HoneySQL IN clause"
    (let [result (f/filter->sql {:type :demographics :field :role :op :in :value ["admin" "user"]})]
      (is (= [:in :role ["admin" "user"]] result)))))

(deftest location-filter-sql
  (testing ":location generates HoneySQL clause"
    (let [result (f/filter->sql {:type :location :field :country :op :in :value ["NL" "BE"]})]
      (is (= [:in :country ["NL" "BE"]] result)))))

(deftest account-tenure-filter-sql
  (testing ":account-tenure generates date comparison"
    (let [result (f/filter->sql {:type :account-tenure :op :gte :value 90})]
      ;; Should produce something like [:>= [:raw "CURRENT_DATE - created_at"] 90]
      ;; or an equivalent date-diff clause
      (is (some? result)))))

(deftest last-active-filter-sql
  (testing ":last-active :within-days generates date window"
    (let [result (f/filter->sql {:type :last-active :op :within-days :value 30})]
      (is (some? result)))))

(deftest role-filter-sql
  (testing ":role generates equality clause"
    (let [result (f/filter->sql {:type :role :field :role :op :eq :value "admin"})]
      (is (= [:= :role "admin"] result)))))

(deftest behavior-filter-returns-nil-sql
  (testing ":behavior filter->sql returns nil (not DB-evaluable)"
    (is (nil? (f/filter->sql {:type :behavior :op :fn :value (constantly true)})))))

(deftest behavior-filter-predicate
  (testing ":behavior filter->predicate returns the fn from :value"
    (let [pred-fn (fn [user] (> (:login-count user) 5))
          pred (f/filter->predicate {:type :behavior :op :fn :value pred-fn})]
      (is (true? (pred {:login-count 10})))
      (is (false? (pred {:login-count 2}))))))

(deftest feature-usage-filter-sql-returns-nil
  (testing ":feature-usage filter->sql returns nil"
    (is (nil? (f/filter->sql {:type :feature-usage :field :feature-id :op :used-within :value 14})))))

(deftest feature-usage-filter-predicate
  (testing ":feature-usage builds predicate from declarative params"
    (let [pred (f/filter->predicate {:type :feature-usage :field :feature-id :op :used-within :value 14})]
      ;; predicate should check feature usage recency on user map
      (is (fn? pred)))))

(deftest custom-filter-type-registration
  (testing "apps can register custom filter types via defmethod"
    (defmethod f/filter->sql :subscription-tier [filt]
      [:= :subscriptions.tier (:value filt)])
    (let [result (f/filter->sql {:type :subscription-tier :value "gold"})]
      (is (= [:= :subscriptions.tier "gold"] result)))
    ;; Clean up
    (remove-method f/filter->sql :subscription-tier)))

(deftest sql-op-mapping
  (testing "all comparison operators map correctly"
    (is (= [:= :x 1]   (f/filter->sql {:type :demographics :field :x :op :eq  :value 1})))
    (is (= [:<> :x 1]  (f/filter->sql {:type :demographics :field :x :op :neq :value 1})))
    (is (= [:> :x 1]   (f/filter->sql {:type :demographics :field :x :op :gt  :value 1})))
    (is (= [:>= :x 1]  (f/filter->sql {:type :demographics :field :x :op :gte :value 1})))
    (is (= [:< :x 1]   (f/filter->sql {:type :demographics :field :x :op :lt  :value 1})))
    (is (= [:<= :x 1]  (f/filter->sql {:type :demographics :field :x :op :lte :value 1})))
    (is (= [:like :x "%foo%"] (f/filter->sql {:type :demographics :field :x :op :contains :value "foo"})))))
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.audience.core.filter-test
```

- [ ] **Step 3: Implement `core/filter.clj`**

Create helper `sql-op` that maps `:eq` → `:=`, `:neq` → `:<>`, `:gt` → `:>`, etc. Implement `filter->sql` and `filter->predicate` multimethods dispatching on `:type`. Seven built-in types as specified in the filter types table.

For `:account-tenure` — use HoneySQL `[:>= [:raw "EXTRACT(DAY FROM CURRENT_TIMESTAMP - created_at)"] value]` or similar date-diff expression.

For `:last-active` — use `[:>= :last-active-at [:raw "CURRENT_TIMESTAMP - INTERVAL 'N days'"]]`.

For `:feature-usage` `filter->predicate` — build predicate that checks `(get-in user [:feature-usage (:field f)])` recency.

- [ ] **Step 4: Run tests — verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.audience.core.filter-test
```

- [ ] **Step 5: Commit**

```bash
git add libs/audience/src/boundary/audience/core/filter.clj libs/audience/test/boundary/audience/core/filter_test.clj
git commit -m "feat(audience): filter multimethods with 7 built-in types"
```

---

## Task 5: Composition (AND/OR/NOT)

**Files:**
- Create: `libs/audience/src/boundary/audience/core/composition.clj`
- Create: `libs/audience/test/boundary/audience/core/composition_test.clj`

- [ ] **Step 1: Write failing tests**

```clojure
(ns boundary.audience.core.composition-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.audience.core.composition :as comp]))

(deftest and-composition
  (testing "AND intersects user ID sets"
    (is (= #{2 3}
           (comp/compose-results
             {:and [{:user-ids #{1 2 3}} {:user-ids #{2 3 4}}]})))))

(deftest or-composition
  (testing "OR unions user ID sets"
    (is (= #{1 2 3 4}
           (comp/compose-results
             {:or [{:user-ids #{1 2}} {:user-ids #{3 4}}]})))))

(deftest not-composition
  (testing "NOT excludes user IDs from universe"
    ;; NOT requires a universe set to subtract from
    (is (= #{1 4}
           (comp/compose-results
             {:and [{:user-ids #{1 2 3 4}}
                    {:not {:user-ids #{2 3}}}]})))))

(deftest nested-composition
  (testing "nested AND/OR/NOT"
    (is (= #{3}
           (comp/compose-results
             {:and [{:or [{:user-ids #{1 2 3}} {:user-ids #{3 4 5}}]}
                    {:not {:user-ids #{1 2 4 5}}}]})))))

(deftest resolve-refs
  (testing "segment refs resolved via lookup fn"
    (let [lookup (fn [id]
                   (case id
                     :seg-a {:user-ids #{1 2 3}}
                     :seg-b {:user-ids #{2 3 4}}
                     nil))]
      (is (= #{2 3}
             (comp/resolve-and-compose
               {:and [{:ref :seg-a} {:ref :seg-b}]}
               lookup))))))

(deftest circular-ref-detection
  (testing "circular references throw"
    (let [lookup (fn [id]
                   ;; seg-a references seg-b which references seg-a
                   (case id
                     :seg-a {:compose {:and [{:ref :seg-b}]}}
                     :seg-b {:compose {:and [{:ref :seg-a}]}}
                     nil))]
      (is (thrown? Exception
                   (comp/resolve-and-compose
                     {:and [{:ref :seg-a}]}
                     lookup))))))
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.audience.core.composition-test
```

- [ ] **Step 3: Implement `core/composition.clj`**

Functions:
- `compose-results` — takes a composition tree with resolved `{:user-ids #{...}}` leaves. Applies set intersection (AND), union (OR), difference (NOT). Returns `#{user-ids}`.
- `resolve-and-compose` — takes a composition tree with `{:ref :keyword}` leaves and a lookup fn `(fn [id] -> segment-result-or-definition)`. Resolves refs recursively, tracking visited set for circular detection. Throws `ex-info` with `:type :circular-reference` on cycle.

- [ ] **Step 4: Run tests — verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.audience.core.composition-test
```

- [ ] **Step 5: Commit**

```bash
git add libs/audience/src/boundary/audience/core/composition.clj libs/audience/test/boundary/audience/core/composition_test.clj
git commit -m "feat(audience): AND/OR/NOT composition with circular ref detection"
```

---

## Task 6: Compiler (Segment → Execution Plan)

**Files:**
- Create: `libs/audience/src/boundary/audience/core/compiler.clj`
- Create: `libs/audience/test/boundary/audience/core/compiler_test.clj`

- [ ] **Step 1: Write failing tests**

```clojure
(ns boundary.audience.core.compiler-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.audience.core.compiler :as compiler]))

(deftest compile-all-sql-filters
  (testing "all DB-evaluable filters go to :sql-clauses"
    (let [plan (compiler/compile-segment
                 {:filters [{:type :demographics :field :plan :op :eq :value "premium"}
                            {:type :location :field :country :op :in :value ["NL"]}]})]
      (is (= 2 (count (:sql-clauses plan))))
      (is (empty? (:predicates plan))))))

(deftest compile-mixed-filters
  (testing "filters partitioned into sql + predicates"
    (let [pred-fn (fn [_] true)
          plan (compiler/compile-segment
                 {:filters [{:type :demographics :field :plan :op :eq :value "premium"}
                            {:type :behavior :op :fn :value pred-fn}]})]
      (is (= 1 (count (:sql-clauses plan))))
      (is (= 1 (count (:predicates plan)))))))

(deftest compile-no-filters
  (testing "empty filters produce empty plan"
    (let [plan (compiler/compile-segment {:filters []})]
      (is (empty? (:sql-clauses plan)))
      (is (empty? (:predicates plan))))))

(deftest compile-all-predicate-filters
  (testing "all predicate-only filters, no SQL clauses"
    (let [plan (compiler/compile-segment
                 {:filters [{:type :behavior :op :fn :value (fn [_] true)}
                            {:type :behavior :op :fn :value (fn [_] false)}]})]
      (is (empty? (:sql-clauses plan)))
      (is (= 2 (count (:predicates plan)))))))
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.audience.core.compiler-test
```

- [ ] **Step 3: Implement `core/compiler.clj`**

Single public fn `compile-segment` that takes an audience definition (map with `:filters`), iterates filters, calls `filter->sql` on each. If result is non-nil, adds to `:sql-clauses`. Always calls `filter->predicate` for non-SQL filters, adds to `:predicates`. Returns `{:sql-clauses [...] :predicates [...]}`.

- [ ] **Step 4: Run tests — verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.audience.core.compiler-test
```

- [ ] **Step 5: Commit**

```bash
git add libs/audience/src/boundary/audience/core/compiler.clj libs/audience/test/boundary/audience/core/compiler_test.clj
git commit -m "feat(audience): compiler partitions filters into SQL + predicate phases"
```

---

## Task 7: Database Migrations

**Files:**
- Create: `resources/migrations/audience/20260529000000-audience-segments.up.sql`
- Create: `resources/migrations/audience/20260529000000-audience-segments.down.sql`

- [ ] **Step 1: Write up migration**

```sql
CREATE TABLE audience_segments (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  audience_id   VARCHAR(255) NOT NULL UNIQUE,
  label         VARCHAR(255) NOT NULL,
  description   TEXT,
  filters       JSONB NOT NULL,
  composition   JSONB,
  cache_config  JSONB,
  tags          JSONB,
  member_count  INTEGER DEFAULT 0,
  cached_at     TIMESTAMP,
  source        VARCHAR(50) DEFAULT 'dynamic',
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE TABLE audience_memberships (
  audience_id   UUID REFERENCES audience_segments(id) ON DELETE CASCADE,
  user_id       UUID NOT NULL,
  entered_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (audience_id, user_id)
);

--;;

CREATE INDEX idx_audience_memberships_user ON audience_memberships(user_id);
```

Note: use `--;;` separator between statements (Boundary migration convention — see ADR for geo library pattern).

**H2 compatibility note:** H2 2.x supports `JSON` type natively (aliases `JSONB`). The migration uses `JSONB` which works on both PostgreSQL and H2. For reading JSON from result sets, use the `->json` helper pattern from `libs/tenant/src/boundary/tenant/shell/persistence.clj` that handles both `String` (H2) and `PGobject` (PostgreSQL).

- [ ] **Step 2: Write down migration**

```sql
DROP INDEX IF EXISTS idx_audience_memberships_user;

--;;

DROP TABLE IF EXISTS audience_memberships;

--;;

DROP TABLE IF EXISTS audience_segments;
```

- [ ] **Step 3: Verify migration runs**

```bash
clojure -M:migrate up
```

Check that tables exist. H2 2.x supports `JSON` type natively (aliases `JSONB`), so no type changes needed.

- [ ] **Step 4: Commit**

```bash
git add resources/migrations/audience/
git commit -m "feat(audience): database migrations for segments and memberships"
```

---

## Task 8: Persistence Layer

**Files:**
- Create: `libs/audience/src/boundary/audience/shell/persistence.clj`
- Create: `libs/audience/test/boundary/audience/shell/persistence_test.clj`

- [ ] **Step 1: Write failing contract tests**

```clojure
(ns boundary.audience.shell.persistence-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.audience.shell.persistence :as persistence]
            [boundary.audience.ports :as ports]))

;; Setup: create H2 datasource and run migrations in :each fixture.
;; Follow pattern from libs/search/test or libs/workflow/test.

(deftest ^:contract save-and-find-audience
  (testing "save audience then find by keyword id"
    (let [store (persistence/create-audience-store datasource)
          definition {:id          :test-segment
                      :label       "Test Segment"
                      :description "For testing"
                      :filters     [{:type :demographics :field :plan :op :eq :value "premium"}]
                      :tags        [:test]}]
      (ports/save-audience store definition)
      (let [found (ports/find-audience store :test-segment)]
        (is (= :test-segment (:id found)))
        (is (= "Test Segment" (:label found)))
        (is (= 1 (count (:filters found))))))))

(deftest ^:contract list-audiences
  (testing "list returns all saved audiences"
    (let [store (persistence/create-audience-store datasource)]
      (ports/save-audience store {:id :seg-a :label "A" :filters [] :tags [:test]})
      (ports/save-audience store {:id :seg-b :label "B" :filters [] :tags [:test]})
      (let [all (ports/list-audiences store)]
        (is (= 2 (count all)))
        (is (= #{:seg-a :seg-b} (set (map :id all))))))))

(deftest ^:contract delete-audience
  (testing "delete removes audience and cascades to memberships"
    (let [store (persistence/create-audience-store datasource)]
      (ports/save-audience store {:id :del-test :label "Del" :filters []})
      (ports/delete-audience store :del-test)
      (is (nil? (ports/find-audience store :del-test))))))

(deftest ^:contract membership-operations
  (testing "save and query membership records"
    (let [store (persistence/create-audience-store datasource)
          user-id-1 (java.util.UUID/randomUUID)
          user-id-2 (java.util.UUID/randomUUID)]
      (ports/save-audience store {:id :mem-test :label "Mem" :filters []})
      ;; save-memberships! is a persistence-layer fn (not on port)
      (persistence/save-memberships! datasource :mem-test #{user-id-1 user-id-2})
      (let [members (persistence/get-memberships datasource :mem-test)]
        (is (= #{user-id-1 user-id-2} (set members)))))))
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.audience.shell.persistence-test
```

- [ ] **Step 3: Implement persistence.clj**

`create-audience-store` returns a reified `IAudienceRepository`. Uses next.jdbc + HoneySQL. Handles:
- JSONB serialization: use `cheshire.core/generate-string` for write, `cheshire.core/parse-string` for read. Or use `clojure.data.json` — check which the project already uses.
- `audience_id` column stores keyword as string (use `name` / `keyword` conversion).
- Case conversion at DB boundary: kebab-case ↔ snake_case via `boundary.shared.core.utils.case-conversion`.
- Membership table CRUD: `save-memberships!`, `get-memberships`, `clear-memberships!` (for refresh).

- [ ] **Step 4: Run tests — verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.audience.shell.persistence-test
```

- [ ] **Step 5: Commit**

```bash
git add libs/audience/src/boundary/audience/shell/persistence.clj libs/audience/test/boundary/audience/shell/persistence_test.clj
git commit -m "feat(audience): persistence layer with JSONB serialization"
```

---

## Task 9: Cache Layer

**Files:**
- Create: `libs/audience/src/boundary/audience/shell/cache.clj`
- Create: `libs/audience/test/boundary/audience/shell/cache_test.clj`

- [ ] **Step 1: Write failing tests**

```clojure
(ns boundary.audience.shell.cache-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.audience.shell.cache :as cache]
            [boundary.audience.ports :as ports]))

;; Setup: H2 datasource with migrations applied. See persistence_test for fixture pattern.

(deftest ^:integration put-and-get-cached
  (testing "put-cached stores result, get-cached retrieves it"
    (let [c (cache/create-audience-cache datasource nil)
          result {:user-ids #{(java.util.UUID/randomUUID)} :count 1
                  :cached? false :evaluated-at (java.time.Instant/now)}]
      (ports/put-cached c :test-seg result 60)
      (let [cached (ports/get-cached c :test-seg)]
        (is (some? cached))
        (is (= 1 (:count cached)))
        (is (true? (:cached? cached)))))))

(deftest ^:integration get-cached-returns-nil-for-unknown
  (testing "get-cached returns nil for non-existent segment"
    (let [c (cache/create-audience-cache datasource nil)]
      (is (nil? (ports/get-cached c :nonexistent))))))

(deftest ^:integration invalidate-clears-single
  (testing "invalidate removes cached data for one segment"
    (let [c (cache/create-audience-cache datasource nil)
          result {:user-ids #{} :count 0 :cached? false
                  :evaluated-at (java.time.Instant/now)}]
      (ports/put-cached c :seg-inv result 60)
      (ports/invalidate c :seg-inv)
      (is (nil? (ports/get-cached c :seg-inv))))))

(deftest ^:integration invalidate-all-clears-everything
  (testing "invalidate-all removes all cached data"
    (let [c (cache/create-audience-cache datasource nil)
          result {:user-ids #{} :count 0 :cached? false
                  :evaluated-at (java.time.Instant/now)}]
      (ports/put-cached c :seg-x result 60)
      (ports/put-cached c :seg-y result 60)
      (ports/invalidate-all c)
      (is (nil? (ports/get-cached c :seg-x)))
      (is (nil? (ports/get-cached c :seg-y))))))

(deftest ^:integration ttl-freshness-check
  (testing "stale cache (beyond TTL) returns nil"
    ;; Use TTL of 0 minutes to force immediate staleness
    (let [c (cache/create-audience-cache datasource nil)
          result {:user-ids #{} :count 0 :cached? false
                  :evaluated-at (java.time.Instant/now)}]
      (ports/put-cached c :stale-seg result 0)
      ;; With 0-minute TTL, cache should be immediately stale
      (is (nil? (ports/get-cached c :stale-seg))))))
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.audience.shell.cache-test
```

- [ ] **Step 3: Implement cache.clj**

`create-audience-cache` takes datasource + optional boundary-cache instance. Returns reified `IAudienceCache`.

L1: query `audience_segments.cached_at` to check freshness against TTL. Load user IDs from `audience_memberships` if fresh. On `put-cached`: write memberships to `audience_memberships`, update `cached_at` and `member_count` on `audience_segments`.
L2: if boundary-cache instance provided, use `ICache` protocol for hot cache. Key: `(str "audience:" (name audience-id))`. On `get-cached`: check L2 first, then L1. On `invalidate`: clear both layers.

JSON serialization pattern for H2/PostgreSQL compatibility (same as tenant lib):
```clojure
(defn- ->json [value]
  (cond
    (nil? value) nil
    (map? value) value
    (string? value) (cheshire.core/parse-string value true)
    (= "org.postgresql.util.PGobject" (.getName (class value)))
      (some-> (.getValue value) (cheshire.core/parse-string true))
    :else value))
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.audience.shell.cache-test
```

- [ ] **Step 5: Commit**

```bash
git add libs/audience/src/boundary/audience/shell/cache.clj libs/audience/test/boundary/audience/shell/cache_test.clj
git commit -m "feat(audience): two-layer cache with TTL and invalidation"
```

---

## Task 10: Evaluation Service

**Files:**
- Create: `libs/audience/src/boundary/audience/shell/service.clj`
- Create: `libs/audience/test/boundary/audience/shell/service_test.clj`

- [ ] **Step 1: Write failing integration tests**

```clojure
(ns boundary.audience.shell.service-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.audience.shell.service :as service]
            [boundary.audience.core.audience :as audience]
            [boundary.audience.ports :as ports]))

(use-fixtures :each
  (fn [f]
    (audience/clear-registry!)
    (f)))

;; Mock IUserDataSource
(defn mock-user-data-source [users]
  (reify ports/IUserDataSource
    (query-users-sql [_ _clause]
      ;; Return all user IDs (mock ignores SQL)
      (mapv :id users))
    (load-users [_ user-ids]
      (filterv #(contains? (set user-ids) (:id %)) users))))

(deftest ^:integration resolve-simple-segment
  (testing "resolves segment with demographics filter"
    (audience/defaudience test-seg
      {:id      :test-seg
       :label   "Premium"
       :filters [{:type :demographics :field :plan :op :eq :value "premium"}]})
    ;; ... wire up service with mocked deps, call resolve-audience
    ;; verify returns correct user IDs
    ))

(deftest ^:integration resolve-with-predicate
  (testing "hybrid: SQL narrows, predicate refines"
    ;; define segment with both demographics + behavior filters
    ;; verify predicate phase filters out users that passed SQL phase
    ))

(deftest ^:integration cache-hit-path
  (testing "second call returns cached result"
    ;; resolve once, verify cached? false
    ;; resolve again, verify cached? true
    ))

(deftest ^:integration member-check
  (testing "member? returns boolean for single user"
    ;; resolve segment, then check member? for included/excluded user
    ))
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
clojure -M:test:db/h2 --focus boundary.audience.shell.service-test
```

- [ ] **Step 3: Implement service.clj**

`create-audience-service` takes `{:keys [repository cache user-data-source]}`. Returns reified `IAudienceResolver`.

`resolve-audience` implementation follows the evaluation pipeline from the spec:
1. Check L2 cache → L1 freshness → full eval
2. Load definition from registry (via `audience/get-audience`) or DB (via `ports/find-audience`)
3. Compile via `compiler/compile-segment`
4. If `:compose` present, resolve refs via `composition/resolve-and-compose`
5. Phase 1: `ports/query-users-sql` with compiled SQL clauses
6. Phase 2: `ports/load-users` + apply predicates
7. Cache result
8. Return `SegmentResult` map

`member?` — resolve full segment (cached), check `(contains? user-ids user-id)`.

- [ ] **Step 4: Run tests — verify they pass**

```bash
clojure -M:test:db/h2 --focus boundary.audience.shell.service-test
```

- [ ] **Step 5: Commit**

```bash
git add libs/audience/src/boundary/audience/shell/service.clj libs/audience/test/boundary/audience/shell/service_test.clj
git commit -m "feat(audience): evaluation service with hybrid SQL+predicate pipeline"
```

---

## Task 11: Integrant Wiring

**Files:**
- Create: `libs/audience/src/boundary/audience/shell/module_wiring.clj`

- [ ] **Step 1: Implement module_wiring.clj**

Follow pattern from `libs/search/src/boundary/search/shell/module_wiring.clj`.

Integrant keys:
- `:boundary/audience` — init creates `{:store <IAudienceRepository> :resolver <IAudienceResolver> :cache <IAudienceCache>}`
- `:boundary/audience-routes` — init returns `{:api [...] :web [...]}`

```clojure
(defmethod ig/init-key :boundary/audience
  [_ {:keys [db-ctx cache-service user-data-source]}]
  (log/info "Initializing audience component")
  (let [datasource (:datasource db-ctx)
        store      (persistence/create-audience-store datasource)
        cache      (cache/create-audience-cache datasource cache-service)
        resolver   (service/create-audience-service
                     {:repository       store
                      :cache            cache
                      :user-data-source user-data-source})]
    (when-not user-data-source
      (log/warn "No :user-data-source provided — audience resolution will fail."
                "Wire an IUserDataSource implementation via Integrant config."))
    {:store    store
     :resolver resolver
     :cache    cache}))
```

The application must provide an `IUserDataSource` implementation via Integrant config:

```clojure
;; In application's config.edn:
:boundary/audience
{:db-ctx           #ig/ref :boundary/db-context
 :cache-service    #ig/ref :boundary/cache
 :user-data-source #ig/ref :app/user-data-source}
```

- [ ] **Step 2: Verify compiles**

```bash
clojure -e "(require '[boundary.audience.shell.module-wiring])"
```

- [ ] **Step 3: Commit**

```bash
git add libs/audience/src/boundary/audience/shell/module_wiring.clj
git commit -m "feat(audience): Integrant lifecycle wiring"
```

---

## Task 12: HTTP Routes + Builder Page (Server-Side)

**Files:**
- Create: `libs/audience/src/boundary/audience/shell/http.clj`
- Create: `libs/audience/src/boundary/audience/core/ui.clj`

- [ ] **Step 1: Implement core/ui.clj**

Pure Hiccup components (no I/O):
- `segment-card` — renders a segment as a card (label, filter count, member count, cached-at)
- `filter-badge` — renders a single filter as a compact badge (type icon + field + op + value)
- `segment-list` — renders list of segment-cards
- `builder-layout` — page layout for builder (metadata form + placeholder divs for Replicant widgets)

Follow pattern from `libs/calendar/src/boundary/calendar/core/ui.clj`.

- [ ] **Step 2: Implement shell/http.clj**

Ring handler functions for all endpoints from spec:
- `list-audiences` — GET /web/audiences — renders segment-list via Hiccup
- `builder-page` — GET /web/audiences/builder — renders builder-layout
- `builder-page-edit` — GET /web/audiences/builder/:id — loads existing segment into builder
- `create-audience` — POST /api/audiences — parse EDN body, validate with DynamicAudienceDefinition, save
- `update-audience` — PUT /api/audiences/:id — same
- `delete-audience` — DELETE /api/audiences/:id
- `preview-audience` — POST /api/audiences/preview — compile+evaluate filters, return count + 10 sample users as HTMX fragment
- `evaluate-audience` — POST /api/audiences/:id/evaluate — full eval, update cache
- `list-members` — GET /api/audiences/:id/members — paginated member list

Route registration fn `audience-routes` returns vector of Reitit route defs.

- [ ] **Step 3: Verify compiles**

```bash
clojure -e "(require '[boundary.audience.shell.http] '[boundary.audience.core.ui])"
```

- [ ] **Step 4: Commit**

```bash
git add libs/audience/src/boundary/audience/core/ui.clj libs/audience/src/boundary/audience/shell/http.clj
git commit -m "feat(audience): HTTP routes and Hiccup UI components for builder"
```

---

## Task 13: Admin Entity Config

**Files:**
- Create: `resources/conf/dev/admin/audiences.edn`
- Create: `resources/conf/test/admin/audiences.edn`

- [ ] **Step 1: Write admin entity configs**

Both files identical content — audiences entity config from spec:

```clojure
{:audiences
 {:label       "Audiences"
  :table-name  :audience_segments
  :list-fields [:label :filter-count :member-count :cached-at :created-at]
  :search-fields [:label :description]
  :readonly-fields #{:id :filter-count :member-count :cached-at :created-at :updated-at}
  :fields
  {:label        {:type :string  :label [:t :admin.audiences/field-label]        :filterable true}
   :filter-count {:type :integer :label [:t :admin.audiences/field-filter-count] :filterable false}
   :member-count {:type :integer :label [:t :admin.audiences/field-member-count] :filterable true}
   :cached-at    {:type :instant :label [:t :admin.audiences/field-cached-at]    :filterable true}
   :created-at   {:type :instant :label [:t :admin.audiences/field-created-at]   :filterable true}}
  :edit-redirect-url "/web/audiences/builder/:id"
  :create-redirect-url "/web/audiences/builder"}}
```

Note: use `[:t ...]` markers for i18n support (framework convention).

- [ ] **Step 2: Verify admin loads config**

Start REPL, load admin module, verify audiences entity appears in entity list.

- [ ] **Step 3: Commit**

```bash
git add resources/conf/dev/admin/audiences.edn resources/conf/test/admin/audiences.edn
git commit -m "feat(audience): admin entity config with builder redirect"
```

---

## Task 14: ClojureScript Builder Widgets (Deferred)

**Files:**
- Create: `libs/audience/shadow-cljs.edn`
- Create: `libs/audience/resources/boundary/audience/builder/app.cljs`
- Create: `libs/audience/resources/boundary/audience/builder/filter_panel.cljs`
- Create: `libs/audience/resources/boundary/audience/builder/preview.cljs`

> **Note:** This task introduces ClojureScript + Replicant — the first CLJS in the framework. It is intentionally separate and can be deferred to a follow-up PR if the team prefers to ship the backend+Hiccup UI first. The builder page from Task 12 works without JS via HTMX form fallback.

- [ ] **Step 1: Create `shadow-cljs.edn`**

```edn
{:source-paths ["resources/boundary/audience/builder"]
 :dependencies [[io.github.borkdude/replicant "LATEST"]]
 :builds
 {:builder
  {:target     :browser
   :output-dir "resources/public/js/audience"
   :asset-path "/js/audience"
   :modules    {:main {:init-fn boundary.audience.builder.app/init!}}
   :devtools   {:repl-init-ns boundary.audience.builder.app}}}}
```

- [ ] **Step 2: Implement `app.cljs`**

Entry point. Mounts Replicant widgets into DOM placeholder divs rendered by `core/ui.clj`'s `builder-layout`. Init function discovers mount points by ID (`#audience-filter-panel`, `#audience-composition-builder`, `#audience-preview`).

- [ ] **Step 3: Implement `filter_panel.cljs`**

Replicant component: renders filter cards. Each card has type dropdown, field input, operator select, value input. Drag-and-drop reordering. Add/remove buttons. State stored as EDN vector of filter maps.

On change: serialize state to hidden input `#audience-filters-data`. Dispatch HTMX request to `/api/audiences/preview` with current filter state for live count update.

- [ ] **Step 4: Implement `preview.cljs`**

Replicant component: receives HTMX response with segment size + sample users. Renders count badge, user list, and (for composed segments) Venn diagram SVG.

- [ ] **Step 5: Build and verify**

```bash
cd libs/audience && npx shadow-cljs compile builder
```

Verify `resources/public/js/audience/main.js` exists.

- [ ] **Step 6: Commit**

```bash
git add libs/audience/shadow-cljs.edn libs/audience/resources/boundary/audience/builder/
git commit -m "feat(audience): ClojureScript builder widgets with Replicant"
```

---

## Task 15: AGENTS.md + Final Verification

**Files:**
- Create: `libs/audience/AGENTS.md`

- [ ] **Step 1: Write AGENTS.md**

Follow pattern from `libs/calendar/AGENTS.md`. Sections:
1. Overview (what the library does)
2. Quick Start (defaudience example, resolve-audience example)
3. Filter Types (table of built-ins + how to register custom)
4. Composition (AND/OR/NOT examples)
5. Caching (TTL, refresh modes)
6. Builder UI (routes, HTMX endpoints)
7. Testing (fixture warning, test commands)
8. Common Pitfalls (registry pollution, fn refs in dynamic segments, UUID vs keyword ID)
9. FC/IS Rules (what goes in core vs shell)

- [ ] **Step 2: Write security tests**

Create `libs/audience/test/boundary/audience/shell/security_test.clj`:

```clojure
(ns boundary.audience.shell.security-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.audience.core.filter :as f]))

(deftest ^:security filter-values-parameterized
  (testing "SQL injection via filter :value is parameterized by HoneySQL"
    ;; HoneySQL generates parameterized queries, never interpolates values.
    ;; Verify the output is a HoneySQL vector (not a raw SQL string):
    (let [malicious "'; DROP TABLE users; --"
          result (f/filter->sql {:type :demographics :field :email :op :eq :value malicious})]
      (is (vector? result))
      (is (= malicious (last result)))))) ;; value is a parameter, not interpolated

(deftest ^:security dynamic-segment-rejects-fn-values
  (testing "DynamicAudienceDefinition rejects fn-typed :value"
    ;; Validate via schema — fn refs must not be persistable
    (let [definition {:id :evil :label "Evil" :filters [{:type :behavior :op :fn :value identity}]}]
      (is (not (malli.core/validate boundary.audience.schema/DynamicAudienceDefinition definition))))))
```

- [ ] **Step 3: Run full test suite**

```bash
clojure -M:test:db/h2 :audience
```

Expected: all tests pass (including security tests).

- [ ] **Step 4: Run linter**

```bash
clojure -M:clj-kondo --lint libs/audience/src libs/audience/test
```

Expected: no errors.

- [ ] **Step 5: Run FC/IS check**

```bash
bb check:fcis
```

Expected: audience core/ has no shell/IO imports.

- [ ] **Step 6: Commit**

```bash
git add libs/audience/AGENTS.md libs/audience/test/boundary/audience/shell/security_test.clj
git commit -m "docs(audience): AGENTS.md developer guide + security tests"
```

---

## Task Summary

| Task | Description | Type | Est. Steps |
|------|------------|------|-----------|
| 1 | Library scaffold | Setup | 8 |
| 2 | Schema + Ports | Core | 4 |
| 3 | defaudience macro + registry | Core (TDD) | 5 |
| 4 | Filter multimethods + built-ins | Core (TDD) | 5 |
| 5 | AND/OR/NOT composition | Core (TDD) | 5 |
| 6 | Compiler | Core (TDD) | 5 |
| 7 | Database migrations | DB | 4 |
| 8 | Persistence layer | Shell (TDD) | 5 |
| 9 | Cache layer | Shell (TDD) | 5 |
| 10 | Evaluation service | Shell (TDD) | 5 |
| 11 | Integrant wiring | Shell | 3 |
| 12 | HTTP routes + builder UI (server) | Shell | 4 |
| 13 | Admin entity config | Config | 3 |
| 14 | CLJS builder widgets (deferrable) | UI | 6 |
| 15 | AGENTS.md + final verification | Docs | 5 |

**Dependencies:** Tasks 1→2→3, then 4/5/6 can run in parallel. Task 7 blocks 8. Tasks 8/9 block 10. Task 10 blocks 11/12. Task 14 is independent (deferrable).
