# Boundary Framework: Killer Features Roadmap

**Status**: Planning Phase  
**Created**: 2026-01-05  
**Owner**: Boundary Framework Team  
**Purpose**: Prioritize high-impact features that differentiate Boundary and accelerate enterprise adoption

---

## Executive Summary

After completing Phase 4.5 (Full-Text Search), Boundary Framework has achieved production readiness with:
- ✅ Solid architectural foundation (FC/IS pattern)
- ✅ Enterprise security (MFA, JWT, RBAC)
- ✅ Background jobs, caching, search
- ✅ API versioning & pagination
- ✅ 765 tests passing, 100% coverage

**Next Goal**: Add killer features that make Boundary irresistible to enterprise teams and differentiate from Django, Rails, Spring Boot.

---

## Priority Order

| # | Feature | Impact | Uniqueness | Enterprise | Implementation | Total |
|---|---------|--------|------------|------------|----------------|-------|
| **1** | Visual Admin Dashboard | 🔥🔥🔥🔥🔥 | 🔥🔥🔥🔥🔥 | 🔥🔥🔥🔥 | 🔥🔥🔥🔥 | **18/20** |
| **2** | AI-Powered Developer Experience | 🔥🔥🔥🔥🔥 | 🔥🔥🔥🔥🔥 | 🔥🔥🔥🔥 | 🔥🔥🔥🔥 | **18/20** |
| **3** | Independent Module Deployments | 🔥🔥🔥🔥🔥 | 🔥🔥🔥🔥🔥 | 🔥🔥🔥🔥🔥 | 🔥🔥🔥 | **18/20** |
| **4** | Real-Time Collaboration | 🔥🔥🔥🔥 | 🔥🔥🔥🔥 | 🔥🔥🔥 | 🔥🔥🔥 | **14/20** |
| **5** | Multi-Tenancy Framework | 🔥🔥🔥🔥🔥 | 🔥🔥🔥 | 🔥🔥🔥🔥🔥 | 🔥🔥 | **15/20** |
| 6 | File Upload & Storage | 🔥🔥🔥 | 🔥 | 🔥🔥 | 🔥🔥🔥🔥 | **10/20** |
| 7 | GraphQL Support | 🔥🔥🔥 | 🔥🔥 | 🔥🔥 | 🔥🔥🔥 | **10/20** |
| 8 | Automated API Testing | 🔥🔥🔥 | 🔥🔥🔥 | 🔥🔥 | 🔥🔥 | **10/20** |

**Recommendation**: Start with **Visual Admin Dashboard** (#1) for immediate "wow factor", then **AI-Powered DX** (#2) to revolutionize development speed, followed by **Independent Module Deployments** (#3) to showcase unique architecture.

---

## Feature #1: Visual Admin Dashboard ⭐ TOP PRIORITY

### Why This Is THE Killer Feature

**Market Reality:**
- **Django's killer feature**: Django Admin (beloved, industry-standard)
- **Rails's killer feature**: ActiveAdmin/Administrate (20K+ stars)
- **Spring Boot**: No built-in admin (requires third-party solutions)
- **Phoenix**: No built-in admin (manual development required)
- **Clojure ecosystem**: Kit, Luminus have no admin UI solutions

**Value Proposition:**
- 80% reduction in admin panel development time
- Every SaaS company needs admin panels
- Non-technical stakeholders can interact with system
- Immediate "wow factor" for new users

### Goals

1. **Auto-generate CRUD UI from database schema**
   - Browse, search, filter any entity
   - Inline editing with validation
   - Relationship navigation
   - Custom actions framework

2. **Enterprise Features**
   - Role-based access control (RBAC)
   - Audit log viewer
   - Bulk operations
   - Dashboard widgets

3. **Developer Experience**
   - Zero-config for basic CRUD
   - Customizable widgets
   - Theme/branding support
   - Mobile-responsive

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP Layer (Shell)                       │
│  • Route handling (/admin/*)                                │
│  • Authentication/authorization                             │
│  Files: boundary/admin/shell/http.clj                      │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                  Service Layer (Shell)                      │
│  • CRUD orchestration                                       │
│  • Permission checking                                      │
│  • Action execution                                         │
│  Files: boundary/admin/shell/service.clj                   │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                   Functional Core (Pure)                    │
│  • Schema introspection                                     │
│  • UI generation (Hiccup)                                   │
│  • Permission logic                                         │
│  Files: boundary/admin/core/schema_introspection.clj       │
│         boundary/admin/core/ui.clj                         │
│         boundary/admin/core/permissions.clj                │
└─────────────────────────────────────────────────────────────┘
```

### Deliverables

**Module Structure:**
```
src/boundary/admin/
├── core/
│   ├── schema_introspection.clj  # Read PostgreSQL schema (400 lines)
│   ├── ui.clj                    # Hiccup components (800 lines)
│   ├── permissions.clj           # RBAC logic (300 lines)
│   └── actions.clj               # Custom action framework (200 lines)
├── shell/
│   ├── service.clj               # CRUD orchestration (500 lines)
│   ├── http.clj                  # Routes (600 lines)
│   └── adapters/
│       └── postgres.clj          # Schema reader (200 lines)
├── ports.clj                     # IAdminService, ISchemaProvider (150 lines)
└── schema.clj                    # Malli schemas (150 lines)

test/boundary/admin/
├── core/
│   ├── schema_introspection_test.clj
│   ├── ui_test.clj
│   └── permissions_test.clj
└── shell/
    ├── service_test.clj
    └── http_test.clj
```

**Total Code**: ~3,000 lines production + ~1,000 lines tests

### Milestones

**Week 1: Foundation** (Jan 6-12, 2026)
- [ ] Create `boundary/admin/` module structure
- [ ] Database schema introspection (PostgreSQL)
- [ ] Core UI components (table, form, filter) in Hiccup
- [ ] Basic CRUD operations (list, view, create, update, delete)
- [ ] Auto-generated navigation menu
- [ ] Integration with existing user authentication

**Deliverables**:
- Working admin UI at `/admin`
- Can browse/edit User entity
- Unit tests for schema introspection
- Basic styling (Pico CSS)

**Week 2: Rich Features** (Jan 13-19, 2026)
- [ ] Inline editing with HTMX
- [ ] Malli validation integration
- [ ] Relationship handling (foreign keys, dropdowns)
- [ ] Search integration (leverage Phase 4.5 full-text search)
- [ ] Custom actions framework
- [ ] Bulk operations (select multiple, delete, export)

**Deliverables**:
- Inline editing working
- Can navigate relationships (e.g., User -> Items)
- Search bar on list views
- Export to CSV functionality
- Integration tests

**Week 3: Enterprise Features** (Jan 20-26, 2026)
- [ ] Role-based permissions (RBAC)
  - Define which roles can access which entities
  - Row-level permissions (edit own, edit all)
- [ ] Audit log viewer
  - Who changed what, when
  - Integration with existing audit log
- [ ] Dashboard widgets (stats, charts, recent activity)
- [ ] File upload widget (images, documents)
- [ ] Custom field renderers (markdown, JSON, dates, colors)

**Deliverables**:
- RBAC working (admin, manager, viewer roles)
- Audit log viewer at `/admin/audit`
- Dashboard at `/admin` (default page)
- File upload for user avatars
- Contract tests for permissions

**Week 4: Polish & Launch** (Jan 27 - Feb 2, 2026)
- [ ] Theme customization (colors, logo, branding)
- [ ] Mobile-responsive design
- [ ] Performance optimization (pagination, lazy loading)
- [ ] Comprehensive documentation
  - Getting started guide
  - Configuration reference
  - Customization guide
- [ ] Video tutorial: "Admin Panel in 10 Minutes"
- [ ] Blog post: "Why Clojure Finally Has Django Admin"
- [ ] Example app: E-commerce admin

**Deliverables**:
- Polished, production-ready admin UI
- Complete documentation (20KB+)
- 15-minute video tutorial
- Example application
- Blog post + marketing materials
- Performance benchmarks

### Configuration Example

```clojure
;; In resources/conf/dev/config.edn
{:boundary/admin
 {:enabled true
  :base-path "/admin"
  :title "My SaaS Admin"
  :logo "/assets/logo.png"
  
  ;; Entities to show in admin
  :entities [{:name :user
              :table "users"
              :display-name "Users"
              :icon "👤"
              :list-fields [:id :email :name :created-at]
              :search-fields [:email :name]
              :form-fields [:email :name :role]
              :actions [:approve :suspend :reset-password]}
             
             {:name :inventory/item
              :table "items"
              :display-name "Inventory Items"
              :icon "📦"
              :list-fields [:sku :name :quantity :price]
              :search-fields [:sku :name :description]
              :relationships [{:type :belongs-to
                               :entity :user
                               :field :owner-id}]}]
  
  ;; Role-based permissions
  :permissions {:admin [:all]  ; Full access
                :manager [:user :read :update
                          :inventory/item :all]
                :viewer [:user :read
                         :inventory/item :read]}
  
  ;; Custom actions
  :custom-actions {:approve {:handler 'my-app.admin.actions/approve-user
                             :label "Approve User"
                             :confirm? true
                             :bulk? true}
                   :export {:handler 'my-app.admin.actions/export-csv
                            :label "Export to CSV"
                            :bulk? true}}}}
```

### Usage Example

```clojure
;; That's it! Admin UI auto-generated at /admin

;; Navigate to http://localhost:3000/admin
;; See auto-generated UI for User and Item entities

;; Add custom action
(ns my-app.admin.actions
  (:require [boundary.admin.ports :as admin-ports]))

(defn approve-user
  "Custom action to approve a user."
  [admin-service user-ids]
  (doseq [user-id user-ids]
    (admin-ports/update-entity admin-service :user user-id {:approved true}))
  {:success true
   :message (str "Approved " (count user-ids) " users")})

;; Customize field renderer
(defn render-status-badge
  "Custom renderer for status field."
  [value]
  [:span {:class (str "badge badge-" (name value))}
   (str/upper-case (name value))])

;; Register custom renderer
(admin/register-renderer :status render-status-badge)
```

### Success Metrics

**Week 1**:
- [ ] Admin UI accessible at `/admin`
- [ ] Can browse/edit at least one entity
- [ ] 20+ unit tests passing

**Week 2**:
- [ ] Inline editing working
- [ ] Relationship navigation working
- [ ] Search integration complete
- [ ] 50+ tests passing

**Week 3**:
- [ ] RBAC enforced across all endpoints
- [ ] Audit log viewer functional
- [ ] Dashboard widgets rendering
- [ ] 80+ tests passing

**Week 4**:
- [ ] Production-ready (zero lint errors, 100% tests)
- [ ] Complete documentation published
- [ ] Video tutorial recorded
- [ ] Example app deployed
- [ ] Blog post drafted
- [ ] Performance: List 1000 items < 200ms

### Synergies with Existing Features

| Existing Feature | Integration Point |
|-----------------|-------------------|
| **Phase 4.5 (Search)** | Search bar on every list view |
| **Phase 4.4 (Pagination)** | Paginated tables with RFC 5988 links |
| **Phase 4.3 (MFA)** | Secure admin access with MFA |
| **Phase 4.1 (Jobs)** | Job monitoring dashboard widget |
| **Phase 3 (Scaffolder)** | Auto-register new entities in admin |
| **Phase 2 (Observability)** | Admin actions logged/traced |

### Technical Stack

- **Backend**: Boundary platform, PostgreSQL schema introspection
- **Frontend**: HTMX + Hiccup (no build step!)
- **Styling**: Pico CSS + custom admin theme
- **Components**: Hiccup DSL (pure functions)
- **Architecture**: FC/IS pattern throughout

### Risk Mitigation

**Risk: Too ambitious for 4 weeks**
- Mitigation: Start with minimal viable admin (Week 1), iterate
- Fallback: Defer dashboard widgets to Week 5

**Risk: Performance with large tables**
- Mitigation: Pagination from day 1, lazy loading
- Fallback: Virtual scrolling for 10K+ rows

**Risk: Limited widget library**
- Mitigation: Focus on 10 essential widgets (text, number, date, select, etc.)
- Fallback: Allow custom renderers for complex fields

**Risk: RBAC complexity**
- Mitigation: Start with simple role-based, defer row-level to Week 5
- Fallback: Admin-only mode if RBAC too complex

### Marketing Strategy

**Demo Video Script** (15 minutes):
1. **Introduction** (2 min): What is auto-admin? Why it matters?
2. **From Zero to Admin** (3 min): Fresh app → enable admin → see UI
3. **Customization** (4 min): Add custom actions, permissions, widgets
4. **Real-World Example** (4 min): E-commerce admin (products, orders, customers)
5. **Comparison** (2 min): Boundary vs Django Admin vs manual admin

**Blog Post Outline**: "Why Clojure Finally Has Django Admin"
- Problem: Building admin panels is tedious
- Solution: Auto-generated admin UI from database schema
- Demo: 10-minute walkthrough
- Architecture: How FC/IS makes this possible
- Comparison: Boundary vs Django vs Rails
- Call to Action: Try Boundary today

**Conference Talk Pitch**: "Auto-Generated UIs in Functional Programming"
- Abstract: How FC/IS enables auto-admin without magic
- Target: Clojure/conj, EuroClojure, Strange Loop
- Demo: Live coding an admin panel

---

## Feature #2: AI-Powered Developer Experience ⭐ DX REVOLUTION

### Why This Is a Game-Changer

**The Problem:**
- **Scaffolding is tedious**: Developers spend hours creating boilerplate (modules, entities, routes, tests)
- **Error messages are cryptic**: Stack traces and test failures take 10+ minutes to debug
- **Documentation is scattered**: Developers waste time searching docs, code, and StackOverflow
- **SQL is error-prone**: Writing HoneySQL/SQL queries manually leads to bugs
- **Tests are manual**: Writing comprehensive unit tests requires significant effort

**Boundary's Solution:**
- **AI-assisted scaffolding**: Natural language → complete module in seconds
- **Intelligent error explainer**: Instant root cause analysis + fix suggestions + code references
- **Code & docs Q&A**: Ask questions, get answers from local codebase + docs (offline-first)
- **SQL copilot**: Natural language → HoneySQL + SQL + explanation
- **Test generator**: Auto-generate unit tests from function signatures + examples

**Market Reality:**
- **GitHub Copilot**: Generic code completion (no framework awareness)
- **ChatGPT**: Requires copy-paste, no local context, privacy concerns
- **JetBrains AI**: IDE-specific, not REPL-integrated
- **Cursor/Aider**: External tools, not framework-integrated
- **Kit/Luminus**: No AI assistance
- **Boundary**: AI deeply integrated into framework, CLI, and REPL (privacy-first, offline-capable)

**Value Proposition:**
- **50% faster scaffolding**: Natural language → production-ready module in 2 minutes (vs 10+ minutes manual)
- **30% fewer debug cycles**: Instant error explanations reduce back-and-forth
- **80% test coverage boost**: Auto-generated tests from examples
- **Privacy-first**: Local models (Ollama) by default, no data leaves machine
- **Cost-effective**: < $10/month (remote AI disabled by default)

### Goals

1. **AI-Assisted Scaffolder (MVP)**
   - Natural language → module + entities + routes + schemas + tests
   - Clarifying questions for ambiguity
   - Dry-run mode with visual diffs
   - `boundary ai scaffold "inventory module with items (name, sku:required:unique, quantity:int)"`

2. **Intelligent Error Explainer**
   - Analyze stack traces and test failures
   - Root cause + probable fix + code/doc references
   - `boundary ai explain-error <logfile>` or `boundary ai explain-error --last-test`
   - Context-aware (knows about FC/IS, ports, schemas)

3. **Test Generator**
   - Generate unit tests from function signatures + docstrings + examples
   - `boundary ai tests core/user.clj --for create-user --examples 3`
   - FC/IS-aware (pure core tests, no mocks)

4. **Code & Docs Q&A**
   - Ask questions about framework, patterns, architecture
   - Answers cite local docs + code with line numbers
   - `boundary ai ask "How do HTTP interceptors compose?"`
   - Offline-first (local embeddings)

5. **SQL Copilot**
   - Natural language → HoneySQL + SQL + rationale
   - `boundary ai sql "list active users with MFA enabled, ordered by last login"`
   - Migration assistant: `boundary ai migration add users.last_login:timestamp`

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      CLI/REPL Layer                         │
│  • boundary ai scaffold/explain/tests/ask/sql/migration     │
│  • Streaming UX for long operations                         │
│  Files: boundary/ai/shell/cli.clj                          │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                    Service Layer (Shell)                    │
│  • Provider selection (local/OpenAI/Azure)                  │
│  • Prompt assembly + redaction (secrets, PII)              │
│  • Cost/latency budgets                                     │
│  • Result caching                                           │
│  Files: boundary/ai/shell/service.clj                      │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                   Functional Core (Pure)                    │
│  • Prompt template assembly                                 │
│  • Result validation (Malli schemas)                        │
│  • Token counting, budget checking                          │
│  • PII redaction rules                                      │
│  • Diff generation (safe patches)                           │
│  Files: boundary/ai/core/prompts.clj                       │
│         boundary/ai/core/validation.clj                    │
│         boundary/ai/core/redaction.clj                     │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                 Ports (Protocols/Interfaces)                │
│  • IAIProvider (complete, chat, embed, token-usage)        │
│  • IVectorStore (store, search embeddings)                 │
│  • ICodePatch (generate-diff, validate-patch)              │
│  Files: boundary/ai/ports.clj                              │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│              Adapters (Shell Implementations)               │
│  • Local: Ollama, llama.cpp (default)                      │
│  • Remote: OpenAI, Azure OpenAI (opt-in)                   │
│  • VectorStore: FAISS, SQLite (embeddings)                 │
│  Files: boundary/ai/shell/providers/ollama.clj             │
│         boundary/ai/shell/providers/openai.clj             │
│         boundary/ai/shell/vector_store.clj                 │
└─────────────────────────────────────────────────────────────┘
```

**Key Architecture Principles:**

1. **FC/IS Pattern:**
   - Core: Pure prompt assembly, validation, redaction
   - Shell: I/O with AI providers, file system, network

2. **Privacy & Safety:**
   - Default: Local models (Ollama) + local embeddings
   - Redaction: Automatic removal of secrets, emails, UUIDs before prompts
   - No-network mode: Strictly offline operation
   - Approval required: All code changes shown as diffs, never auto-applied

3. **Cost Control:**
   - Budget limits: Max tokens/cost per command
   - Prompt caching: Avoid redundant API calls
   - Local-first: Zero cost for default usage

4. **Quality Assurance:**
   - Malli validation: Every AI response validated against schemas
   - Golden files: Unit tests for prompt templates
   - Deterministic mode: Temperature 0 for consistent results

### Implementation Plan

**Week 1: Foundation (5 days)**
- [ ] Create `boundary/ai/` module structure
- [ ] Define ports (`IAIProvider`, `IVectorStore`, `ICodePatch`)
- [ ] Implement Ollama adapter (local)
- [ ] Core: Prompt templates for scaffolding
- [ ] Core: PII redaction rules
- [ ] CLI: `boundary ai config --show`

**Week 2: Scaffolder Copilot (5 days)**
- [ ] Core: Scaffolding prompt templates (module + entity + routes)
- [ ] Service: Clarifying questions logic
- [ ] CLI: `boundary ai scaffold <description> --dry-run`
- [ ] Integration: Generate 12 files (same as manual scaffolder)
- [ ] Tests: Golden file tests for scaffolding prompts

**Week 3: Error Explainer + Tests (5 days)**
- [ ] Core: Error analysis prompts (stack traces + test failures)
- [ ] Service: Code/doc reference extraction
- [ ] CLI: `boundary ai explain-error <logfile>` or `--last-test`
- [ ] Core: Test generation prompts (from function signatures)
- [ ] CLI: `boundary ai tests <file> --for <function>`

**Week 4: Q&A + SQL + Polish (5 days)**
- [ ] Vector store: Local embeddings (docs + code)
- [ ] Service: Semantic search over docs/code
- [ ] CLI: `boundary ai ask <question>`
- [ ] Core: SQL generation prompts (natural language → HoneySQL)
- [ ] CLI: `boundary ai sql <description>`
- [ ] Documentation: AI commands guide
- [ ] Demo video: 15-minute AI DX showcase

**Deliverables:**
- [ ] `boundary/ai/` module (~2,500 lines production)
- [ ] `test/boundary/ai/` tests (~800 lines, 100% coverage)
- [ ] CLI commands: `scaffold`, `explain-error`, `tests`, `ask`, `sql`, `migration`
- [ ] Documentation: `docs/AI_DX_GUIDE.md`
- [ ] Demo video: "AI-Powered Boundary: 10x Faster Development"
- [ ] Blog post: "How We Built Privacy-First AI into a Framework"

### CLI Examples

**1. AI-Assisted Scaffolding:**
```bash
$ boundary ai scaffold "inventory module with items: name (required), sku (unique), quantity (int), location"

🤖 Analyzing request...
✓ Module: inventory
✓ Entity: Item
✓ Fields: name (string, required), sku (string, unique), quantity (int), location (string)

❓ Should quantity be required? [Y/n] y
❓ Generate web UI routes? [Y/n] y

🚀 Generating files (dry-run)...
   
📝 Files to create (12):
   ✓ src/boundary/inventory/schema.clj
   ✓ src/boundary/inventory/ports.clj
   ✓ src/boundary/inventory/core/item.clj
   ✓ src/boundary/inventory/core/ui.clj
   ✓ src/boundary/inventory/shell/service.clj
   ✓ src/boundary/inventory/shell/persistence.clj
   ✓ src/boundary/inventory/shell/http.clj
   ✓ src/boundary/inventory/shell/web_handlers.clj
   ✓ test/boundary/inventory/core/item_test.clj
   ✓ test/boundary/inventory/shell/service_test.clj
   ✓ test/boundary/inventory/shell/persistence_test.clj
   ✓ migrations/009_create_items.sql

💾 Write these files? [y/N] y

✅ Module created successfully!
⚡ Run: clojure -M:test:db/h2 --focus-meta :inventory
```

**2. Intelligent Error Explainer:**
```bash
$ clojure -M:test:db/h2 --focus boundary.user.core.user-test
...
FAIL in (create-user-test)
Expected: {:id #uuid "...", :email "test@example.com"}
Actual: nil

$ boundary ai explain-error --last-test

🤖 Analyzing test failure...

📍 Root Cause:
   File: test/boundary/user/core/user_test.clj:45
   Function `create-user` expects `:created-at` in input but test provides `:created_at` (snake_case).
   
   Common issue: Database layer returns snake_case (:created_at) but Clojure code uses kebab-case (:created-at).

💡 Probable Fix:
   Add transformation at persistence boundary:
   
   File: src/boundary/user/shell/persistence.clj:67
   ```clojure
   (defn db->user-entity [db-record]
     (-> db-record
         (clojure.set/rename-keys {:created_at :created-at
                                   :updated_at :updated-at})))
   ```

📚 References:
   - AGENTS.md:1234 (Common Pitfall #1: snake_case vs kebab-case)
   - src/boundary/user/core/user.clj:89 (create-user function)
   - test/boundary/user/core/user_test.clj:45 (failing test)

⏱️ Analysis completed in 1.2s (local model)
```

**3. Test Generator:**
```bash
$ boundary ai tests src/boundary/user/core/user.clj --for create-user --examples 3

🤖 Generating tests for `create-user`...

📝 Generated tests (3):

(deftest create-user-with-valid-data-test
  (testing "creates user with valid email and name"
    (let [user-data {:email "test@example.com" :name "Test User"}
          result (user-core/create-user user-data)]
      (is (some? (:id result)))
      (is (= "test@example.com" (:email result)))
      (is (= "Test User" (:name result))))))

(deftest create-user-with-minimal-data-test
  (testing "creates user with only required fields"
    (let [user-data {:email "min@example.com"}
          result (user-core/create-user user-data)]
      (is (some? (:id result)))
      (is (= "min@example.com" (:email result))))))

(deftest create-user-normalizes-email-test
  (testing "normalizes email to lowercase"
    (let [user-data {:email "TEST@EXAMPLE.COM"}
          result (user-core/create-user user-data)]
      (is (= "test@example.com" (:email result))))))

💾 Add to test/boundary/user/core/user_test.clj? [y/N] y

✅ Tests added successfully!
⚡ Run: clojure -M:test:db/h2 -n boundary.user.core.user-test
```

**4. Code & Docs Q&A:**
```bash
$ boundary ai ask "How do HTTP interceptors compose?"

🤖 Searching local docs and code...

📚 Answer:

HTTP interceptors in Boundary execute in bidirectional enter/leave/error phases:

**Execution Order:**
```
Request Flow:
  enter:  global-1 → global-2 → route-1 → route-2 → handler
  leave:  route-2 → route-1 → global-2 → global-1 → response
```

**Interceptor Shape:**
```clojure
{:name   :my-interceptor
 :enter  (fn [context] ...)  ; Process request (can short-circuit)
 :leave  (fn [context] ...)  ; Process response (reverse order)
 :error  (fn [context] ...)} ; Handle exceptions
```

**Example:**
```clojure
;; In routes
[{:path "/api/admin"
  :methods {:post {:handler 'handlers/create-resource
                   :interceptors ['auth/require-admin
                                  'audit/log-action
                                  'rate-limit/admin-limit]}}}]
```

**References:**
📄 AGENTS.md:1456-1520 (HTTP Interceptors section)
📄 docs/ADR-010-http-interceptor-architecture.adoc (Full specification)
💻 src/boundary/platform/shell/http/interceptors.clj:45 (Implementation)
💻 test/boundary/platform/shell/http/interceptors_test.clj:78 (Examples)

⏱️ Answer generated in 0.8s (local embeddings)
```

**5. SQL Copilot:**
```bash
$ boundary ai sql "list all active users with MFA enabled, ordered by last login descending"

🤖 Generating SQL...

📊 HoneySQL:
```clojure
{:select [:id :email :name :last-login]
 :from [:users]
 :where [:and
         [:= :active true]
         [:= :mfa-enabled true]]
 :order-by [[:last-login :desc]]}
```

📊 SQL:
```sql
SELECT id, email, name, last_login
FROM users
WHERE active = TRUE
  AND mfa_enabled = TRUE
ORDER BY last_login DESC;
```

💡 Explanation:
   - Filters for active users (active = TRUE)
   - Filters for MFA-enabled users (mfa_enabled = TRUE)
   - Orders by most recent login first (DESC)

⏱️ Generated in 0.5s (local model)

💾 Copy to clipboard? [y/N] y
✅ Copied to clipboard!
```

### Configuration

**Default Configuration (Local-Only, Privacy-First):**
```clojure
;; resources/conf/dev/config.edn
{:boundary/ai
 {:provider :ollama                    ; Local model (default)
  :model "codellama:13b"              ; Code-optimized model
  :embedding-model "all-minilm:l6"    ; Lightweight embeddings
  :max-tokens 4096                    ; Per-request limit
  :temperature 0.0                    ; Deterministic
  :offline-only false                 ; Allow local network (Ollama)
  :redact-pii true                    ; Auto-redact secrets, emails
  :cost-budget-monthly 0.0            ; $0 (local only)
  :cache-ttl-minutes 1440             ; 24 hours
  :vector-store :sqlite               ; Local embeddings
  :require-approval true}}            ; Confirm before file writes
```

**Optional: Remote Providers (Opt-In):**
```bash
# Enable OpenAI (opt-in)
export BOUNDARY_AI_PROVIDER=openai
export OPENAI_API_KEY=sk-...
export BOUNDARY_AI_COST_BUDGET_MONTHLY=10.00

# Enable Azure OpenAI
export BOUNDARY_AI_PROVIDER=azure
export AZURE_OPENAI_ENDPOINT=https://...
export AZURE_OPENAI_KEY=...
```

**CLI Configuration:**
```bash
$ boundary ai config --show
Provider: ollama (local)
Model: codellama:13b
Embedding Model: all-minilm:l6
Offline Only: false (allows local network)
Redact PII: enabled
Cost Budget: $0.00/month (local only)
Cache TTL: 24 hours
Vector Store: sqlite (local)
Require Approval: enabled

$ boundary ai config --set provider openai
⚠️  Warning: This will send code to OpenAI's API.
   Set OPENAI_API_KEY and BOUNDARY_AI_COST_BUDGET_MONTHLY.
   Continue? [y/N]
```

### Success Metrics

**Developer Productivity:**
- [ ] 50% reduction in scaffolding time (10 min → 2 min)
- [ ] 30% reduction in debug cycles (test failures resolved faster)
- [ ] 80% increase in test coverage (auto-generated tests)
- [ ] 70%+ adoption rate (active developers use `boundary ai` commands)

**Quality & Safety:**
- [ ] Zero secrets leaked (100% redaction success)
- [ ] 95%+ valid AI outputs (Malli validation catches errors)
- [ ] Zero unauthorized file writes (approval required)

**Cost & Performance:**
- [ ] < $10/month average cost (local-first strategy)
- [ ] < 2s response time (scaffolding, Q&A)
- [ ] < 5s response time (error analysis, test generation)

### Marketing Strategy

**Positioning:**
> "Boundary: The first web framework with AI deeply integrated into the developer experience—privacy-first, offline-capable, and 10x faster."

**Taglines:**
- "AI-powered scaffolding: Natural language → production-ready module in 2 minutes"
- "Intelligent error explanations: No more 10-minute debugging sessions"
- "Privacy-first AI: Local models, zero data leaves your machine"
- "The framework that learns your codebase"

**Demo Video** (15 minutes):
1. **Setup** (2 min): Install Boundary, configure Ollama
2. **Scaffold with AI** (3 min): Natural language → complete module
3. **Debug with AI** (3 min): Test failure → instant explanation + fix
4. **Generate Tests** (2 min): Function → 3 unit tests in seconds
5. **Ask Questions** (2 min): "How do interceptors work?" → instant answer with code refs
6. **SQL Copilot** (2 min): Natural language → HoneySQL + SQL
7. **Privacy & Config** (1 min): Local-first, offline-capable

**Blog Posts:**
1. "How We Built Privacy-First AI into a Web Framework"
2. "AI-Powered Scaffolding: From Idea to Production in 2 Minutes"
3. "Why We Chose Local Models (Ollama) Over OpenAI"
4. "The Future of Framework DX: AI Assistants Built-In"

**Conference Talks:**
- **Clojure/conj**: "AI-Powered Developer Experience in Functional Programming"
- **Strange Loop**: "Building Privacy-First AI Tools for Developers"
- **EuroClojure**: "How AI Accelerates FC/IS Development"

### Competitive Analysis

| Framework | AI Scaffolding | Error Explainer | Test Gen | Q&A | Privacy-First | Framework-Integrated |
|-----------|---------------|-----------------|----------|-----|---------------|---------------------|
| **Boundary** | ✅ Natural Lang | ✅ Built-in | ✅ Built-in | ✅ Local | ✅ Ollama | ✅ CLI + REPL |
| Django | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Rails | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Spring Boot | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Phoenix | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Kit | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| GitHub Copilot | ~⚠️ Generic | ❌ | ~⚠️ Generic | ❌ | ❌ Remote | ~⚠️ IDE only |
| Cursor/Aider | ~⚠️ Generic | ~⚠️ Generic | ~⚠️ Generic | ~⚠️ | ⚠️ Optional | ❌ External |

**Legend:** ✅ Built-in | ~⚠️ Generic/Partial | ❌ Not available

**Boundary's Unique Advantages:**
1. **Framework-Integrated**: AI knows about FC/IS, ports, schemas, interceptors
2. **Privacy-First**: Local models (Ollama) by default, no data leaves machine
3. **Offline-Capable**: Works without internet (local embeddings)
4. **Cost-Effective**: $0/month default (local-first strategy)
5. **Clojure-Aware**: Understands REPL workflow, parentheses, immutability
6. **Context-Aware**: Knows your codebase (local embeddings of docs + code)

---

## Feature #3: Independent Module Deployments ⭐ UNIQUE DIFFERENTIATOR

### Why This Is a Game-Changer

**The Problem:**
- Teams want **monolith simplicity** during development (fast, simple, easy to debug)
- Teams need **microservice flexibility** for production scale (independent scaling, deployment, teams)
- **Current solutions require rewriting code** to split monolith into microservices
- Result: **Premature optimization** (microservices too early) or **scalability pain** (can't split when needed)

**Boundary's Solution:**
- **Same code runs standalone OR composed** in monolith (zero rewrites)
- **Start simple** (monolith), **scale selectively** (extract hot modules)
- **Truly unique**: No other framework offers protocol-driven dual-mode modules

**Market Reality:**
- **Django**: Apps can't run standalone (requires rewrites to extract)
- **Rails**: Engines require significant refactoring to split
- **Spring Boot**: Can modularize, but extracting requires code changes
- **Phoenix**: Contexts are organizational, not deployment boundaries
- **Kit/Luminus**: Modules are templates (one-time generation, not runtime)
- **Boundary**: Modules are **deployment units** from day one (dual-mode runtime)

**Value Proposition:**
- **Zero rewrites**: Same module code runs in monolith OR as independent service
- **Flexible deployment**: Mix monolith + standalone modules (hybrid topology)
- **Risk reduction**: Test in monolith, deploy critical paths independently
- **Team scaling**: Multiple teams can own and deploy independent modules

### Goals

1. **Dual-Mode Runtime**
   - **Standalone Mode**: Module runs as independent microservice
     - Own HTTP server, CLI, database connection
     - Own observability (logs, metrics, errors)
     - Minimal bootstrap: `java -jar boundary-user.jar`
   - **Composed Mode**: Same module in monolith (via Integrant)
     - In-process communication (function calls)
     - Shared resources (database, cache)
     - Single deployment artifact
   - **Toggle via configuration**, not code changes

2. **Inter-Module Communication**
   - **Synchronous**: REST over HTTP with interceptors (auth, rate-limit, versioning)
   - **Asynchronous**: Event bus with durable subscriptions (Kafka/NATS/Redis pub/sub)
   - **Backward Compatibility**: Versioned APIs, deprecation policy per module

3. **Data Ownership & Isolation**
   - Each module owns its database schema (strict boundaries)
   - Shared-data access via ports + pagination/search contracts
   - Multi-tenancy: Tenant-scoped contexts per module

4. **Distribution & Packaging**
   - **Module-level artifacts**: JAR/Docker images (e.g., `boundary-user:1.2.0`)
   - **Semantic versioning** per module
   - **Compatibility matrix** for composed deployments
   - **Registry support**: Internal registry or Clojars

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│              Deployment Mode 1: Monolith (Default)          │
│  • Single process, all modules loaded                       │
│  • Integrant wires modules together                         │
│  • In-process communication (function calls)                │
│  • Simplest development/deployment                          │
│  • Example: Start-up, dev environment                       │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│         Deployment Mode 2: Independent Micro-Mods            │
│  • Each module runs as separate service                     │
│  • HTTP/gRPC for inter-module communication                 │
│  • Event bus for async integration                          │
│  • Scale modules independently                              │
│  • Example: High-scale production                           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│          Deployment Mode 3: Hybrid (Best of Both)           │
│  • Critical modules independent (user, payments)            │
│  • Low-traffic modules composed (admin, reporting)          │
│  • Flexible topology based on load patterns                 │
│  • Example: Practical production setup                      │
└─────────────────────────────────────────────────────────────┘
```

**Module Runtime Architecture:**

```
src/boundary/user/
├── core/              # Pure business logic (SHARED - same in both modes)
│   ├── user.clj
│   ├── session.clj
│   └── mfa.clj
├── shell/             # I/O adapters (SHARED - same in both modes)
│   ├── service.clj
│   ├── persistence.clj
│   └── http.clj
├── ports.clj          # Protocols (SHARED)
└── runtime.clj        # NEW: Standalone entrypoint

;; runtime.clj - Standalone mode configuration
(ns boundary.user.runtime
  (:require [integrant.core :as ig]
            [boundary.user.shell.module-wiring :as wiring])
  (:gen-class))

(defn standalone-config
  "Configuration for standalone module deployment."
  []
  {:boundary/db-context {...}              ; Own DB connection
   :boundary/user-repository {...}         ; Own persistence
   :boundary/user-service {...}            ; Own service
   :boundary/user-http {:port 3001}        ; Own HTTP server (port 3001)
   :boundary/observability {...}})         ; Own logging/metrics

(defn -main [& args]
  (println "Starting User module in standalone mode on port 3001...")
  (let [system (ig/init (standalone-config))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(ig/halt! system)))))
```

**Inter-Module Communication:**

```clojure
;; Port-based HTTP client for inter-module calls
(defprotocol IModuleClient
  "Client for calling other modules over HTTP."
  (call-module [this module-name operation params]
    "Call another module's operation via HTTP.
     
     Examples:
       (call-module client :user :get-user {:id 123})
       (call-module client :inventory :reserve-items {:items [...]})"))

;; Event bus for async integration
(defprotocol IEventBus
  "Event bus for inter-module async communication."
  (publish-event [this topic event]
    "Publish event to topic.")
  (subscribe [this topic handler]
    "Subscribe to topic with handler."))

;; Usage in service layer (works in BOTH modes!)
(defn create-order-service
  [order-repo user-client inventory-client event-bus]
  (reify IOrderService
    (create-order [this order-data]
      ;; Verify user exists (inter-module call)
      ;; In monolith: Function call
      ;; In standalone: HTTP call to user service
      (let [user (call-module user-client :user :get-user 
                              {:id (:user-id order-data)})]
        (when-not user
          (throw (ex-info "User not found" {:user-id (:user-id order-data)})))
        
        ;; Reserve inventory (inter-module call)
        (call-module inventory-client :inventory :reserve-items 
                     {:items (:items order-data)})
        
        ;; Create order
        (let [order (create-order order-repo order-data)]
          ;; Publish event for other modules (async)
          (publish-event event-bus "order.created" {:order-id (:id order)})
          order)))))
```

### Deliverables

**Module Structure:**
```
src/boundary/user/
├── runtime.clj                    # NEW: Standalone entrypoint (200 lines)
├── shell/
│   ├── module_wiring.clj          # Integrant wiring (existing)
│   ├── http_client.clj            # NEW: HTTP client for inter-module (300 lines)
│   └── event_publisher.clj        # NEW: Event publishing (200 lines)

src/boundary/platform/
├── core/
│   └── module_registry.clj        # NEW: Module discovery (300 lines)
├── shell/
│   ├── api_gateway.clj            # NEW: API gateway (500 lines)
│   ├── event_bus.clj              # NEW: Event bus (400 lines)
│   └── adapters/
│       ├── kafka.clj              # Kafka event bus (300 lines)
│       ├── redis_pubsub.clj       # Redis pub/sub (250 lines)
│       └── nats.clj               # NATS adapter (300 lines)

build/
├── module_builder.clj             # NEW: Build module JARs (400 lines)
└── compatibility_matrix.clj       # NEW: Version compatibility (200 lines)

docker/
├── Dockerfile.user                # NEW: User module Dockerfile
├── Dockerfile.inventory           # NEW: Inventory module Dockerfile
└── docker-compose-*.yml           # NEW: Topology templates
```

**Total Code**: ~2,750 lines production + ~800 lines tests

### Milestones

**Week 1: Module Runtime Infrastructure** (TBD)
- [ ] Create module runtime specification
- [ ] Implement `boundary.user.runtime` (standalone mode)
- [ ] Implement `IModuleClient` port + in-process adapter (monolith mode)
- [ ] Implement HTTP client adapter (standalone mode)
- [ ] Health/readiness endpoints per module (`/health`, `/ready`)
- [ ] Convert user module to dual-mode

**Deliverables**:
- User module runs standalone: `java -jar boundary-user.jar`
- User module runs in monolith: Existing Integrant wiring
- Same code, zero changes

**Week 2: Inter-Module Communication** (TBD)
- [ ] Event bus abstraction (`IEventBus` port)
- [ ] Redis pub/sub adapter for events
- [ ] API gateway for routing between modules
- [ ] Versioned API contracts (OpenAPI per module)
- [ ] Correlation ID propagation across modules
- [ ] Convert inventory module to dual-mode

**Deliverables**:
- Modules can call each other (in-process OR HTTP)
- Event-driven integration working
- API gateway routes requests to modules

**Week 3: Distribution & Operations** (TBD)
- [ ] Module-level Docker builds (`Dockerfile.{module}`)
- [ ] Compatibility matrix tooling (which versions work together)
- [ ] Scaffolder: `boundary scaffold module-runtime --module {name}`
- [ ] Example deployment topologies (monolith, micro-mods, hybrid)
- [ ] Documentation + migration guide
- [ ] Performance benchmarking (overhead of HTTP vs in-process)

**Deliverables**:
- Docker images per module
- Scaffolder generates runtime support
- Complete documentation
- Example deployments

### Success Metrics

- [ ] Same code runs standalone OR composed (zero rewrites)
- [ ] < 5 min to convert existing module to dual-mode
- [ ] Module-level versioning and deployment
- [ ] Inter-module HTTP calls < 50ms overhead vs in-process
- [ ] Event bus throughput 10K+ events/sec
- [ ] Scaffolder automates 90% of conversion work
- [ ] Documentation: "Monolith to Microservices" migration guide

### Configuration Example

```clojure
;; Monolith mode (config.edn)
{:boundary/deployment-mode :monolith
 :boundary/modules [:user :inventory :orders :admin]
 :boundary/inter-module-comm :in-process}  ; Function calls

;; Standalone mode (user module config.edn)
{:boundary/deployment-mode :standalone
 :boundary/module :user
 :boundary/http {:port 3001}
 :boundary/inter-module-comm :http         ; HTTP calls
 :boundary/module-registry
 {:inventory "http://inventory-service:3002"
  :orders "http://orders-service:3003"}}

;; Hybrid mode (config.edn)
{:boundary/deployment-mode :hybrid
 :boundary/modules-local [:admin :reporting]      ; Composed
 :boundary/modules-remote
 {:user "http://user-service:3001"                ; Standalone
  :inventory "http://inventory-service:3002"      ; Standalone
  :orders "http://orders-service:3003"}}          ; Standalone
```

### Deployment Topologies

**Topology 1: Monolith (Development, Small Scale)**
```yaml
# docker-compose-monolith.yml
services:
  boundary:
    image: boundary/monolith:1.0.0
    environment:
      - BND_DEPLOYMENT_MODE=monolith
      - BND_MODULES=user,inventory,orders,admin
    ports:
      - "3000:3000"
```

**Topology 2: Independent Modules (Large Scale)**
```yaml
# docker-compose-microservices.yml
services:
  boundary-user:
    image: boundary/user:1.2.0
    environment:
      - BND_DEPLOYMENT_MODE=standalone
      - BND_PORT=3001
    ports:
      - "3001:3001"
    deploy:
      replicas: 5  # Scale user service independently
  
  boundary-inventory:
    image: boundary/inventory:1.1.0
    environment:
      - BND_DEPLOYMENT_MODE=standalone
      - BND_PORT=3002
    ports:
      - "3002:3002"
    deploy:
      replicas: 3
  
  boundary-orders:
    image: boundary/orders:1.3.0
    environment:
      - BND_DEPLOYMENT_MODE=standalone
      - BND_PORT=3003
    ports:
      - "3003:3003"
    deploy:
      replicas: 10  # Scale orders service independently
```

**Topology 3: Hybrid (Practical Production)**
```yaml
# docker-compose-hybrid.yml
services:
  # High-traffic modules: Standalone
  boundary-user:
    image: boundary/user:1.2.0
    environment:
      - BND_DEPLOYMENT_MODE=standalone
    deploy:
      replicas: 5
  
  boundary-orders:
    image: boundary/orders:1.3.0
    environment:
      - BND_DEPLOYMENT_MODE=standalone
    deploy:
      replicas: 10
  
  # Low-traffic modules: Composed
  boundary-core:
    image: boundary/composed:1.0.0
    environment:
      - BND_DEPLOYMENT_MODE=composed
      - BND_MODULES=admin,inventory,reporting,notifications
```

### Developer Experience

```bash
# Scaffolder generates runtime support
$ boundary scaffold module-runtime --module user
✓ Created src/boundary/user/runtime.clj
✓ Created Dockerfile.user
✓ Updated deps.edn with runtime dependencies
✓ Module ready for standalone deployment

# Run module in different modes
$ clojure -M:run-module user              # Standalone mode (port 3001)
Starting User module in standalone mode on port 3001...
User service ready at http://localhost:3001

$ clojure -M:compose user,inventory,orders  # Composed mode (monolith)
Starting Boundary in composed mode with modules: user, inventory, orders...
System ready at http://localhost:3000

$ java -jar target/boundary-user.jar      # Production standalone
Starting User module in standalone mode...

# Build module-specific artifacts
$ clojure -T:build module-jar :module user
Building standalone JAR for user module...
✓ Created target/boundary-user-1.2.0.jar

$ docker build -f Dockerfile.user -t boundary-user:1.2.0 .
✓ Built image: boundary-user:1.2.0

# Deploy different topologies
$ boundary deploy --topology monolith     # All modules in one process
$ boundary deploy --topology micro        # All modules standalone
$ boundary deploy --topology hybrid       # Mix composed + standalone
```

### Why This Is a Killer Feature

1. **True Flexibility Without Rewrites:**
   - Start simple (monolith for development)
   - Scale selectively (extract hot modules when needed)
   - **No code changes required** - same module runs in both modes
   - Reduces risk of premature optimization

2. **Enterprise Appeal:**
   - Teams can grow architecture with business needs
   - Extract modules only when justified (performance, team boundaries)
   - Hybrid topology: Best of both worlds (simple + scalable)
   - Different teams can own independent modules

3. **Absolutely Unique Differentiator:**
   - **No framework offers this**: Protocol-driven dual-mode modules
   - **FC/IS makes this possible**: Pure core works anywhere, shell adapters swap seamlessly
   - **Clojure protocols enable**: Adapter swapping (in-process ↔ HTTP)
   - **Boundary's module architecture**: Already designed for this

4. **Developer Experience:**
   - Same development workflow (monolith simplicity, fast REPL)
   - Production flexibility (scale critical paths independently)
   - Scaffolder automates conversion (< 5 minutes)
   - Test in monolith, deploy selectively

5. **Reduced Risk & Complexity:**
   - Test everything in monolith mode (fast, simple, debuggable)
   - Deploy critical paths independently (safety, isolation)
   - Gradually migrate (no big bang rewrite)
   - Rollback to monolith if microservices don't pay off

### Competitive Analysis

| Framework | Modular | Standalone Modules | Zero Rewrite | Dual-Mode | Notes |
|-----------|---------|-------------------|--------------|-----------|-------|
| **Boundary** | ✅ Runtime | ✅ Yes | ✅ Yes | ✅ Yes | FC/IS enables dual-mode |
| Django | ⚠️ Apps | ❌ No | ❌ No | ❌ No | Apps tightly coupled |
| Rails | ⚠️ Engines | ❌ No | ❌ No | ❌ No | Engines share Rails runtime |
| Spring Boot | ⚠️ Modules | ⚠️ Partial | ❌ No | ❌ No | Requires Spring context |
| Phoenix | ⚠️ Contexts | ❌ No | ❌ No | ❌ No | Contexts are logical, not deployable |
| Kit | ~⚠️ Templates | ❌ No | ❌ No | ❌ No | Modules = code generation, not runtime |
| Luminus* | ~⚠️ Templates | ❌ No | ❌ No | ❌ No | Legacy (Kit is successor) |

**Legend:** ✅ Built-in | ⚠️ Partial/3rd-party | ❌ Not available | \* Deprecated

### Market Position

> **"Boundary modules can run standalone OR composed in a monolith—same code, no rewrites. Start simple, scale selectively, avoid microservices premature optimization."**

**Taglines:**
- "Monolith simplicity, microservice flexibility, zero rewrites."
- "Deploy your architecture, not rewrite it."
- "The only framework where modules are deployment units from day one."

### Marketing Strategy

**Blog Post**: "Monolith to Microservices Without Rewrites: How Boundary Does It"
- Problem: Teams face false choice (monolith OR microservices)
- Solution: Boundary's dual-mode modules
- Demo: 5-minute video converting user module
- Architecture: How FC/IS + protocols enable this

**Video Tutorial**: "From Monolith to Microservices in 5 Minutes"
1. Show monolith running (all modules together)
2. Extract user module: `boundary scaffold module-runtime --module user`
3. Build standalone: `docker build -f Dockerfile.user`
4. Deploy: Show user module running independently
5. Demonstrate: Same code, zero changes

**Conference Talk**: "Architecture as Configuration: Deployment Topologies Without Rewrites"
- Abstract: How Boundary enables monolith ↔ microservices without code changes
- Demo: Live deployment topology switching
- Target: Clojure/conj, EuroClojure, Strange Loop, QCon

**Effort:** 3 person-weeks

---

## Feature #3: Real-Time Collaboration

### Why This Matters

**Modern UX Expectation**: Users expect live updates (notifications, presence, dashboards)

**Technical Showcase**: Demonstrates Clojure's concurrency strengths (core.async, agents, atoms)

**Enterprise Use Cases**:
- Live dashboards (admin sees changes instantly)
- Collaborative editing (multiple users editing same resource)
- Notification system (push updates)
- Live monitoring (job status, system health)

**Market Gap**:
- Phoenix has Channels (killer feature)
- Rails has ActionCable
- Clojure has libraries, no framework integration

### Goals

1. **WebSocket Infrastructure**
   - Bidirectional communication
   - Connection pooling
   - Automatic reconnection
   - Heartbeat/keepalive

2. **Server-Sent Events (SSE)**
   - Unidirectional (simpler)
   - HTTP/2 friendly
   - Automatic reconnection

3. **Channel Abstraction**
   - Pub/sub pattern
   - Presence tracking
   - Broadcast to channel
   - Private channels (user-specific)

4. **Integration Points**
   - Admin dashboard (live updates)
   - Background jobs (live status)
   - Search results (streaming)
   - Notifications

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                 WebSocket/SSE Handlers (Shell)              │
│  • Connection upgrade                                       │
│  • Message serialization                                    │
│  Files: boundary/realtime/shell/websocket.clj              │
│         boundary/realtime/shell/sse.clj                    │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                    Channel Manager (Shell)                  │
│  • Connection registry                                      │
│  • Message routing                                          │
│  • Presence tracking                                        │
│  Files: boundary/realtime/shell/channels.clj               │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                   Functional Core (Pure)                    │
│  • Message routing logic                                    │
│  • Presence calculation                                     │
│  • Channel authorization                                    │
│  Files: boundary/realtime/core/routing.clj                 │
│         boundary/realtime/core/presence.clj                │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                   Adapters (Distributed)                    │
│  • Redis pub/sub (multi-server)                            │
│  • In-memory (single server)                               │
│  Files: boundary/realtime/shell/adapters/redis.clj         │
└─────────────────────────────────────────────────────────────┘
```

### Deliverables

**Module Structure:**
```
src/boundary/realtime/
├── core/
│   ├── routing.clj       # Message routing logic (300 lines)
│   ├── presence.clj      # Presence tracking (200 lines)
│   └── auth.clj          # Channel authorization (150 lines)
├── shell/
│   ├── websocket.clj     # WebSocket handler (400 lines)
│   ├── sse.clj           # SSE handler (250 lines)
│   ├── channels.clj      # Channel manager (500 lines)
│   └── adapters/
│       ├── redis.clj     # Redis pub/sub (300 lines)
│       └── in_memory.clj # In-memory (200 lines)
├── ports.clj             # IWebSocketHandler, IChannelManager (200 lines)
└── schema.clj            # Message schemas (100 lines)
```

**Client Library (JavaScript)**:
```javascript
// resources/public/js/boundary-realtime.js (500 lines)
const rt = BoundaryRealtime.connect('/realtime');

rt.channel('admin:dashboard')
  .on('user:created', (data) => { /* update UI */ })
  .join();
```

**Total Code**: ~2,600 lines

### Milestones

**Week 1: Infrastructure** (Feb 3-9, 2026)
- [ ] Create `boundary/realtime/` module
- [ ] WebSocket handler (connection upgrade, send/receive)
- [ ] SSE handler (streaming responses)
- [ ] In-memory channel manager
- [ ] Basic routing logic

**Week 2: Channels & Presence** (Feb 10-16, 2026)
- [ ] Channel abstraction (join, leave, broadcast)
- [ ] Presence tracking (who's online)
- [ ] Private channels (user-specific)
- [ ] Redis adapter (distributed channels)
- [ ] Message persistence (optional)

**Week 3: Integration** (Feb 17-23, 2026)
- [ ] Admin dashboard live updates
- [ ] Job status streaming (Phase 4.1 integration)
- [ ] Search result streaming (Phase 4.5 integration)
- [ ] Notification system
- [ ] Client JavaScript library

**Week 4: Scale & Polish** (Feb 24 - Mar 2, 2026)
- [ ] Connection limit management
- [ ] Heartbeat/keepalive
- [ ] Automatic reconnection
- [ ] Performance testing (10K concurrent connections)
- [ ] Documentation + examples

### Success Metrics

- [ ] 1000+ concurrent WebSocket connections
- [ ] < 50ms message latency
- [ ] Automatic reconnection working
- [ ] Admin dashboard updates instantly
- [ ] Complete documentation

---

## Feature #4: Multi-Tenancy Framework

### Why This Matters

**SaaS Reality**: Most modern apps are multi-tenant

**Complexity**: Data isolation is hard, easy to get wrong (security nightmare)

**Enterprise Requirement**: Many enterprises won't adopt without multi-tenancy

**Market Gap**:
- Rails: acts_as_tenant (gem, not built-in)
- Django: django-tenant-schemas (not built-in)
- Phoenix: Manual schema switching
- Clojure: Nothing

### Goals

1. **Tenant Identification**
   - Subdomain (acme.myapp.com)
   - Header (X-Tenant-ID)
   - Path (/tenants/acme/...)
   - Token (JWT tenant claim)

2. **Data Isolation Strategies**
   - Schema-based (separate PostgreSQL schemas)
   - Row-level (tenant_id column + RLS)
   - Database-based (separate DB per tenant)

3. **Tenant Management**
   - Provisioning (create schema, seed data)
   - Deprovisioning (archive, delete)
   - Tenant configuration (feature flags, limits)
   - Cross-tenant operations (platform admin)

4. **Integration**
   - Tenant-aware caching
   - Tenant-aware background jobs
   - Tenant-aware search
   - Tenant-aware audit logging

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                  Tenant Middleware (Shell)                  │
│  • Resolve tenant from request                              │
│  • Inject into request context                              │
│  Files: boundary/tenancy/shell/middleware.clj              │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                   Database Context (Shell)                  │
│  • Switch PostgreSQL schema                                 │
│  • Apply RLS policies                                       │
│  Files: boundary/tenancy/shell/db_context.clj              │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│                   Functional Core (Pure)                    │
│  • Tenant identification logic                              │
│  • Access control rules                                     │
│  Files: boundary/tenancy/core/resolver.clj                 │
│         boundary/tenancy/core/access.clj                   │
└─────────────────────────────────────────────────────────────┘
```

### Deliverables

**Module Structure:**
```
src/boundary/tenancy/
├── core/
│   ├── resolver.clj      # Tenant identification (300 lines)
│   ├── access.clj        # Access control (200 lines)
│   └── provisioning.clj  # Provisioning logic (250 lines)
├── shell/
│   ├── middleware.clj    # Tenant middleware (300 lines)
│   ├── db_context.clj    # DB switching (400 lines)
│   ├── service.clj       # Tenant management (500 lines)
│   └── provisioner.clj   # Provisioning executor (400 lines)
├── ports.clj             # ITenantResolver, ITenantManager (200 lines)
└── schema.clj            # Tenant schemas (150 lines)
```

**Migrations:**
```sql
-- migrations/010_add_tenant_support.sql
CREATE SCHEMA tenant_template;
-- ... create tables in template schema

-- migrations/011_enable_rls.sql
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_isolation ON users
  USING (tenant_id = current_setting('app.current_tenant')::uuid);
```

**Total Code**: ~2,700 lines

### Milestones

**Week 1: Foundation** (Mar 3-9, 2026)
- [ ] Create `boundary/tenancy/` module
- [ ] Tenant resolver (subdomain, header, path)
- [ ] Middleware for tenant injection
- [ ] Database context switching (PostgreSQL schemas)
- [ ] Basic tenant CRUD

**Week 2: Data Isolation** (Mar 10-16, 2026)
- [ ] Schema-based isolation (create tenant schemas)
- [ ] Row-level security (RLS policies)
- [ ] Tenant-aware queries (automatic filtering)
- [ ] Cross-tenant queries (platform admin)
- [ ] Tenant configuration

**Week 3: Provisioning** (Mar 17-23, 2026)
- [ ] Tenant provisioning service
- [ ] Schema creation from template
- [ ] Seed data injection
- [ ] Deprovisioning (archive vs delete)
- [ ] Tenant migration tools

**Week 4: Integration** (Mar 24-30, 2026)
- [ ] Tenant-aware caching (namespace by tenant)
- [ ] Tenant-aware background jobs
- [ ] Tenant-aware search
- [ ] Tenant-aware audit logging
- [ ] Documentation + migration guide

### Success Metrics

- [ ] Data isolation verified (tenant A can't see tenant B data)
- [ ] Provisioning < 10 seconds
- [ ] No performance degradation with 100+ tenants
- [ ] Zero cross-tenant data leaks
- [ ] Complete documentation

---

## Feature #5: File Upload & Storage

### Goals

- Secure file uploads with validation
- Storage adapters (local, S3, GCS)
- Image processing (resize, thumbnails)
- Signed URLs for secure access
- CDN integration

### Deliverables

**Module Structure:**
```
src/boundary/storage/
├── core/
│   ├── validation.clj    # File validation (200 lines)
│   └── processing.clj    # Image processing (300 lines)
├── shell/
│   ├── service.clj       # Storage orchestration (400 lines)
│   ├── http.clj          # Upload endpoints (300 lines)
│   └── adapters/
│       ├── local.clj     # Local filesystem (200 lines)
│       ├── s3.clj        # AWS S3 (300 lines)
│       └── gcs.clj       # Google Cloud Storage (300 lines)
├── ports.clj             # IFileStorage, IImageProcessor (150 lines)
└── schema.clj            # File schemas (100 lines)
```

**Total Code**: ~2,250 lines

### Milestones

**Week 1: Foundation** (Mar 31 - Apr 6, 2026)
- [ ] Create `boundary/storage/` module
- [ ] File validation (type, size, virus scan)
- [ ] Local filesystem adapter
- [ ] S3 adapter
- [ ] Upload endpoints

**Week 2: Processing & Polish** (Apr 7-13, 2026)
- [ ] Image processing (resize, crop, thumbnails)
- [ ] Signed URLs (temporary access)
- [ ] GCS adapter
- [ ] Admin widget integration
- [ ] Documentation

### Success Metrics

- [ ] Upload, process, serve from S3
- [ ] Image resize < 500ms
- [ ] Signed URLs expire correctly
- [ ] Admin widget functional

---

## Feature #6: GraphQL Support (Optional)

### Goals

- Auto-generate GraphQL schema from entities
- DataLoader for N+1 prevention
- Subscriptions via real-time module
- GraphiQL interface

### Deliverables

**Module Structure:**
```
src/boundary/graphql/
├── core/
│   ├── schema_gen.clj    # Schema generation (400 lines)
│   └── resolvers.clj     # Resolver logic (300 lines)
├── shell/
│   ├── handler.clj       # GraphQL endpoint (300 lines)
│   └── dataloader.clj    # DataLoader (200 lines)
├── ports.clj             # IGraphQLHandler (100 lines)
└── schema.clj            # GraphQL schemas (100 lines)
```

**Total Code**: ~1,400 lines

### Milestones

**Week 1: Schema Generation** (Apr 14-20, 2026)
- [ ] Generate GraphQL schema from entities
- [ ] Basic resolvers
- [ ] GraphiQL UI

**Week 2: Optimization** (Apr 21-27, 2026)
- [ ] DataLoader implementation
- [ ] Subscriptions via realtime
- [ ] Documentation

### Success Metrics

- [ ] GraphQL parity with REST endpoints
- [ ] N+1 queries eliminated
- [ ] Subscriptions working

---

## Feature #7: Automated API Testing (Optional)

### Goals

- Property-based testing from schemas
- Fuzzing for edge cases
- Contract testing
- Load testing scenarios

### Deliverables

**Module Structure:**
```
src/boundary/testing/
├── api/
│   ├── generators.clj    # Test data generation (400 lines)
│   ├── fuzzing.clj       # Fuzzing strategies (300 lines)
│   └── load.clj          # Load testing (200 lines)
└── cli.clj               # CLI commands (200 lines)
```

**Total Code**: ~1,100 lines

### Milestones

**Week 1: Generators** (Apr 28 - May 4, 2026)
- [ ] Generate test cases from Malli schemas
- [ ] Property-based testing integration
- [ ] Fuzzing strategies

**Week 2: Integration** (May 5-11, 2026)
- [ ] Contract testing framework
- [ ] Load testing scenarios
- [ ] CI integration
- [ ] Documentation

### Success Metrics

- [ ] 50% test reduction via generation
- [ ] Find edge case bugs via fuzzing
- [ ] Load tests in CI

---

## Cross-Cutting Requirements

### Documentation

**For Each Feature:**
- [ ] Architecture overview
- [ ] Getting started guide
- [ ] Configuration reference
- [ ] API documentation
- [ ] Migration guide (if applicable)
- [ ] Troubleshooting guide
- [ ] Example applications

**Estimated**: 15-20KB per feature

### Observability

**For Each Feature:**
- [ ] Structured logging (all major operations)
- [ ] Metrics emission (latency, throughput, errors)
- [ ] Error reporting (exceptions captured)
- [ ] Distributed tracing (correlation IDs)

**Use Existing Ports:**
- `boundary.logging.ports/ILogger`
- `boundary.metrics.ports/IMetricsEmitter`
- `boundary.error-reporting.ports/IErrorReporter`

### Security

**For Each Feature:**
- [ ] RBAC integration (role-based access)
- [ ] Rate limiting (prevent abuse)
- [ ] CSRF protection (web forms)
- [ ] Input validation (Malli schemas)
- [ ] SQL injection prevention (parameterized queries)
- [ ] Secrets validation (no hardcoded secrets)

**Security Checklist:**
- [ ] OWASP Top 10 compliance
- [ ] Penetration testing
- [ ] Security headers (CSP, HSTS, etc.)
- [ ] Dependency scanning

### Developer Experience

**Scaffolder Updates:**
- [ ] `boundary scaffold admin-entity` - Register entity in admin
- [ ] `boundary scaffold realtime-channel` - Create new channel
- [ ] `boundary scaffold tenant-aware` - Make module tenant-aware
- [ ] `boundary scaffold storage-widget` - Add file upload to form

**IDE Support:**
- [ ] Update `.clj-kondo` configuration
- [ ] Add code snippets
- [ ] Update REPL helpers

---

## Implementation Timeline

### Overview (20 weeks total = 5 months)

| Phase | Feature | Duration | Start | End |
|-------|---------|----------|-------|-----|
| 5.1 | Visual Admin Dashboard | 4 weeks | Jan 6 | Feb 2 |
| 5.2 | Real-Time Collaboration | 4 weeks | Feb 3 | Mar 2 |
| 5.3 | Multi-Tenancy Framework | 4 weeks | Mar 3 | Mar 30 |
| 5.4 | File Upload & Storage | 2 weeks | Mar 31 | Apr 13 |
| 5.5 | GraphQL Support (Optional) | 2 weeks | Apr 14 | Apr 27 |
| 5.6 | API Testing (Optional) | 2 weeks | Apr 28 | May 11 |
| 5.7 | Polish & Marketing | 2 weeks | May 12 | May 25 |

**Total**: 20 weeks (5 months) to complete all killer features

### Team Requirements

**Option 1: Small Team (Recommended)**
- 1 Senior Clojure Engineer (full-time)
- 0.5 Technical Writer (documentation)
- 0.25 DevRel (marketing, examples)

**Option 2: Solo Developer**
- Extend timeline to 8-10 months
- Focus on features 1-4 (defer GraphQL/API Testing)
- Prioritize documentation as you go

---

## Success Metrics

### Phase 5.1 (Admin Dashboard)
- [ ] Admin accessible in <10 min from fresh install
- [ ] 80% dev time reduction for admin panels
- [ ] 3,000+ lines of production code
- [ ] 1,000+ lines of tests
- [ ] Complete documentation (20KB+)
- [ ] Video tutorial (15 min)
- [ ] Example app deployed

### Phase 5.2 (Real-Time)
- [ ] 1000+ concurrent connections
- [ ] < 50ms message latency
- [ ] Admin dashboard live updates working
- [ ] 2,600+ lines of production code

### Phase 5.3 (Multi-Tenancy)
- [ ] Zero cross-tenant data leaks
- [ ] Provisioning < 10 seconds
- [ ] 100+ tenants with no performance degradation
- [ ] 2,700+ lines of production code

### Phase 5.4 (File Upload)
- [ ] Upload, process, serve from S3
- [ ] Image resize < 500ms
- [ ] 2,250+ lines of production code

### Overall Phase 5 Completion
- [ ] 500+ GitHub stars
- [ ] 10+ production deployments
- [ ] 200+ Discord/community members
- [ ] 3+ conference talks accepted
- [ ] Competitive with Django/Rails feature set

---

## Risk Mitigation

### Technical Risks

**Risk: Features too ambitious (scope creep)**
- **Mitigation**: Strict weekly milestones, defer non-essential features
- **Fallback**: Ship MVPs, iterate based on feedback

**Risk: Performance issues at scale**
- **Mitigation**: Benchmarks per feature, load testing before GA
- **Fallback**: Redis/caching opt-in, document scaling strategies

**Risk: Integration complexity**
- **Mitigation**: Clear interfaces (ports), modular design
- **Fallback**: Features work independently, integration optional

### Market Risks

**Risk: Low adoption (no one uses it)**
- **Mitigation**: Build in public, gather feedback early, iterate
- **Fallback**: Internal tool, consultant productivity framework

**Risk: Competition releases similar features**
- **Mitigation**: Ship fast, market aggressively, focus on DX
- **Fallback**: Double down on Clojure-specific advantages (REPL, FC/IS)

### Resource Risks

**Risk: Limited development time**
- **Mitigation**: Focus on top 3 features (Admin, Real-Time, Multi-Tenancy)
- **Fallback**: Defer GraphQL/API Testing, prioritize core differentiators

**Risk: Documentation debt**
- **Mitigation**: Write docs during development, not after
- **Fallback**: Community-contributed docs, video tutorials instead of text

---

## Dependencies

### Infrastructure

**Required:**
- PostgreSQL 12+ (schema support, RLS, full-text search)
- Redis 6+ (distributed caching, channels, rate limiting)

**Optional:**
- AWS S3 / Google Cloud Storage (file storage)
- Datadog / Sentry (observability providers)

### External Libraries

**New Dependencies:**
- WebSocket support: `http-kit` or `aleph`
- Image processing: `mikera/imagez` or Java AWT
- S3 client: `com.amazonaws/aws-java-sdk-s3`
- GraphQL (optional): `com.walmartlabs/lacinia`

**Estimated**: 5-10 new dependencies across all features

---

## Marketing Strategy

### Content Plan

**Blog Posts** (1 per feature):
1. "Why Clojure Finally Has Django Admin" (Admin Dashboard)
2. "Real-Time Features Without JavaScript" (Real-Time)
3. "Multi-Tenancy Done Right" (Multi-Tenancy)
4. "From Zero to Production in 10 Minutes" (Overall DX)

**Video Tutorials** (15 min each):
1. Admin Dashboard Walkthrough
2. Building a Real-Time Dashboard
3. SaaS Multi-Tenancy Setup
4. Complete E-Commerce Example

**Conference Talks**:
1. Clojure/conj: "Auto-Generated UIs in Functional Programming"
2. EuroClojure: "Building SaaS with Clojure"
3. Strange Loop: "Functional Core, Imperative Shell at Scale"

### Community Building

**Channels:**
- Discord server: Boundary Framework Community
- Twitter/X: @BoundaryFramework
- Reddit: r/Clojure (weekly updates)
- Hacker News: Launch announcements

**Engagement:**
- Weekly office hours (live Q&A)
- Monthly livestream (feature development)
- Contributor recognition (Hall of Fame)
- Swag store (t-shirts, stickers)

### Launch Strategy

**Soft Launch** (After Admin Dashboard):
- Blog post + HN announcement
- Demo video on YouTube
- Discord invite
- Early adopter program (10 companies)

**1.0 Launch** (After Top 3 Features):
- Major announcement (HN, Reddit, Twitter)
- Press release
- Conference talks
- Partnership announcements

**Success Metrics:**
- 500+ GitHub stars in 3 months
- 10+ production deployments in 6 months
- 200+ community members in 6 months

---

## Competitive Positioning

### After Killer Features

| Capability | Boundary | Django | Rails | Spring Boot | Phoenix | Kit | Luminus* |
|------------|----------|--------|-------|-------------|---------|-----|----------|
| **FC/IS Architecture** | ✅ Enforced | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Module-Centric** | ✅ Runtime | ❌ | ❌ | ❌ | ✅ | ~⚠️ Templates | ~⚠️ Templates |
| **Independent Modules** | ✅ Dual-mode | ❌ | ❌ | ~⚠️ | ❌ | ❌ | ❌ |
| **AI-Powered DX** | ✅ **NEW** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Auto-Admin UI** | ✅ **NEW** | ✅ | ~⚠️ (gems) | ❌ | ❌ | ❌ | ❌ |
| **Real-Time** | ✅ **NEW** | ~⚠️ (channels) | ~⚠️ (cable) | ~⚠️ | ✅ | ~⚠️ (Sente) | ~⚠️ (Sente) |
| **Multi-Tenancy** | ✅ **NEW** | ~⚠️ (3rd party) | ~⚠️ (gems) | ❌ | ~⚠️ | ❌ | ❌ |
| **Background Jobs** | ✅ | ✅ | ✅ | ❌ | ✅ | ~⚠️ (Quartz) | ~⚠️ |
| **Full-Text Search** | ✅ | ~⚠️ | ~⚠️ | ❌ | ✅ | ❌ | ❌ |
| **MFA** | ✅ | ~⚠️ | ~⚠️ | ❌ | ~⚠️ | ~⚠️ (Buddy) | ~⚠️ |
| **Scaffolding** | ✅ Complete + AI | ✅ | ✅ | ❌ | ✅ | ~⚠️ Modules | ~⚠️ Templates |
| **File Storage** | ✅ S3/Local | ~⚠️ | ~⚠️ | ~⚠️ | ❌ | ❌ | ❌ |
| **Distributed Cache** | ✅ Redis | ~⚠️ | ~⚠️ | ~⚠️ | ✅ | ~⚠️ (module) | ~⚠️ |
| **API Pagination** | ✅ Cursor+Offset | ~⚠️ | ~⚠️ | ❌ | ✅ | ❌ | ❌ |

**Legend:**
- ✅ Built-in, production-ready
- ~⚠️ Requires 3rd-party library, module, or manual setup
- ❌ Not available or requires significant development
- \* Luminus is legacy; Kit is the official successor

### Value Proposition

> **Boundary is the first production-ready Clojure framework with AI-powered development experience, auto-generated admin UI, independently deployable modules, real-time collaboration, and multi-tenancy, enforcing Functional Core / Imperative Shell architecture for highly testable, modular systems that scale from prototype to production with confidence—and from monolith to microservices without rewrites.**

**Clojure Ecosystem Context:**
- **Kit/Luminus**: Template-based code generation (one-time)
- **Boundary**: AI-powered + runtime abstractions with dual-mode deployment (always switchable)
- **Kit modules**: Add features to projects
- **Boundary modules**: Deployment units that can run standalone OR composed

**Why Boundary Over Kit:**
1. **AI-Powered DX**: Natural language scaffolding, instant error explanations, auto-generated tests (no other framework has this)
2. **FC/IS Enforcement**: Architectural guardrails for testability
3. **Dual-Mode Modules**: Same code runs standalone (microservice) OR composed (monolith)
4. **Production Features**: Admin UI, MFA, full-text search, background jobs (all built-in)
5. **Zero-Rewrite Scaling**: Toggle deployment mode via config, not code changes

### Taglines

- "AI-powered development: Natural language → production-ready module in 2 minutes."
- "Build admin panels in 10 minutes, not 10 days."
- "Privacy-first AI: Local models, $0 cost, 10x faster development."
- "Real-time by default, multi-tenant ready."
- "Clojure's answer to Django, Rails, and Phoenix—but better."
- "Production-ready functional programming."
- "Monolith simplicity. Microservices flexibility. Zero rewrites."

---

## Why Choose Boundary Over Kit?

### The Kit vs Boundary Comparison

**Kit** is an excellent lightweight Clojure web framework with REPL-driven module installation. However, Boundary offers several unique advantages for production applications:

#### 1. Architecture Enforcement: FC/IS Pattern

**Kit:**
- No architectural enforcement
- Developers free to organize code however they prefer
- Can lead to inconsistent patterns across modules

**Boundary:**
- Enforces Functional Core / Imperative Shell pattern
- Pure business logic in `core/`, side effects in `shell/`
- 10x faster unit tests (no mocks needed)
- 5x easier refactoring (pure functions compose easily)

**Impact**: Boundary projects maintain consistency as teams grow; Kit projects require discipline.

#### 2. Module System: Templates vs Runtime Abstractions

**Kit Modules:**
```clojure
;; Install module via REPL (one-time code generation)
user=> (kit/install-module :kit/html)
:kit/html installed successfully!
restart required!

;; Module code is now in your project
;; src/clj/myapp/web/routes/pages.clj (generated)
;; Cannot run standalone, cannot switch modes
```

**Boundary Modules:**
```clojure
;; Module exists as runtime abstraction
;; src/boundary/user/ (ports + core + shell)

;; Run standalone (microservice)
$ clojure -M:run-module user  # Port 3001

;; Run composed (monolith)
$ clojure -M:compose user,inventory  # Port 3000

;; Same code, different deployment modes
;; Toggle via config, NOT code changes
```

**Impact**: Kit modules are one-time templates; Boundary modules are deployment units.

#### 3. Production Features: Built-in vs DIY

| Feature | Kit | Boundary |
|---------|-----|----------|
| **Admin UI** | ❌ Manual | ✅ Auto-generated (Django-style) |
| **Background Jobs** | ~⚠️ Quartz module | ✅ Built-in, cron + delayed |
| **MFA (2FA)** | ~⚠️ Buddy + manual | ✅ TOTP + backup codes |
| **Full-Text Search** | ❌ Manual | ✅ PostgreSQL-based, multi-field |
| **File Storage** | ❌ Manual | ✅ S3 + local with abstractions |
| **Distributed Cache** | ~⚠️ Redis module | ✅ Redis + in-memory with TTL |
| **API Pagination** | ❌ Manual | ✅ Cursor + offset strategies |
| **Scaffolding** | ~⚠️ Module installer | ✅ Complete modules (12 files, tests, lint-free) |

**Impact**: Boundary delivers production features out-of-the-box; Kit requires integration work.

#### 4. Independent Module Deployments: Boundary's Unique Feature

**The Problem Kit Doesn't Solve:**
- Start with monolith (simple, fast development)
- Need to scale specific modules (user auth under load)
- Options in Kit: Rewrite as microservice OR scale entire monolith
- **Neither option is ideal**

**Boundary's Solution:**
```bash
# Development: Monolith mode (fast, simple)
$ clojure -M:compose user,billing,inventory
Started on http://localhost:3000

# Production: Extract high-load module (zero rewrites)
$ docker run boundary-user:1.0        # Port 3001 (standalone)
$ docker run boundary-app:1.0         # Port 3000 (composed: billing + inventory)

# Same code in both modes
# Inter-module calls automatically adapt (in-process OR HTTP)
```

**Why This Matters:**
1. **Start Simple**: Monolith development (fast REPL, easy debugging)
2. **Scale Selectively**: Extract bottleneck modules without rewrite
3. **Reduce Risk**: Test in monolith, deploy critical paths independently
4. **Flexibility**: Move modules back to monolith if microservices don't pay off

**Impact**: Kit requires rewrite to go from monolith → microservices; Boundary just toggles config.

#### 5. Testing Strategy: Pure Core = No Mocks

**Kit Example (typical):**
```clojure
;; Testing requires mocking database, HTTP, etc.
(deftest create-user-test
  (with-redefs [db/insert! (fn [_ _] {:id 123})]  ; Mock database
    (let [result (create-user {:email "test@example.com"})]
      (is (= 123 (:id result))))))
```

**Boundary Example:**
```clojure
;; Core logic is pure (no mocks needed)
(deftest prepare-user-test
  (let [result (user-core/prepare-user {:email "test@example.com"})]
    (is (= "test@example.com" (:email result)))
    (is (some? (:id result)))))

;; Shell layer tested separately with real database (H2 in-memory)
```

**Impact**: Boundary unit tests run 10x faster (no setup/teardown); Kit tests require mocking.

#### 6. When to Choose Kit vs Boundary

**Choose Kit if:**
- Building small projects (< 5K LOC)
- Don't need admin UI or production features
- Prefer maximum flexibility over architectural consistency
- Comfortable integrating third-party libraries yourself

**Choose Boundary if:**
- Building production SaaS applications
- Need admin UI, MFA, search, background jobs out-of-the-box
- Want architectural guardrails (FC/IS pattern)
- Need to scale from monolith → microservices without rewrites
- Value consistency and testability

**Migration Path**: Kit projects can migrate to Boundary by refactoring into FC/IS pattern (typically 2-4 weeks for medium projects).

---

## Next Steps

### Immediate (This Week)

1. **Review & Approve**: Confirm Visual Admin Dashboard as Phase 5.1
2. **Create Design Doc**: Detailed technical design for admin module
3. **Set Up Tasks**: Break Week 1 into 20-25 small tasks
4. **Reserve Resources**: AWS for demo deployment, domain names

### Week 1 (Jan 6-12, 2026)

1. **Start Development**: Create `boundary/admin/` module
2. **Schema Introspection**: PostgreSQL schema reading
3. **Basic UI Components**: Table, form, navigation
4. **Integration**: Wire into existing system

### Marketing Prep

1. **Reserve Domains**:
   - `boundary-admin-demo.com`
   - `boundary-framework.org`

2. **Social Media**:
   - Create @BoundaryFramework Twitter
   - Set up Discord server
   - Create YouTube channel

3. **Content Pipeline**:
   - Draft blog post outline
   - Script video tutorial
   - Prepare conference talk abstracts

---

## Conclusion

**Visual Admin Dashboard is the clear priority** for Phase 5.1:
- Maximum market impact (Django Admin killer)
- Immediate visual wow factor
- Synergizes with all existing features
- Unique in Clojure ecosystem
- 4 weeks to production-ready

**Following with Real-Time and Multi-Tenancy** positions Boundary as:
- Complete enterprise framework
- Competitive with Django/Rails/Phoenix
- Clojure's best-in-class solution for SaaS

**Timeline**: 5 months to feature-complete, market-leading framework

**Go/No-Go Decision Point**: After Admin Dashboard (Week 4)
- If adoption strong → Proceed with Real-Time
- If adoption weak → Pivot to consulting/internal tool
- If DX poor → Iterate on admin, defer other features

---

**Ready to start Phase 5.1: Visual Admin Dashboard!** 🚀

**Next Action**: Create `docs/PHASE5_1_ADMIN_DASHBOARD_DESIGN.md` with detailed technical design.
