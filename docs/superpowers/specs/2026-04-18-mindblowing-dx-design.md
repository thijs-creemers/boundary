# Boundary DX Vision: Mindblowing Developer Experience

**Date:** 2026-04-18
**Status:** Draft
**Author:** Thijs Creemers + Claude

## Context

Boundary already has strong DX foundations: AI-powered scaffolding, 10+ automated quality gates, config doctor, structured validation with "Did you mean?" suggestions, REPL workflow with Integrant, interactive setup wizard, and per-library AGENTS.md documentation.

The goal is to elevate this from "good" to "mindblowing" -- an experience where the framework actively guides developers, surfaces information at the right moment, and removes every unnecessary friction point. The design principle is **progressive guidance that can be tuned down**: built-in by default, silenceable for experienced users.

This design covers the full journey from first clone to daily production development.

## Design Principles

1. **The framework is the best teacher** -- errors, REPL output, and scaffolding all proactively guide you
2. **Progressive disclosure** -- guidance level is configurable (`:full`, `:minimal`, `:off`)
3. **Never leave flow state** -- everything achievable from the REPL without switching context
4. **Errors are conversations, not dead ends** -- every error explains why, suggests a fix, and offers auto-recovery where possible
5. **Zero-config start** -- one command from clone to running app

---

## Pillar 1: Guidance Engine

A `boundary.guidance` system that provides contextual, actionable help throughout the development lifecycle.

### Architecture

- A `:boundary/guidance` Integrant key that boots with the system
- Configurable level: `:full` (default for new projects), `:minimal` (key warnings only), `:off`
- Hooks into system startup, REPL evaluation, HTTP error responses (dev mode), and scaffolding output
- Lives in `libs/devtools/` (dev-only dependency, zero production overhead, not published to Clojars)

### System Startup Dashboard

When `(go)` runs at `:full` guidance level:

```
┌─ Boundary Dev ──────────────────────────────────────┐
│ System:    running (12 components, 0 errors)        │
│ Database:  PostgreSQL @ localhost:5432/boundary_dev  │
│ Web:       http://localhost:3000                     │
│ Admin:     http://localhost:3000/admin               │
│ nREPL:     port 7888                                │
│                                                     │
│ Modules:   user, admin, payments (3 active)         │
│ Guidance:  full (set :guidance-level :minimal to    │
│            quiet this)                              │
│                                                     │
│ Try: (user-service) | (db-context) | (routes)       │
└─────────────────────────────────────────────────────┘
```

### Post-Scaffold Guidance

After `bb scaffold generate`:

```
✓ Module 'product' generated at libs/product/

Next steps:
1. Review schema:  libs/product/src/boundary/product/schema.clj
2. Wire module:    bb scaffold integrate product
3. Add migration:  bb migrate create add-products-table
4. Run tests:      clojure -M:test:db/h2 :product
```

### Dev-Mode Error Enrichment

Validation and config errors include contextual help:

```
Validation error on :user/create
├── :email — required field missing
│   Schema expects: [:string {:min 1}]
│   Hint: Check your request params — the key might be camelCase
│         in the request but should be :email (kebab-case) internally.
│         See: boundary.core.utils.case-conversion
└── :role — invalid value "superadmin"
    Allowed: #{:admin :user :viewer}
    Did you mean: :admin?
```

### FC/IS Violation Detection

At REPL time, when a core namespace accidentally requires a shell namespace:

```
⚠ FC/IS boundary violation detected
boundary.product.core.validation requires boundary.product.shell.persistence

Why this matters: Core namespaces must be pure functions — no I/O,
no database, no logging. This keeps your business logic testable
and portable.

Fix: Move the data access behind a port (protocol) in ports.clj,
then have shell implement it.

See: libs/core/AGENTS.md § FC/IS Architecture
```

---

## Pillar 2: Zero-Friction Onboarding

### `bb quickstart`

One command from clone to running app:

```bash
bb quickstart
# 1. Checks environment (Java, Clojure CLI, ports)
# 2. Runs bb setup interactively (or accepts --preset minimal)
# 3. Scaffolds a sample module (e.g., "tasks" with name, status, due-date)
# 4. Runs migrations
# 5. Starts the system
# 6. Opens browser to localhost:3000
# 7. Prints "Your first module is live. Try: curl localhost:3000/api/tasks"
```

### `bb doctor:env`

Pre-flight environment check (complements existing `bb doctor` which validates config). The relationship:
- `bb doctor` = config validation (existing, keeps current behavior)
- `bb doctor:env` = system prerequisites (new)
- `bb doctor --all` = both combined

```
Checking development environment...
✓ Java 21.0.2 (minimum: 17)
✓ Clojure CLI 1.12.0
✓ Babashka 1.12.196
✓ Node.js 20.11.0 (for UI assets)
✓ Port 3000 available
✓ Port 7888 available (nREPL)
✓ Port 9999 available (dev dashboard)
✓ SQLite available (default dev database)
⚠ PostgreSQL not found (optional, set :database :postgresql to use)
✓ clj-kondo installed
⚠ Ollama not running (AI features will use cloud fallback)
  → Start with: ollama serve
```

### IDE Bootstrapping

Ship editor configs, auto-generated by `bb setup` based on detected editor:

- **VS Code**: `.vscode/extensions.json` recommending Calva, `.vscode/settings.json` with nREPL port, test runner config
- **IntelliJ/Cursive**: `.idea/runConfigurations/` with REPL and test run configs
- **Emacs/CIDER**: `.dir-locals.el` with CIDER jack-in config, nREPL port
- **Neovim/Conjure**: `.conjure/` config with nREPL connection settings, key mappings for Boundary REPL helpers. Auto-detect Conjure installation and configure `g:conjure#client#clojure#nrepl#connection#auto_repl#port` to 7888.

### AI Provider Configuration

The AI-powered features (error explanation, code review, test suggestions, SQL copilot) support multiple providers with a pluggable architecture:

- **Ollama** (default): Local inference, no data leaves machine. Recommended models: `qwen2.5-coder:7b`, `codellama:13b`
- **MLX** (macOS native): For users running local models via MLX/oMLX. Configure with `{:provider :mlx :base-url "http://localhost:8080"}`. Supports any MLX-compatible model.
- **Anthropic**: Cloud fallback via Anthropic API
- **OpenAI**: Cloud fallback via OpenAI API
- **Custom OpenAI-compatible**: Any provider with an OpenAI-compatible API endpoint (LM Studio, text-generation-webui, vLLM, etc.). Configure with `{:provider :openai-compatible :base-url "http://localhost:XXXX/v1"}`

Provider auto-detection in `bb doctor:env`:
```
✓ MLX server detected at localhost:8080 (model: codellama-7b-q4)
  → Configured as primary AI provider
⚠ Ollama not running (available as fallback)
```

### Local Example App

- `examples/quickstart/` -- minimal app (1 module, SQLite, no auth)
- Lives in-repo (not in the separate `boundary-examples` repo) because it's part of the onboarding flow, not a showcase app. Kept minimal to avoid monorepo bloat.
- Referenced from the getting-started guide
- Runnable with `cd examples/quickstart && bb quickstart`

---

## Pillar 3: REPL as Command Center

The REPL becomes a full development cockpit -- the place where you never need to leave.

### System Introspection

```clojure
(status)                    ; Full system health: components, DB, ports, errors
(routes)                    ; All routes: method, path, handler, interceptors
(routes :user)              ; Filter by module
(routes "/api/users/:id")   ; Find route by path pattern
(interceptors :create-user) ; Full interceptor chain for a handler, in order
(config)                    ; Full running config (with secrets redacted)
(config :database)          ; Drill into a config section
(modules)                   ; Active modules, their status, component count
(deps-graph)                ; Visualize library dependency graph (prints ASCII)
```

### Request Simulation & Debugging

```clojure
(simulate :get "/api/users")
(simulate :post "/api/users" {:body {:email "test@example.com" :name "Test"}})
(simulate :get "/api/users" {:as :admin})       ; With role
;; Returns: status, headers, body, AND the interceptor trace

(trace :create-user)        ; Set a trace on a handler
(untrace :create-user)      ; Remove trace
(replay)                    ; Replay the last simulated/traced request
(replay {:email "changed@example.com"})  ; Replay with modified params
```

### Data Exploration

```clojure
(query :users)                          ; Quick SELECT * (dev only, limit 20)
(query :users {:where [:= :active true] :limit 5})
(count-rows :users)
(schema :user/create)                   ; Pretty-print Malli schema
(schema :user/create :example)          ; Generate example value from schema
(validate :user/create {:email "bad"})  ; Validate data against schema
(seed! :users 10)                       ; Generate and insert 10 random records
```

### Live System Mutation (Dev Only)

```clojure
(restart-component :boundary/http-server)   ; Hot-swap a single component
(swap-config! :database :pool-size 20)      ; Change config at runtime

;; Add a route at runtime (rapid prototyping)
(defroute! :get "/api/test" (fn [req] {:status 200 :body {:hello "world"}}))

;; Temporarily intercept all requests to a handler
(tap-handler! :create-user
  (fn [ctx] (println "Request:" (:request ctx)) ctx))
(untap-handler! :create-user)
```

### Time-Travel Debugging

```clojure
(recording :start)                          ; Record all requests in a session
(recording :stop)
(recording :list)                           ; Show captured requests with timing
(recording :replay 3)                       ; Replay request #3
(recording :replay 3 {:email "different@test.com"})  ; With modifications
(recording :diff 3 5)                       ; Compare two request/response pairs
(recording :save "auth-flow")               ; Save for later
(recording :load "auth-flow")               ; Restore saved recording
```

### Schema-Driven Development

```clojure
;; Generate everything from a schema description
(prototype! :invoice
  {:fields {:customer [:string {:min 1}]
            :amount   [:decimal {:min 0}]
            :status   [:enum [:draft :sent :paid :overdue]]
            :due-date :date}
   :endpoints [:crud :list :search]})
;; Generates: schema, ports, core, shell, tests, migration -- all wired

(schema-tree :user)       ; Visual schema tree with nested types
(schema-diff :user/create :user/update)  ; Compare schemas
```

### Observability

```clojure
(metrics)                  ; Request rate, error rate, avg latency
(metrics :user)            ; Per-module metrics
(metrics :slow)            ; Requests > 500ms

(log-filter! :user :warn)  ; Filter REPL log output
(log-tail 20)              ; Last 20 log entries
(log-search "failed")      ; Search recent logs

(pool-status)              ; HikariCP pool: active, idle, waiting, max
```

### AI-Assisted Development

```clojure
(ai/suggest :query "find users who signed up this week and haven't verified email")
(ai/review :user.core.validation)           ; AI code review
(ai/test-ideas :user.core.validation)       ; Suggest missing test cases
(ai/explain-interceptor :auth-interceptor)  ; Plain-English explanation
(ai/refactor-fcis 'boundary.invoice.core.validation)  ; AI-guided FC/IS fix
```

### Workflow Shortcuts

```clojure
(new-feature! "invoicing"
  "Invoice module with customer, line-items, PDF export, email notifications")
;; Interactive: confirms spec, scaffolds, integrates, runs tests

(migrate! :create "add-invoices-table"
  [[:id :uuid :primary-key]
   [:customer-id :uuid :references :users]
   [:amount :decimal :not-null]
   [:status :varchar :default "'draft'"]
   [:created-at :timestamp :default :now]])
;; Generates SQL migration AND runs it
```

### Development Flow

```clojure
(explain *e)                ; AI-powered exception explanation
(explain *e :verbose)       ; Include stack trace analysis + suggested fix
(next-steps)                ; What should you do next?
(guidance :minimal)         ; Tune down guidance
(watch :user)               ; Watch module, auto-run tests on save
(unwatch :user)
(test :user)                ; Run module tests from REPL
(test :user :unit)          ; Run only unit tests
(lint)                      ; Run clj-kondo from REPL
(check)                     ; Run all quality checks
```

---

## Pillar 4: Dev Dashboard

A local web UI at `localhost:9999` providing x-ray vision into the running system.

### Technical Approach

- Built as `libs/devtools/` (Boundary module, FC/IS compliant)
- HTMX + Hiccup (consistent with admin UI patterns)
- Only loads in `:dev` profile -- zero production overhead
- Integrant component alongside the system
- Uses existing observability interceptors for data collection

### Pages

#### 1. System Overview (`/dashboard`)
- Component health: all Integrant components with status
- Current config (secrets redacted)
- Active modules with route counts
- Environment info
- Quick links to admin UI, API docs

#### 2. Route Explorer (`/dashboard/routes`)
- Full route table: method, path, handler, interceptor chain
- Click route → interceptor pipeline visualized as flow diagram
- Filter by module, method, search by path
- "Try it" button: fill params, send test request, see response + trace

#### 3. Request Inspector (`/dashboard/requests`)
- Live request stream: every request with method, path, status, timing
- Click to expand: full request/response, interceptor trace, timing per phase
- Filter by status (errors), module, time range
- Highlight slow requests (configurable threshold)

#### 4. Schema Browser (`/dashboard/schemas`)
- All Malli schemas as interactive trees
- Example value generation
- Schema diff tool
- Validation playground: paste JSON, select schema, see result

#### 5. Database Explorer (`/dashboard/db`)
- Migration status (applied, pending, failed)
- Table browser: click table → schema + sample rows
- Query runner (HoneySQL or raw SQL)
- Connection pool stats (HikariCP)

#### 6. Error Dashboard (`/dashboard/errors`)
- Recent errors grouped by type with occurrence count
- Each error: stack trace, request context, AI-suggested fix
- Error codes linked to documentation
- FC/IS violations highlighted separately

#### 7. Jobs & Queues (`/dashboard/jobs`)
- Active/pending/failed/completed job counts
- Queue visualization: what's processing, what's waiting
- Failed job details with error + retry history
- Manual retry/cancel from UI
- Job timing stats: average duration, slowest jobs

#### 8. Config Editor (`/dashboard/config`)
- Full config tree, editable in dev mode
- Change value → see which components would restart
- "Apply" button: hot-apply, restart affected components
- Diff view: current vs. proposed
- Reset to file: revert runtime changes to config.edn

#### 9. Web REPL (`/dashboard/repl`)
- Browser-based REPL connected to the running nREPL server
- Pre-loaded with all devtools helpers (`(routes)`, `(simulate)`, `(schema)`, etc.)
- Syntax-highlighted Clojure input with auto-completion
- Rich output rendering: tables for query results, trees for schemas, formatted error output
- Command history (persisted across sessions)
- Quick-action buttons for common operations: `(status)`, `(routes)`, `(modules)`, `(next-steps)`
- Useful for developers who prefer browser-based workflows or are pairing/demoing without IDE access

#### 10. Git Worktree Manager (`/dashboard/worktrees`)
- List all active worktrees: path, branch, last commit, status (clean/dirty)
- Create new worktree: pick branch (or create new), choose directory, auto-setup (runs `bb setup` in new worktree)
- Quick-switch: click a worktree to see its status, open terminal commands
- Delete stale worktrees (with confirmation)
- Per-worktree system status: is a REPL running? On which port? Which DB?
- Branch comparison: show commits ahead/behind main for each worktree
- Useful for parallel feature development -- see all your work streams at a glance

Also available from the REPL:
```clojure
(worktrees)                  ; List all worktrees with branch, status, ports
(worktree-create! "feature/invoicing")  ; Create new worktree + setup
(worktree-status)            ; Current worktree details
```

And from the CLI:
```bash
bb worktree:list             # List all worktrees
bb worktree:create feature/invoicing  # Create + auto-setup
bb worktree:clean            # Remove worktrees with merged branches
```

#### 11. Security Status (`/dashboard/security`)
- Authentication setup: which auth methods are active (JWT, session, MFA)
- CSRF protection status
- CSP header configuration
- Rate limiting status
- Password policy summary
- Active sessions count
- Recent auth failures (failed logins, expired tokens)
- Security test coverage: which `:security` tests exist, last run status

---

## Pillar 5: Error Experience

Every error is a conversation, not a dead end.

### Error Code System

- Every Boundary error gets a code: `BND-xxx`
- Categories: `BND-1xx` (config), `BND-2xx` (validation), `BND-3xx` (persistence), `BND-4xx` (auth), `BND-5xx` (interceptor), `BND-6xx` (FC/IS)
- Each code maps to documentation: what it means, common causes, how to fix, examples

### Rich Error Output (Dev Mode)

```
━━━ BND-201: Schema Validation Failed ━━━━━━━━━━━━━━━━━━━━━━━━━━
Handler:  :user/create
Schema:   :user/create-schema

Errors:
  :email    → required (missing from input)
  :role     → "superadmin" is not in #{:admin :user :viewer}
              Did you mean: :admin?
  :password → "short" is too short (min: 12, got: 5)

Input received:
  {:name "Test User", :role "superadmin", :password "short"}

Expected shape (example):
  {:email "user@example.com"
   :name  "Test User"
   :role  :user
   :password "a-secure-password-123"}

Fix: Ensure all required fields are present and values match the
     schema at boundary.user.schema/create-schema

Dashboard: http://localhost:9999/dashboard/errors
Docs: https://boundary.dev/errors/BND-201
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### Error Auto-Recovery: `(fix! *e)`

Analyzes the last exception and offers automatic fixes:

```
━━━ BND-301: Migration Not Applied ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Table 'invoices' does not exist

Pending migration found: 20260418_add_invoices_table.sql

Auto-fix available:
  REPL:     (fix! *e)          ; Apply the pending migration
  Terminal: bb migrate up

Apply now? [y/N]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Known auto-fixes:**

| Error | `(fix! *e)` action |
|-------|-------------------|
| Missing migration | Runs `migrate up` |
| Missing env var | Sets for current session |
| Missing module wiring | Runs `integrate!` |
| Invalid config value | Suggests correct value, applies if unambiguous |
| Missing dependency | Suggests addition to `deps.edn` (always interactive, requires confirmation) |
| FC/IS violation | Shows refactoring steps + offers `(ai/refactor-fcis ...)` |

### Stack Trace Filtering

In dev mode, stack traces highlight your code and dim framework/JVM frames. The relevant Boundary namespace is always at the top.

---

## Pillar 6: Discoverability & Progressive Learning

### Contextual Tips

After significant actions, show one relevant tip (rotates, doesn't repeat in a session):

```
💡 Tip: You can run (simulate :post "/api/invoices" {:body {...}})
   to test your new endpoint without leaving the REPL.
   (Set :guidance-level :minimal to see fewer tips)
```

### `bb help next`

State-aware guidance:

```bash
$ bb help next

Your project has:
  ✓ 3 modules (user, admin, invoice)
  ⚠ 1 unintegrated module: invoice
    → Run: bb scaffold integrate invoice

  ⚠ 1 pending migration: 20260418_add_invoices_table.sql
    → Run: bb migrate up

  ⚠ No seed data defined
    → Create: resources/seeds/dev.edn

  ✓ All tests passing (42 tests)
  ✓ No linting errors
```

### In-REPL Documentation

```clojure
(doc :scaffold)          ; How to use scaffolding (with examples)
(doc :interceptors)      ; How interceptors work in Boundary
(doc :fcis)              ; Explain the FC/IS architecture
(doc :testing)           ; Testing strategies and helpers
```

### Command Palette

```clojure
(commands)               ; All available REPL commands, grouped:
;; System:     (go) (reset) (halt) (status) (config) ...
;; Data:       (query) (seed!) (count-rows) (schema) ...
;; Debug:      (simulate) (trace) (explain) (recording) ...
;; Generate:   (scaffold!) (migrate!) (prototype!) ...
;; Quality:    (test) (lint) (check) ...
;; AI:         (ai/suggest) (ai/review) (ai/test-ideas) ...
;; Help:       (doc) (commands) (next-steps) (guidance) ...
```

---

## Daily Workflow Commands

### Database Commands

```bash
bb db:status          # Migration status, current DB, connection info
bb db:reset           # Drop + recreate + migrate (with confirmation)
bb db:seed            # Run seed data from resources/seeds/dev.edn
bb db:console         # SQL REPL against dev database
bb db:snapshot        # Save current DB state
bb db:restore         # Restore from snapshot
```

### Unified Quality Checks

```bash
bb check              # Run ALL checks (fcis, deps, placeholder, kondo, doctor)
bb check --fix        # Auto-fix what can be fixed
bb check --quick      # Fast subset (kondo + fcis only)
```

### Contextual Help

```bash
bb help               # General help with common commands
bb help scaffold      # Detailed help for scaffolding
bb help next          # State-aware: what should you do next?
bb help error BND-201 # Lookup error code with causes + fixes
```

---

## Implementation Phasing (Suggested)

### Phase 1: Foundation (highest impact, lowest effort)
- `bb quickstart` command
- `bb doctor:env` environment checker
- `bb db:status`, `bb db:reset`, `bb db:seed`
- `bb check` unified command
- `bb help next` state-aware guidance
- IDE bootstrapping configs
- Error code system (BND-xxx) with initial catalog
- Startup dashboard in REPL

### Phase 2: REPL Power
- `(routes)`, `(status)`, `(config)`, `(modules)` introspection
- `(simulate)` request simulation
- `(query)`, `(schema)`, `(validate)` data exploration
- `(trace)` / `(untrace)` handler tracing
- `(test)`, `(lint)`, `(check)` from REPL
- `(commands)` palette
- `(doc)` in-REPL documentation
- Contextual tips system

### Phase 3: Error Experience
- Rich error output with BND-xxx codes
- Stack trace filtering (highlight user code)
- `(fix! *e)` auto-recovery for known error patterns
- `(explain *e)` AI-powered error explanation (already partially exists)
- Config error enrichment

### Phase 4: Dev Dashboard
- System overview page
- Route explorer with interceptor visualization
- Request inspector (live stream)
- Schema browser with validation playground
- Database explorer
- Error dashboard

### Phase 5: Advanced REPL
- `(recording)` time-travel debugging
- `(prototype!)` schema-driven development
- `(restart-component)` hot-swap
- `(defroute!)` runtime route addition
- `(tap-handler!)` / `(untap-handler!)` request tapping
- `(scaffold!)` from REPL

### Phase 6: Dashboard Extensions + AI
- Jobs & queues page
- Config editor with hot-apply
- Security status page
- `(ai/review)`, `(ai/test-ideas)`, `(ai/refactor-fcis)` AI tooling
- `(new-feature!)` full workflow automation

---

## Key Files to Create/Modify

### New Library: `libs/devtools/`
```
libs/devtools/
├── src/boundary/devtools/
│   ├── core/
│   │   ├── guidance.clj          ; Guidance engine (pure: message formatting)
│   │   ├── error_codes.clj       ; Error code catalog and lookup
│   │   ├── error_formatter.clj   ; Rich error output formatting
│   │   ├── introspection.clj     ; Route/config/module analysis (pure)
│   │   ├── schema_tools.clj      ; Schema tree, diff, example generation
│   │   └── state_analyzer.clj    ; Project state analysis for "next steps"
│   ├── shell/
│   │   ├── repl.clj              ; REPL helper functions (go, routes, simulate, etc.)
│   │   ├── dashboard/
│   │   │   ├── server.clj        ; Dashboard HTTP server (port 9999)
│   │   │   ├── pages/            ; Hiccup page handlers
│   │   │   └── components/       ; Shared UI components
│   │   ├── recording.clj         ; Request recording/replay
│   │   ├── tracer.clj            ; Handler tracing
│   │   └── auto_fix.clj          ; (fix! *e) implementation
│   ├── ports.clj
│   └── schema.clj
├── test/
└── resources/
    └── dashboard/
        └── assets/               ; CSS/JS for dashboard UI
```

### Relationship: `libs/tools/` vs `libs/devtools/`

- **`libs/tools/`** -- Babashka CLI tasks (`bb quickstart`, `bb doctor:env`, `bb db:*`, `bb check`, `bb help`). These run as Babashka scripts, outside the JVM. Existing `doctor.clj` and `setup.clj` already live here.
- **`libs/devtools/`** -- JVM REPL helpers and dashboard. Everything that runs inside the REPL or as part of the running Integrant system (`(routes)`, `(simulate)`, dashboard server, guidance engine). Dev-only, not published to Clojars (same as `libs/tools/`).
- Both are dev-only dependencies with zero production overhead.
- `(prototype!)` and `(scaffold!)` in the REPL will call into `libs/scaffolder/` internals, which need to be JVM-callable (not just Babashka). Verify that `libs/scaffolder/` core functions work on JVM classpath.

### REPL Wiring Strategy

Rather than bloating `dev/repl/user.clj` with dozens of imports, `libs/devtools/` provides its own `dev/repl/devtools.clj` loaded via `:extra-paths`. The `user.clj` namespace requires `devtools` and exposes a curated set of top-level helpers. Advanced functions are available via the `devtools` namespace.

### Modified Files
- `bb.edn` -- add `quickstart`, `doctor:env`, `db:*`, `check`, `help` tasks
- `libs/tools/src/boundary/tools/` -- new task implementations for CLI commands
- `libs/tools/AGENTS.md` -- document all new `bb` commands
- `deps.edn` -- add `libs/devtools` to `:dev` paths
- `dev/repl/user.clj` -- require devtools, expose curated REPL helpers
- `dev/repl/devtools.clj` -- full devtools REPL namespace (loaded via `:extra-paths`)
- `resources/conf/dev/config.edn` -- add `:boundary/guidance` and `:boundary/dashboard` keys
- `tests.edn` -- add `:devtools` test suite

### Existing Code to Leverage
- `libs/core/src/boundary/core/validation/` -- error formatting, "did you mean" engine
- `libs/ai/src/boundary/ai/shell/repl.clj` -- AI REPL helpers (explain, sql, gen-tests)
- `libs/tools/src/boundary/tools/doctor.clj` -- config validation rules
- `libs/tools/src/boundary/tools/setup.clj` -- setup wizard
- `libs/observability/` -- interceptor patterns for data collection
- `dev/repl/user.clj` -- existing REPL helpers to extend

---

## Verification Plan

### Per-Phase Verification

**Phase 1 (concrete acceptance criteria):**
- `bb quickstart` completes end-to-end on a clean clone with no manual intervention
- `bb doctor:env` exits 0 when all prerequisites present, exits 1 when critical tool missing
- `bb doctor:env` correctly detects Java version, Clojure CLI, Babashka, ports 3000/7888/9999
- `bb db:status` shows current migration state; `bb db:reset` drops and recreates DB (with confirmation prompt)
- `bb check` runs all quality gates and reports aggregated results
- `bb help next` detects: unintegrated modules, pending migrations, missing seed data
- Startup dashboard prints on `(go)` at `:full` guidance level, suppressed at `:off`
- `clojure -M:test:db/h2 :devtools` passes

**Phase 2:**
- All REPL introspection functions work after `(go)` and return accurate data
- `(routes)` output matches actual Reitit route table
- `(simulate :get "/api/users")` returns same response as a real HTTP request
- `(commands)` lists all available functions grouped by category

**Phase 3:**
- Intentionally trigger each BND-xxx error type → verify rich output with code, explanation, and fix suggestion
- `(fix! *e)` resolves: missing migration, missing env var, missing module wiring
- `(fix! *e)` for "missing dependency" always asks for confirmation before modifying `deps.edn`
- Stack traces in dev mode show user code first, framework frames dimmed

**Phase 4:**
- Dashboard loads at localhost:9999; all pages render without errors
- Route explorer shows accurate route data matching `(routes)` output
- Request inspector shows live request stream
- Schema browser generates valid example values from Malli schemas

**Phase 5:**
- `(recording :start)` / `(recording :stop)` captures requests; `(recording :replay N)` reproduces them
- `(prototype!)` generates a working module that passes tests after `(reset)`

**Phase 6:**
- Jobs page reflects actual queue state from `libs/jobs/`
- Config editor hot-applies changes and only restarts affected Integrant components

### Integration Tests
- New `:devtools` test suite in `tests.edn`
- Unit tests for all pure core functions (guidance, error formatting, introspection)
- Integration tests for REPL helpers against running system
- Contract tests for dashboard pages rendering correctly
