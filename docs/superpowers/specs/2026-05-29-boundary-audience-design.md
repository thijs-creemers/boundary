# Boundary Audience Segmentation Design

**Date:** 2026-05-29  
**Branch:** main  
**Status:** Draft

## Problem

Targeted communication is 2–5× more effective than broadcast. Currently, every consumer library (boundary-push, boundary-email, boundary-forms) that wants to target a subset of users must implement its own ad-hoc filter logic. This leads to:

1. **Duplicated filtering** — each library re-implements demographics, location, behavior queries
2. **Inconsistent segments** — an "active premium user" defined in email campaigns may differ from the same concept in push notifications
3. **No reusability** — segments can't be shared across libraries or composed
4. **No visibility** — admins can't see or manage audience definitions without developer intervention

## Solution

`boundary-audience` — a declarative, reusable audience engine that provides:

- `defaudience` macro for code-defined segments (compile-time registration)
- Dynamic segment definitions via admin builder UI (runtime, persisted to DB)
- Hybrid evaluation: SQL narrows candidates, Clojure predicates refine
- Two-layer caching: DB membership table + in-memory/Redis hot cache
- Composable segments with AND / OR / NOT logic
- Dedicated builder UI with drag-and-drop filters and live preview
- Open filter type system via multimethods

## Core Domain Model

### Schemas

```clojure
(def FilterDef
  [:map
   [:type :keyword]                              ;; multimethod dispatch key
   [:field {:optional true} :keyword]             ;; DB column or user attribute
   [:op :keyword]                                 ;; :eq, :neq, :gt, :gte, :lt, :lte, :in, :contains, :fn
   [:value :any]])                                ;; literal value or fn symbol (code segments only)

;; SegmentRef — reference another segment by keyword :id
(def SegmentRef
  [:map [:ref :keyword]])

;; Composable — either a ref to another segment, or a nested composition
(def Composable
  [:schema
   {:registry
    {::composable
     [:or
      SegmentRef
      [:map [:and [:vector [:ref ::composable]]]]
      [:map [:or  [:vector [:ref ::composable]]]]
      [:map [:not [:ref ::composable]]]]}}
   ::composable])

(def CacheConfig
  [:map
   [:ttl-minutes {:optional true} :int]
   [:refresh-schedule {:optional true} :string]])  ;; cron expression

(def AudienceDefinition
  [:map
   [:id :keyword]
   [:label :string]
   [:description {:optional true} :string]
   [:filters [:vector FilterDef]]
   [:compose {:optional true} Composable]
   [:cache {:optional true} CacheConfig]
   [:tags {:optional true} [:vector :keyword]]])

;; SegmentResult — returned by resolve-audience
(def SegmentResult
  [:map
   [:user-ids [:set :uuid]]
   [:count :int]
   [:cached? :boolean]
   [:evaluated-at inst?]])

;; MembershipRecord — row in audience_memberships table
(def MembershipRecord
  [:map
   [:audience-id :uuid]     ;; FK to audience_segments.id (internal UUID)
   [:user-id :uuid]
   [:entered-at inst?]])
```

### Identifier Semantics

Two identifiers exist for each segment:

- **`:id` (keyword)** — the logical identifier used in code (`defaudience`), composition references (`{:ref :active-premium}`), and consumer API calls (`(resolve-audience resolver :active-premium)`). Stored as `audience_id VARCHAR` in DB.
- **`id` (UUID)** — the database primary key, used for FK joins (e.g. `audience_memberships.audience_id` → `audience_segments.id`). Internal to persistence layer, never exposed to consumers.

Lookups always use keyword `:id`. UUID is an implementation detail of the persistence layer.

### `defaudience` Macro & Registry

```clojure
;; --- Registry (atom-backed, in-process) ---

(defonce ^:private registry (atom {}))

(defn register-audience!
  "Register an audience definition by its :id keyword."
  [definition]
  (let [id (:id definition)]
    (swap! registry assoc id definition)
    definition))

(defn get-audience
  "Look up audience definition by keyword :id. Returns nil if not found."
  [id]
  (get @registry id))

(defn list-audiences
  "Return all registered audience keyword :ids."
  []
  (keys @registry))

(defn clear-registry!
  "Reset registry. Use in test :each fixtures to prevent pollution."
  []
  (reset! registry {}))

;; --- Macro ---

(defmacro defaudience
  "Define and register an audience segment.
   The body is a map literal that must satisfy AudienceDefinition schema.
   After expansion, the definition is registered in the in-process registry."
  [sym definition-map]
  `(do
     (def ~sym ~definition-map)
     (register-audience! ~sym)
     ~sym))

;; Usage:
(defaudience active-premium
  {:id          :active-premium
   :label       "Active Premium Users"
   :description "Premium plan, active in last 30 days"
   :filters     [{:type :demographics :field :plan :op :eq :value "premium"}
                 {:type :behavior :op :fn :value active-last-30d?}]
   :cache       {:ttl-minutes 60}})

;; Composition:
(defaudience high-value-eu
  {:id      :high-value-eu
   :label   "High-Value EU Users"
   :compose {:and [{:ref :active-premium}
                   {:ref :eu-located}
                   {:not {:ref :churning}}]}})
```

### Test Fixtures

```clojure
;; Every test namespace using defaudience MUST clear the registry:
(use-fixtures :each
  (fn [f]
    (audience/clear-registry!)
    (f)))
```

### Filter Type Multimethod (Open/Extensible)

```clojure
;; Two-phase evaluation: DB-narrowing + in-memory refinement

(defmulti filter->sql
  "Compile filter to HoneySQL clause. Return nil if not DB-evaluable."
  :type)

(defmulti filter->predicate
  "Compile filter to (fn [user] -> boolean). Always available as fallback."
  :type)

;; Built-in:
(defmethod filter->sql :demographics [f]
  [(sql-op (:op f)) (:field f) (:value f)])

(defmethod filter->sql :behavior [_f]
  nil)  ;; not DB-evaluable

(defmethod filter->predicate :behavior [f]
  (:value f))  ;; :value is a predicate fn
```

### Code-Defined vs Dynamic Segments

**Code-defined** (via `defaudience`): can use `:op :fn` with Clojure function references as `:value`. These are live fn objects in the registry — not serialized.

**Dynamic** (admin-created, persisted to DB): cannot use `:op :fn`. All filters must be data-only (serializable to JSON). Dynamic segments are restricted to DB-evaluable filter types plus declarative predicate types that the library knows how to interpret:

```clojure
;; Dynamic segment filter — declarative, no fn refs:
{:type :feature-usage :field :feature-id :op :used-within :value 14}
;; The :feature-usage multimethod knows how to interpret :used-within declaratively.

;; Code segment filter — can use fn refs:
{:type :behavior :op :fn :value active-last-30d?}
;; Only works in defaudience. Would fail validation if saved to DB.
```

Schema validation enforces this: `AudienceDefinition` used by `defaudience` allows `:any` for `:value`. A stricter `DynamicAudienceDefinition` schema (used by persistence layer) disallows fn-typed values.

### Built-in Filter Types

| Type | DB-evaluable? | Dynamic-safe? | Example |
|------|:---:|:---:|---------|
| `:demographics` | Yes | Yes | `{:field :plan :op :eq :value "premium"}` |
| `:location` | Yes | Yes | `{:field :country :op :in :value ["NL" "BE" "DE"]}` |
| `:account-tenure` | Yes | Yes | `{:op :gte :value 90}` (days since created_at) |
| `:last-active` | Yes | Yes | `{:op :within-days :value 30}` |
| `:role` | Yes | Yes | `{:field :role :op :eq :value "admin"}` |
| `:feature-usage` | No | Yes | `{:field :feature-id :op :used-within :value 14}` |
| `:behavior` | No | **No** | `{:op :fn :value my-predicate-fn}` (code only) |

Apps register custom types:

```clojure
(defmethod filter->sql :subscription-tier [f]
  [:= :subscriptions.tier (:value f)])

(defmethod filter->predicate :subscription-tier [f]
  (fn [user] (= (get-in user [:subscription :tier]) (:value f))))
```

## Architecture

### File Structure

```
libs/audience/
├── deps.edn
├── build.clj
├── AGENTS.md
├── README.md
├── src/boundary/audience/
│   ├── schema.clj                          ; AudienceDefinition, FilterDef, SegmentResult, MembershipRecord
│   ├── ports.clj                           ; IAudienceResolver, IAudienceRepository, IAudienceCache, IUserDataSource
│   ├── core/
│   │   ├── audience.clj                    ; defaudience macro, registry, helpers
│   │   ├── filter.clj                      ; filter->sql, filter->predicate multimethods + built-ins
│   │   ├── composition.clj                 ; AND/OR/NOT logic, segment reference resolution
│   │   ├── compiler.clj                    ; compile segment → {:sql-clauses [...] :predicates [...]}
│   │   └── ui.clj                          ; Hiccup components: segment-card, filter-badge, segment-list
│   └── shell/
│       ├── service.clj                     ; IAudienceResolver impl — orchestrates SQL → filter → cache
│       ├── persistence.clj                 ; IAudienceRepository impl — CRUD for dynamic segments
│       ├── cache.clj                       ; IAudienceCache impl — precomputed membership, TTL, refresh
│       ├── http.clj                        ; Ring routes: builder UI + API endpoints
│       └── module_wiring.clj               ; Integrant keys, init/halt
├── resources/boundary/audience/builder/    ; ClojureScript + Replicant source
│   ├── app.cljs                            ; Builder app entry
│   ├── filter_panel.cljs                   ; Drag-and-drop filter composition
│   └── preview.cljs                        ; Venn diagram + live count
└── test/boundary/audience/
    ├── core/
    │   ├── audience_test.clj               ; ^:unit
    │   ├── filter_test.clj                 ; ^:unit
    │   ├── composition_test.clj            ; ^:unit
    │   └── compiler_test.clj               ; ^:unit
    └── shell/
        ├── service_test.clj                ; ^:integration
        ├── persistence_test.clj            ; ^:contract
        └── cache_test.clj                  ; ^:integration
```

### Dependency Flow (FC/IS)

```
shell/service.clj
  ├── shell/persistence.clj     (DB — IAudienceRepository)
  ├── shell/cache.clj           (cache — IAudienceCache)
  ├── ports.clj                 (IUserDataSource — provided by app/user lib)
  └── core/compiler.clj         (pure)

core/compiler.clj
  ├── core/filter.clj           (pure — multimethods)
  └── core/composition.clj      (pure)

core/audience.clj
  └── schema.clj                (pure)

core/ui.clj
  └── (no deps on shell or ports)

shell/http.clj
  └── shell/service.clj
```

### Key Ports

```clojure
(defprotocol IAudienceResolver
  "Primary consumer interface — used by boundary-push, boundary-email, etc."
  (resolve-audience [this audience-id] [this audience-id opts]
    "Returns {:user-ids #{...} :count n :cached? bool :evaluated-at inst}")
  (member? [this audience-id user-id]
    "Quick membership check for single user"))

(defprotocol IAudienceRepository
  "CRUD for dynamically-defined segments (admin-created, not defaudience)"
  (save-audience [this definition])
  (find-audience [this audience-id])
  (list-audiences [this] [this filters])
  (delete-audience [this audience-id]))

(defprotocol IAudienceCache
  "Precomputed segment membership"
  (get-cached [this audience-id])
  (put-cached [this audience-id result ttl-minutes])
  (invalidate [this audience-id])
  (invalidate-all [this]))

(defprotocol IUserDataSource
  "App provides this — bridges audience engine to user data"
  (query-users-sql [this honeysql-clause]
    "Execute HoneySQL WHERE against user table, return user IDs")
  (load-users [this user-ids]
    "Load full user maps for predicate evaluation"))
```

### Evaluation Pipeline

```
resolve-audience(id)
  │
  ├─ check cache → hit? return cached result
  │
  ├─ load definition (registry or DB)
  │
  ├─ compiler/compile → {:sql-clauses [...] :predicates [...]}
  │
  ├─ resolve composition refs (AND/OR/NOT → recursive resolve)
  │
  ├─ Phase 1: IUserDataSource/query-users-sql → candidate IDs
  │
  ├─ Phase 2: IUserDataSource/load-users(candidates) → user maps
  │            apply predicates → final IDs
  │
  ├─ store in cache (if TTL configured)
  │
  └─ return {:user-ids #{...} :count n :cached? false :evaluated-at (now)}
```

## Builder UI

### Architecture

Two layers working together:

**Server-rendered (Hiccup + HTMX):**
- Page chrome, navigation, segment list/CRUD
- Segment metadata form (label, description, tags, cache config)
- Save / Duplicate / Delete actions
- Segment list view with cards showing name, filter count, cached member count

**ClojureScript + Replicant (interactive widgets):**
- **Filter panel** — drag-and-drop filter composition. Add/remove/reorder filters. Each filter rendered as card with type selector, field picker, operator, value input. Grouped visually by evaluation phase (DB vs predicate).
- **Composition builder** — visual AND/OR/NOT tree. Drag segment references into composition slots. Nest groups.
- **Live preview** — fires HTMX request to `/api/audiences/preview` on filter change (debounced ~500ms). Shows segment size, sample users (first 10), Venn diagram for composed segments, evaluation breakdown.

### Communication Pattern

Replicant widgets manage local state (filter list, composition tree). On save, state serialized to EDN in hidden form field, HTMX submits to server. Preview requests send current filter state as EDN body, server evaluates and returns count + sample via HTMX swap.

### API Endpoints

```clojure
;; Builder UI pages
["GET"  "/web/audiences"              handler/list-audiences]
["GET"  "/web/audiences/builder"      handler/builder-page]
["GET"  "/web/audiences/builder/:id"  handler/builder-page-edit]

;; API (HTMX + JSON)
["POST"   "/api/audiences"              handler/create-audience]
["PUT"    "/api/audiences/:id"          handler/update-audience]
["DELETE" "/api/audiences/:id"          handler/delete-audience]
["POST"   "/api/audiences/preview"      handler/preview-audience]
["POST"   "/api/audiences/:id/evaluate" handler/evaluate-audience]
["GET"    "/api/audiences/:id/members"  handler/list-members]
```

### Admin Integration

Segment entity config for admin's existing list view:

```clojure
;; resources/conf/dev/admin/audiences.edn
{:audiences
 {:label       "Audiences"
  :table-name  :audience_segments
  :list-fields [:label :filter-count :member-count :cached-at :created-at]
  :search-fields [:label :description]
  :readonly-fields #{:id :filter-count :member-count :cached-at :created-at :updated-at}
  :fields
  {:label        {:type :string  :label "Name"        :filterable true}
   :filter-count {:type :integer :label "Filters"     :filterable false}
   :member-count {:type :integer :label "Members"     :filterable true}
   :cached-at    {:type :instant :label "Last Cached"  :filterable true}
   :created-at   {:type :instant :label "Created"      :filterable true}}
  :edit-redirect-url "/web/audiences/builder/:id"
  :create-redirect-url "/web/audiences/builder"}}
```

## ClojureScript Build Tooling

This is the first CLJS dependency in the Boundary Framework. Build uses shadow-cljs:

```edn
;; libs/audience/shadow-cljs.edn
{:source-paths ["resources/boundary/audience/builder"]
 :dependencies [[io.github.borkdude/replicant "0.x.x"]]
 :builds
 {:builder
  {:target     :browser
   :output-dir "resources/public/js/audience"
   :asset-path "/js/audience"
   :modules    {:main {:init-fn boundary.audience.builder.app/init!}}
   :devtools   {:repl-init-ns boundary.audience.builder.app}}}}
```

Compiled JS artifact (`resources/public/js/audience/main.js`) is included in the library JAR. Builder page loads it via `<script>` tag. No CLJS compilation required by consumers — they get pre-built JS.

CI adds a `shadow-cljs compile builder` step to the audience library's build pipeline.

## Database Schema

Migration file: `resources/migrations/audience/20260529000000-audience-segments.up.sql`

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
  source        VARCHAR(50) DEFAULT 'dynamic',  -- 'dynamic' or 'code'
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audience_memberships (
  audience_id   UUID REFERENCES audience_segments(id) ON DELETE CASCADE,
  user_id       UUID NOT NULL,
  entered_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (audience_id, user_id)
);

CREATE INDEX idx_audience_memberships_user ON audience_memberships(user_id);
```

## Caching Strategy

### Two Layers

**Layer 1: Membership table** (`audience_memberships`) — precomputed segment results stored in DB. Survives restarts. Source of truth for "who's in this segment."

**Layer 2: In-memory / Redis** (via `boundary-cache`) — hot cache for `resolve-audience` and `member?` calls.

```
resolve-audience(id)
  ├─ L2 cache hit? → return (fastest)
  ├─ L1 membership table fresh? (cached_at + ttl > now) → load from DB, populate L2
  └─ Stale/missing → full evaluation pipeline → write L1 + L2
```

### Refresh Modes

- **On-demand** — stale cache triggers re-evaluation on next `resolve-audience` call
- **Scheduled** — background job (via `boundary-jobs`) runs cron from `:refresh-schedule`
- **Manual** — admin "Refresh" button or API call

## Consumer Integration

Consumers depend on `IAudienceResolver` — injected via Integrant.

```clojure
;; In boundary-email:
(defn send-campaign [email-service audience-resolver campaign]
  (let [{:keys [user-ids]} (audience/resolve-audience audience-resolver
                                                       (:audience-id campaign))]
    ...))

;; Quick membership check (feature gating):
(when (audience/member? audience-resolver :beta-testers current-user-id)
  (render-beta-feature))
```

## Testing Strategy

- **Unit** (`^:unit`): macro/registry, filter SQL generation, predicate compilation, AND/OR/NOT composition, compiler execution plans
- **Integration** (`^:integration`): full evaluation pipeline with mock IUserDataSource, cache hit/miss/TTL paths
- **Contract** (`^:contract`): persistence CRUD against H2, JSONB round-trips, membership table operations
- **Security** (`^:security`): filter value sanitization, auth on preview/builder endpoints

## Consequences

### Benefits

1. Single source of truth for audience definitions across all consumer libraries
2. Hybrid evaluation scales to large user bases (SQL narrows, predicates refine)
3. Open filter type system — apps extend without forking
4. Two-layer caching keeps hot paths fast, scheduled refresh keeps segments warm
5. Builder UI gives admins self-service segment management
6. Composable AND/OR/NOT enables complex targeting without code changes
7. Consistent with framework patterns (defaudience ≈ defreport/defevent)

### Drawbacks

1. ClojureScript + Replicant adds build complexity (first CLJS in the framework)
2. Membership table grows with user count × segment count — needs periodic cleanup
3. Predicate-based filters (`:behavior`, `:feature-usage`) require loading user data into memory
4. Composition with NOT requires evaluating the negated segment fully before set-difference
