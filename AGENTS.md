# Boundary Framework — Developer Reference

Essential commands, conventions, and patterns for working with the Boundary Framework.

---

## Quick Reference

### Essential Commands

```bash
# Testing - All tests across all libraries
clojure -M:test:db/h2                              # All tests (H2 in-memory)
JWT_SECRET="dev-secret-32-chars-minimum" clojure -M:test:db/h2  # With JWT secret

# Testing - Per-library test suites
clojure -M:test:db/h2 :core                        # Core library tests
clojure -M:test:db/h2 :observability               # Observability library tests
clojure -M:test:db/h2 :platform                    # Platform library tests
clojure -M:test:db/h2 :user                        # User library tests
clojure -M:test:db/h2 :admin                       # Admin library tests
clojure -M:test:db/h2 :storage                     # Storage library tests
clojure -M:test:db/h2 :scaffolder                  # Scaffolder library tests
clojure -M:test:db/h2 :cache                       # Cache library tests
clojure -M:test:db/h2 :jobs                        # Jobs library tests
clojure -M:test:db/h2 :email                       # Email library tests
clojure -M:test:db/h2 :tenant                      # Tenant library tests
clojure -M:test:db/h2 :realtime                    # Realtime library tests
clojure -M:test:db/h2 :workflow                    # Workflow library tests
clojure -M:test:db/h2 :search                      # Search library tests
clojure -M:test:db/h2 :external                    # External adapters tests
clojure -M:test:db/h2 :payments                    # Payments library tests
clojure -M:test:db/h2 :reports                     # Reports library tests
clojure -M:test:db/h2 :calendar                    # Calendar library tests
clojure -M:test:db/h2 :geo                         # Geo library tests
clojure -M:test:db/h2 :ai                          # AI library tests
clojure -M:test:db/h2 :ui-style                    # UI style library tests

# Testing - By metadata category
clojure -M:test:db/h2 --focus-meta :unit           # Unit tests only
clojure -M:test:db/h2 --focus-meta :integration    # Integration tests
clojure -M:test:db/h2 --focus-meta :contract       # Database contract tests

# Testing - Watch mode and specific namespaces
clojure -M:test:db/h2 --watch :core                # Watch core library tests
clojure -M:test:db/h2 --focus validation-test                # Single namespace

# Update validation snapshots
UPDATE_SNAPSHOTS=true clojure -M:test:db/h2 --focus user-validation-snapshot-test

# Code Quality
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test  # Lint all code

# REPL Development
clojure -M:repl-clj                                # Start REPL (nREPL on port 7888)
# In REPL:
(require '[integrant.repl :as ig-repl])
(ig-repl/go)                                       # Start system
(ig-repl/reset)                                    # Reload and restart
(ig-repl/halt)                                     # Stop system

# Tools
clj-nrepl-eval --discover-ports                    # Find nREPL ports
clj-nrepl-eval -p 7888 "(+ 1 2)"                   # Evaluate code via nREPL
clj-paren-repair <file>                            # Fix parentheses

# Build
clojure -T:build clean && clojure -T:build uber    # Build uberjar
java -jar target/boundary-*.jar server             # Run standalone jar

# Database Migrations
clojure -M:migrate up                              # Run migrations

# Scripting (Babashka)
bb scaffold                                        # Interactive module scaffolding wizard
bb scaffold ai "product module with name, price"   # AI-powered NL scaffolding (interactive confirm)
bb scaffold ai "product module with name, price" --yes  # AI-powered NL scaffolding (non-interactive)
bb ai explain --file stacktrace.txt                # Explain error via AI
bb ai gen-tests libs/user/src/boundary/user/core/validation.clj  # Generate test namespace
bb ai sql "find active users with orders in last 7 days"          # HoneySQL from NL
bb ai docs --module libs/user --type agents                       # Generate AGENTS.md
bb ai admin-entity "products with name, price, status"            # Generate admin entity EDN config
bb doctor                                          # Validate config for common mistakes
bb doctor --env all --ci                           # Check all environments (CI mode)
bb setup                                           # Interactive config setup wizard
bb setup ai "PostgreSQL with Stripe"               # AI-powered config setup
bb scaffold integrate product                      # Wire scaffolded module into deps/tests/wiring
bb create-admin                                    # Create first admin user for a new project (interactive wizard)
bb create-admin --env prod                         # Use production config
bb create-admin --email a@b.com --name "Admin"     # Skip email/name prompts (password still prompted securely)
bb migrate up                                      # Run pending database migrations
bb migrate status                                  # Show current migration status
bb coverage                                        # Run Cloverage coverage with the monorepo test/classpath setup
bb check-links                                     # Validate local markdown links in AGENTS.md files
bb smoke-check                                     # Verify deps.edn aliases and key tool entrypoints
bb install-hooks                                   # Configure git hooks path to .githooks
bb scripts/docs_lint.clj                           # Run documentation drift linter directly

# Quality Gates (all run in CI + check:fcis runs in pre-commit)
bb check:fcis                                      # FC/IS enforcement: core/ must not import shell/IO/logging/DB
bb check:placeholder-tests                         # Detect (is true) placeholder assertions in tests
bb check:deps                                      # Verify library dependency direction + cycle detection
clojure -M:test:db/h2 --focus-meta :security      # Security-focused tests (error mapping, CSRF, XSS, SQL)
```

### Architecture Quick Facts

```
┌─────────────────────────────────────────────┐
│         IMPERATIVE SHELL (shell/*)          │
│  I/O, validation, logging, side effects     │
└─────────────────────────────────────────────┘
                    ↓ calls
┌─────────────────────────────────────────────┐
│           PORTS (ports.clj)                 │
│  Protocol definitions (interfaces)          │
└─────────────────────────────────────────────┘
                    ↑ implements
┌─────────────────────────────────────────────┐
│         FUNCTIONAL CORE (core/*)            │
│  Pure functions, business logic only        │
└─────────────────────────────────────────────┘
```

**Module Structure:**
```
libs/{library}/src/boundary/{library}/
├── core/          # Pure business logic
├── shell/         # I/O, validation, adapters
├── ports.clj      # Protocol definitions
└── schema.clj     # Malli validation schemas

# Library structure (monorepo)
libs/
├── core/          # Foundation: validation, utilities, interceptors
├── observability/ # Logging, metrics, error reporting
├── platform/      # HTTP, database, CLI infrastructure
├── user/          # Authentication, authorization, MFA
├── admin/         # Auto-CRUD admin interface
├── storage/       # File storage (local & S3)
├── scaffolder/    # Module code generator
├── cache/         # Distributed caching (Redis/in-memory)
├── jobs/          # Background job processing
├── email/         # Production-ready email sending (SMTP)
├── realtime/      # WebSocket/SSE for real-time features
├── tenant/        # Multi-tenancy (PostgreSQL schema-per-tenant)
├── workflow/      # State machine workflows with audit trails
├── search/        # Full-text search (PostgreSQL FTS / H2 LIKE)
├── payments/      # PSP abstraction: Mollie, Stripe, Mock (checkout-session flow)
├── external/      # External communication adapters: SMTP, IMAP, Twilio
├── reports/       # Report definitions, PDF/CSV export, scheduling (defreport macro)
├── calendar/      # Calendar events, RRULE recurrence, iCal export/import, conflict detection
├── geo/           # Geocoding (OSM/Google/Mapbox), DB-backed cache, Haversine distance
├── ai/            # Framework-aware AI tooling: NL scaffolding, error explainer, test generator, SQL copilot, docs wizard
└── ui-style/      # Shared UI style bundles, tokens, and CSS assets contract

# boundary-tools (at repo root, not under libs/)
boundary-tools/    # Developer tooling: scaffolding, AI, i18n, deploy, dev utilities
```

---

## Critical Conventions

### 1. ALWAYS Use kebab-case Internally

| Location | Format | Example |
|----------|--------|---------|
| **All Clojure code** | kebab-case | `:password-hash`, `:created-at` |
| **Database (at boundary only)** | snake_case | `password_hash`, `created_at` |
| **API (at boundary only)** | camelCase | `passwordHash`, `createdAt` |

```clojure
;; ✅ CORRECT - Convert ONLY at persistence boundary using shared utilities
(require '[boundary.core.utils.case-conversion :as cc])

;; At persistence boundary - DB to Clojure
(cc/snake-case->kebab-case-map db-record)

;; At persistence boundary - Clojure to DB
(cc/kebab-case->snake-case-map entity)

;; Alternative: Manual transformation
(defn db->user-entity [db-record]
  (-> db-record
      (set/rename-keys {:created_at :created-at
                        :password_hash :password-hash
                        :updated_at :updated-at})))
```

**Why**: Recent bug caused authentication failures because service layer used `:password_hash` but entities had `:password-hash`.

### 2. Layer Responsibilities

| Layer | Allowed | Never |
|-------|---------|-------|
| **Core** | Pure functions, calculations | I/O, logging, exceptions |
| **Shell** | I/O, validation, side effects | Business logic |
| **Ports** | Protocol definitions | Implementations |

### 3. Dependency Rules

- ✅ Shell → Core (shell calls core)
- ✅ Core → Ports (core depends on protocols)
- ✅ Shell → Adapters (shell implements protocols)
- ❌ Core → Shell (NEVER)
- ❌ Core → Adapters (NEVER)

---

## Common Workflows

### Adding New Functionality

1. **Define schema** in `libs/{library}/src/boundary/{library}/schema.clj`
2. **Write core logic** in `libs/{library}/src/boundary/{library}/core/{domain}.clj` (pure functions)
3. **Write unit tests** in `libs/{library}/test/boundary/{library}/core/{domain}_test.clj`
4. **Define port** in `libs/{library}/src/boundary/{library}/ports.clj` (protocol)
5. **Implement in service** in `libs/{library}/src/boundary/{library}/shell/service.clj`
6. **Add HTTP endpoint** in `libs/{library}/src/boundary/{library}/shell/http.clj`

### Testing Workflow

```bash
# Watch mode while developing
clojure -M:test:db/h2 --watch --focus-meta :unit

# Watch specific library
clojure -M:test:db/h2 --watch :core

# Full test suite before committing
clojure -M:test:db/h2

# Lint before committing
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test
```

### REPL Debugging

```clojure
;; Get system component
(def user-svc (get integrant.repl.state/system :boundary/user-service))

;; Test directly
(ports/list-users user-svc {:limit 10})

;; Reload namespace with changes (use :reload)
(require '[boundary.user.core.user :as user-core] :reload)

;; Full system reset
(ig-repl/halt)
(ig-repl/go)
```

### Debugging Workflow

When encountering 500 errors or unexpected behavior:

1. **Check logs first** - Errors are logged with stack traces:
   ```bash
   tail -100 logs/app.log | grep -A 10 "ERROR"
   ```

2. **Add temporary logging** - Use `println` for quick debugging:
   ```clojure
   (println "DEBUG:" {:field value :other other-value})
   ```
   Output appears in REPL/server stdout, not log files.

3. **Test in isolation via REPL** - Bypass HTTP layer to test services directly:
   ```clojure
   (def admin-svc (get integrant.repl.state/system :boundary/admin-service))
   (admin-ports/update-entity admin-svc :users uuid {:name "Test"})
   ```

4. **Check actual HTTP request data** - Add temporary logging in handlers:
   ```clojure
   (println "DEBUG request:"
            {:method (:request-method request)
             :params (:params request)
             :headers (select-keys (:headers request) ["hx-request"])})
   ```

5. **Verify database state** - Query directly via REPL:
   ```clojure
   (def ds (get-in integrant.repl.state/system [:boundary/db-context :datasource]))
   (jdbc/execute! ds ["SELECT * FROM users WHERE id = ?" uuid])
   ```

6. **Remove debug logging before committing** - Clean up all `println` statements.

**Key Principle**: Start from the innermost layer (core/database) and work outward to HTTP layer. Don't debug through the full stack if you can isolate the issue.

---

## Common Pitfalls

### 1. snake_case vs kebab-case Mixing

**Problem**: Using snake_case internally causes nil lookups.

**Solution**:
- Always use kebab-case in all internal code
- Convert at boundaries only (persistence layer, HTTP layer)
- Use utilities: `snake-case->kebab-case-map`, `kebab-case->snake-case-map`

### 2. defrecord Changes Not Taking Effect

**Problem**: `(ig-repl/reset)` doesn't recreate defrecord instances.

**Solution**:
```clojure
(ig-repl/halt)
(ig-repl/go)
;; OR restart REPL entirely
```

### 3. Unbalanced Parentheses

**Problem**: Manual editing creates syntax errors.

**Solution**:
```bash
# ALWAYS use the tool, never manually fix
clj-paren-repair libs/user/src/boundary/user/core/user.clj
```

### 4. Validation in Wrong Layer

```clojure
;; ❌ WRONG - Validation in core
(defn create-user [user-data]
  (when-not (valid? user-data)  ; Side effect in pure code!
    (throw ...)))

;; ✅ CORRECT - Validation in shell
(defn create-user-service [user-data]
  (let [[valid? errors data] (validate user-data)]
    (if valid?
      (user-core/create-user data)  ; Core gets clean data
      (throw ...))))
```

### 5. Core Depending on Shell

```clojure
;; ❌ WRONG - Core requires shell namespace
(ns boundary.user.core.user
  (:require [boundary.user.shell.persistence :as db]))  ; BAD!

;; ✅ CORRECT - Core depends on ports only
(ns boundary.user.core.user)
(defn find-user-decision [user-id existing-user] ...)  ; Pure logic
```

### 6. Schema-Database Mismatch

**Problem**: Fields referenced in business logic but missing from database/schema.

**Root Cause**: Adding logic that uses new fields without:
1. Adding fields to Malli schema
2. Adding database columns
3. Adding field transformations in persistence layer

**Detection**:
- 500 errors with cryptic messages
- `nil` values for expected fields
- SQL errors about missing columns

**Solution**:
```clojure
;; ALWAYS follow this checklist when adding new fields:

;; 1. Add to Malli schema (src/{module}/schema.clj)
[:failed-login-count {:optional true} :int]
[:lockout-until {:optional true} [:maybe inst?]]

;; 2. Add to persistence layer transformations (src/{module}/shell/persistence.clj)
;; In entity->db:
(update :lockout-until type-conversion/instant->string)
;; In db->entity:
(update :lockout-until type-conversion/string->instant)

;; 3. Verify database has columns (or add migration)
ALTER TABLE users ADD COLUMN failed_login_count INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN lockout_until TEXT;
```

**Prevention**: When referencing a new field in code, immediately check:
1. Does it exist in the schema?
2. Does the database table have the column?
3. Are transformations in place (for types like inst, decimal, etc.)?

### 7. Exception Handling - Missing :type in ex-data

**Problem**: Exceptions without `:type` in ex-data trigger generic 500 errors.

**Symptom**: Log warning "Exception reached HTTP boundary without :type in ex-data"

**Root Cause**: Throwing exceptions from Java methods or using `ex-info` without `:type`:
```clojure
;; ❌ WRONG - No :type in ex-data
(parse-long "invalid")  ; Throws NumberFormatException (no ex-data)
(UUID/fromString "bad") ; Throws IllegalArgumentException (no ex-data)
(throw (ex-info "Error" {:field :foo}))  ; Missing :type
```

**Solution**: Wrap external calls in try-catch with typed errors:
```clojure
;; ✅ CORRECT - Wrap in try-catch with :type
(try
  (parse-long value)
  (catch NumberFormatException _
    (throw (ex-info "Invalid integer value"
                    {:type :validation-error
                     :field field-keyword
                     :value value
                     :message "Must be a valid integer"}))))
```

**Prevention**:
- ALL `ex-info` calls MUST include `:type` key
- Wrap calls to Java methods that can throw (parse-long, UUID/fromString, bigdec, etc.)
- Use validation-error, not-found, unauthorized, forbidden, conflict, or internal-error as types
- Add new types to error interceptor's case statement if needed

### 8. Java Interop - Static vs Instance Methods

**Problem**: Incorrect Java method invocation syntax causes runtime errors.

**Symptom**: `IllegalArgumentException: No matching method <method-name> found taking N args for class java.lang.Class`

**Root Cause**: Using instance method syntax (`.method`) for static methods or vice versa:
```clojure
;; ❌ WRONG - Using instance syntax for static method
(.between java.time.Duration instant now)
;; Error: No matching method between found taking 2 args

;; ❌ WRONG - Using static syntax for instance method
(java.time.Instant/getSeconds my-instant)
;; Error: No matching field or method getSeconds
```

**Solution**: Use correct syntax for static vs instance methods:
```clojure
;; ✅ CORRECT - Static method using ClassName/method
(java.time.Duration/between instant now)

;; ✅ CORRECT - Instance method using .method
(.getSeconds duration)
```

**Common Java Interop Patterns**:
```clojure
;; Static methods (ClassName/method)
(java.time.Instant/now)
(java.util.UUID/randomUUID)
(java.lang.Math/abs -5)
(java.time.Duration/between start end)

;; Instance methods (.method object)
(.toString my-object)
(.getSeconds duration)
(.format instant formatter)
```

### 9. Module API Routes — Wrong Format (Reitit Vectors vs Normalized Maps)

**Problem**: Module `shell/http.clj` files return Reitit-style route vectors instead of the normalized map format expected by the platform wiring. Causes `IllegalArgumentException: Key must be integer` on `(ig-repl/go)`.

**Symptom**:
```
Execution error (IllegalArgumentException) at boundary.platform.shell.http.versioning/wrap-route-with-version
Key must be integer
```

**Root Cause**: The platform's `apply-versioning` calls `(update route :path ...)`. This works on maps but throws on vectors, because `update` on a Clojure vector requires an integer index, not a keyword.

```clojure
;; ❌ WRONG - Reitit-style tuple (do NOT use for module :api routes)
(defn my-routes [svc]
  [["/api/my-resource"
    {:get {:handler (fn [req] ...)}}]])

;; ✅ CORRECT - Normalized map format
(defn my-routes [svc]
  [{:path    "/my-resource"   ; NO /api prefix — versioning adds /api/v1
    :methods {:get {:handler (fn [req] ...)
                    :summary "..."}}}])
```

**Two rules to remember**:
1. API routes in `shell/http.clj` MUST be vectors of maps `[{:path "..." :methods {...}}]`, not Reitit vectors.
2. Paths must NOT include the `/api` prefix. The platform's versioning middleware adds `/api/v1` automatically. Including it produces double-prefixed routes (`/api/v1/api/my-resource`).

;; Static fields (ClassName/FIELD)
java.time.temporal.ChronoUnit/DAYS

;; Instance fields (.field object)
(.length my-string)
```

**Prevention**:
- Check Java documentation to identify static vs instance methods
- Static methods in Java docs: `public static Duration between(Temporal start, Temporal end)`
- Instance methods in Java docs: `public long getSeconds()`
- Use IDE or REPL to verify before committing
- Test changes via REPL immediately after editing

### 10. Forward References — Define Before Use

**Problem**: Clojure compiles a file top-to-bottom. A `defn` (or `defn-`) that calls another function defined *later* in the same file causes a compile-time error.

**Symptom**:
```
Unable to resolve symbol: my-helper in this context
  Syntax error compiling at (my_file.clj:42:5)
```

**Root Cause**: Private helpers placed *after* the public functions that call them.

```clojure
;; ❌ WRONG — system-component is called before it is defined
(defn dev-resend-invite! [...]
  (let [svc (system-component :boundary/my-service)] ...))  ; used here

(defn- system-component [k]   ; defined here — too late!
  (get integrant.repl.state/system k))

;; ✅ CORRECT — helper defined first
(defn- system-component [k]
  (get integrant.repl.state/system k))

(defn dev-resend-invite! [...]
  (let [svc (system-component :boundary/my-service)] ...))
```

**Rule**: Private helpers must always appear *above* the first function that calls them. This applies in `src/`, `dev/`, and `test/` files alike.

### 11. Swagger/OpenAPI — Parameters Invisible Without Explicit Declaration

**Problem**: Routes with path or query parameters show no input fields in Swagger UI.

**Root Cause**: `reitit-swagger` only auto-generates parameter fields when the router has a coercion layer configured (Malli/Spec/Schema). Without coercion, Swagger UI renders endpoints with no inputs regardless of route patterns like `/:id`.

**Solution**: Add a `:swagger` key with raw OpenAPI 2.0 parameter specs to every handler (or route) that has path or query parameters:

```clojure
;; Path parameter — on the method
:get {:handler ...
      :summary "Get product by slug"
      :swagger {:parameters [{:name "slug" :in "path" :required true :type "string"
                              :description "Product slug (e.g. boundary-tshirt)"}]}}

;; Query parameters — on the method
:get {:handler ...
      :summary "List customer orders"
      :swagger {:parameters [{:name "email"  :in "query" :required true  :type "string"}
                             {:name "limit"  :in "query" :required false :type "integer"}
                             {:name "offset" :in "query" :required false :type "integer"}]}}

;; Shared path param when a route has multiple methods — put :swagger at route level
["/api/cart/items/:product-id"
 {:swagger {:parameters [{:name "product-id" :in "path" :required true :type "string"}]}
  :patch  {:handler ... :summary "Update item quantity"}
  :delete {:handler ... :summary "Remove item from cart"}}]
```

**Rule**: after adding any new parameterised route, immediately add the `:swagger` block. Without it the parameter is invisible to API consumers using the Swagger UI.

---

## Key Technologies

| Library | Purpose | Critical Knowledge |
|---------|---------|-------------------|
| **Integrant** | Lifecycle | Use `ig-repl/go`, `ig-repl/reset`, `ig-repl/halt` |
| **Aero** | Config | Environment-based profiles in `resources/conf/` |
| **Malli** | Validation | Schemas in `{module}/schema.clj` |
| **next.jdbc** | Database | Connection pooling, prepared statements |
| **Ring/Reitit** | HTTP | Normalized routes, interceptors |
| **HTMX** | Web UI | Server-side rendering, no build step |

---

## Configuration

**Current Setup (Development)**:
- **Database**: SQLite (`dev-database.db`) - no setup required
- **HTTP**: Port 3000 (auto-find if busy: 3001-3099)
- **Environment**: Set `BND_ENV=development`

**Environment Variables**:
```bash
export JWT_SECRET="dev-secret-minimum-32-characters"
export DB_PASSWORD="dev_password"
```

**Configuration File**: `resources/conf/dev/config.edn`

---

## Internationalisation (i18n) — ADR-013

Translation keys live as `[:t :key]` data markers inside Hiccup trees.
A shell-layer `postwalk` resolves them before HTML is emitted.

### Workflow: adding a new string

1. **Add to `en.edn`** first — `libs/i18n/resources/boundary/i18n/translations/en.edn`
2. **Add to `nl.edn`** — `libs/i18n/resources/boundary/i18n/translations/nl.edn`
3. **Use marker in Hiccup** — `[:t :user/my-key]` instead of `"Hardcoded string"`
4. **Verify** — `bb i18n:scan` (exits 0 if no unexternalised literals remain)
5. **Check parity** — `bb i18n:missing` reports gaps between locales

### Marker syntax

```clojure
[:t :user/sign-in]                        ; simple lookup
[:t :user/greeting {:name "Alice"}]       ; interpolation
[:t :user/items {:n 3} 3]                 ; plural (4th arg = count)
```

### Babashka tooling

```bash
bb i18n:find "Sign in"      ; search catalogue + codebase
bb i18n:scan                ; CI gate — exit 1 if literals found
bb i18n:missing             ; report translation gaps
bb i18n:unused              ; report unreferenced catalogue keys
```

### Key namespaces in catalogues

| Namespace | Module |
|-----------|--------|
| `:common/*` | Shared across all modules |
| `:user/*` | User module |
| `:admin/*` | Admin module |
| `:search/*` | Search module |
| `:calendar/*` | Calendar module |
| `:workflow/*` | Workflow module |

### Full documentation

See `libs/i18n/AGENTS.md` for complete API reference, middleware wiring, and common pitfalls.

---

## Git Policy

- **Never commit or push without explicit user approval.** Always show the intended changes and wait for the user to say "commit" or "push" before running `git commit` or `git push`.

---

## Maintenance Notes

- Keep command examples in one place (`Quick Reference`); avoid duplicate command blocks elsewhere in this file.
- Library-specific troubleshooting and deep implementation notes belong in `libs/*/AGENTS.md`.
- Use `bb check-links` after editing AGENTS documentation.
- Install local pre-commit hooks once per clone: `bb install-hooks`.

---

## Quality Gates

Six automated safeguards run in CI (and `check:fcis` in pre-commit) to prevent regressions caught during QA review (PRs #108–#116).

| Gate | Command | What it catches | Hard fail? |
|------|---------|-----------------|------------|
| **FC/IS enforcement** | `bb check:fcis` | Core namespaces importing shell, I/O, logging, or DB code | Yes |
| **Placeholder tests** | `bb check:placeholder-tests` | `(is true)` / `(is (= true true))` masking missing coverage | Yes |
| **Dependency direction** | `bb check:deps` | Core independence violations, circular deps between libraries | Yes (cycles/core); warn (undeclared) |
| **Security tests** | `clojure -M:test:db/h2 --focus-meta :security` | Error→HTTP mapping, CSRF routing, XSS escaping, SQL injection, sensitive field leaks | Yes (test failure) |
| **clj-kondo lint** | `clojure -M:clj-kondo --lint ...` | Static analysis (existing gate) | Yes |
| **Config doctor** | `bb doctor --env dev --ci` | Configuration errors (existing gate) | Yes |

**Scripts location:** `boundary-tools/src/boundary/tools/check_{fcis,tests,deps}.clj`
**Security tests:** `libs/platform/test/boundary/platform/shell/security_test.clj` (tagged `^:security ^:unit`)
**Handler test helpers:** `test/support/handler_test_helpers.clj` (Ring request builders, response assertions)
**ADRs:** `dev-docs/adr/ADR-021-fcis-boundary-rules.adoc`, `ADR-022-error-handling-conventions.adoc`

---

## Library-Specific Guides

Each library has its own `AGENTS.md` with library-specific patterns, pitfalls, and workflows.

| Library | Guide | Key Topics |
|---------|-------|------------|
| **core** | [`libs/core/AGENTS.md`](libs/core/AGENTS.md) | Validation framework, case conversion, interceptor pipeline, feature flags |
| **observability** | [`libs/observability/AGENTS.md`](libs/observability/AGENTS.md) | Service/persistence interceptor patterns |
| **platform** | [`libs/platform/AGENTS.md`](libs/platform/AGENTS.md) | HTTP interceptor architecture |
| **user** | [`libs/user/AGENTS.md`](libs/user/AGENTS.md) | MFA/security features, authentication patterns |
| **admin** | [`libs/admin/AGENTS.md`](libs/admin/AGENTS.md) | UI/Frontend (Hiccup, HTMX, Pico CSS, Lucide icons), entity config, form/HTMX pitfalls |
| **storage** | [`libs/storage/AGENTS.md`](libs/storage/AGENTS.md) | File storage (local/S3), validation, image processing, signed URLs |
| **scaffolder** | [`libs/scaffolder/AGENTS.md`](libs/scaffolder/AGENTS.md) | Module generation commands and workflow |
| **cache** | [`libs/cache/AGENTS.md`](libs/cache/AGENTS.md) | Distributed caching protocols, TTL, atomic ops, tenant-scoped cache |
| **jobs** | [`libs/jobs/AGENTS.md`](libs/jobs/AGENTS.md) | Background job processing, retry logic, worker pools, dead letter queue |
| **email** | [`libs/email/AGENTS.md`](libs/email/AGENTS.md) | SMTP sending, async/queued modes, jobs integration |
| **tenant** | [`libs/tenant/AGENTS.md`](libs/tenant/AGENTS.md) | Multi-tenancy, schema-per-tenant, provisioning, lifecycle states, membership management (ADR-016), email invite flow |
| **realtime** | [`libs/realtime/AGENTS.md`](libs/realtime/AGENTS.md) | WebSocket messaging, JWT auth, pub/sub, message routing |
| **workflow** | [`libs/workflow/AGENTS.md`](libs/workflow/AGENTS.md) | State machine definitions, transitions, lifecycle hooks, auto-transitions |
| **search** | [`libs/search/AGENTS.md`](libs/search/AGENTS.md) | Document indexing, FTS/LIKE strategy, filter support, migrations |
| **payments** | [`libs/payments/AGENTS.md`](libs/payments/AGENTS.md) | PSP abstraction (Mollie, Stripe, Mock), checkout-session flow, webhook verification |
| **external** | [`libs/external/AGENTS.md`](libs/external/AGENTS.md) | Twilio SMS/WhatsApp, SMTP transport, IMAP mailbox |
| **reports** | [`libs/reports/AGENTS.md`](libs/reports/AGENTS.md) | `defreport` macro, registry, PDF/CSV export, scheduling |
| **calendar** | [`libs/calendar/AGENTS.md`](libs/calendar/AGENTS.md) | `defevent` macro, RRULE recurrence (DST-aware), conflict detection, iCal export/import, Hiccup UI |
| **geo** | [`libs/geo/AGENTS.md`](libs/geo/AGENTS.md) | Multi-provider geocoding (OSM/Google/Mapbox), DB cache, rate limiting, Haversine distance |
| **ai** | [`libs/ai/AGENTS.md`](libs/ai/AGENTS.md) | Multi-provider AI (Ollama/Anthropic/OpenAI), NL scaffolding, error explainer, test generator, SQL copilot, docs wizard |
| **ui-style** | [`libs/ui-style/AGENTS.md`](libs/ui-style/AGENTS.md) | App-wide UI contract: style bundles (`:base`, `:pilot`, `:admin-pilot`), tokens, and shared CSS assets |

---

## Adding a New Library to CI

When a new library is added under `libs/`, update **`.github/workflows/ci.yml`** in three places:

1. **Lint step** — add `libs/{name}/src` to the `clojure -M:clj-kondo --lint \` path list.
2. **New test job** — copy an existing `test-*` job block; set `needs: lint` (or add a dependency if the lib depends on another boundary lib); run `clojure -M:test:db/h2 :{name}`.
3. **`test-summary` job** — add `test-{name}` to the `needs:` array and add an echo line.

Also add the lib's `:id` test suite to `tests.edn` and its source/test paths to the root `deps.edn`.

---

## Ecommerce API Example

A complete reference application demonstrating Boundary patterns with SQLite, Integrant, Reitit, and Swagger UI.
Source: https://github.com/thijs-creemers/boundary-examples/tree/main/ecommerce-api

```bash
# Clone boundary-examples and run from ecommerce-api/
git clone https://github.com/thijs-creemers/boundary-examples
cd boundary-examples/ecommerce-api
clojure -M:run          # Start server on port 3002
```

**Swagger UI**: `http://localhost:3002/api-docs/`
**OpenAPI spec**: `http://localhost:3002/swagger.json`

### Swagger parameter documentation

The app reads `query-params`/`path-params` directly and does **not** configure reitit coercion middleware. This means Swagger will not auto-generate input fields from route patterns. See **Common Pitfalls #10** for the required `:swagger` parameter pattern.

### SQLite `nil` LIMIT/OFFSET pitfall

Clojure's `{:or {limit 20 offset 0}}` destructuring only fires for **absent** keys. If the caller passes `{:limit nil :offset nil}` (e.g. from `some->` on missing query params), the defaults are ignored and `LIMIT nil` causes `SQLITE_MISMATCH`. Always guard with `(or limit 20)` / `(or offset 0)` at the point where the SQL params vector is assembled.

---

## boundary-tools — The 21st Artifact

`boundary-tools` is a standalone, independently publishable Clojars artifact containing all portable Babashka developer tooling. It lives at `boundary-tools/` (not under `libs/`) and is consumed by the monorepo via a local path dep:

```clojure
;; bb.edn (monorepo)
{:deps {org.boundary-app/boundary-tools {:local/root "boundary-tools"}}}
```

Any project using `boundary-starter` or starting fresh should consume it via:

```clojure
;; bb.edn (consumer project)
{:deps {org.boundary-app/boundary-tools {:mvn/version "1.0.0-alpha"}}}
```

### Namespaces

| Namespace | Entry point |
|---|---|
| `boundary.tools.scaffold` | `bb scaffold` |
| `boundary.tools.ai` | `bb ai` |
| `boundary.tools.doctor` | `bb doctor` — config validation (6 rule-based checks) |
| `boundary.tools.setup` | `bb setup` — config setup wizard (interactive / flags / AI) |
| `boundary.tools.integrate` | `bb scaffold integrate` — wire modules into deps/tests/wiring |
| `boundary.tools.i18n` | `bb i18n:find/scan/missing/unused` |
| `boundary.tools.admin` | `bb create-admin` |
| `boundary.tools.deploy` | `bb deploy` (handles all 21 artifacts) |
| `boundary.tools.dev` | `bb migrate`, `bb check-links`, `bb smoke-check`, `bb install-hooks` |
| `boundary.tools.check-fcis` | `bb check:fcis` — FC/IS boundary enforcement (ADR-021) |
| `boundary.tools.check-tests` | `bb check:placeholder-tests` — placeholder assertion detection |
| `boundary.tools.check-deps` | `bb check:deps` — dependency direction linting + cycle detection |

### Releasing boundary-tools

```bash
# Bump version in boundary-tools/build.clj, then:
bb deploy boundary-tools

# Or directly:
cd boundary-tools && clojure -T:build clean && clojure -T:build deploy
```

For a full tagged Clojars release, remember that `.github/workflows/publish.yml`
runs `bb deploy --all`. That means every artifact included in the release must
have a fresh, unpublished version in its own `build.clj` before you push the
tag; the tag only triggers the workflow, it does not override artifact versions.

See `boundary-tools/AGENTS.md` for the full command reference.

---

## Detailed Documentation

**For in-depth information, see:**

- **[Documentation Index](dev-docs/content-readme.adoc)** - Main docs navigation
- **[Architecture Guide](docs/modules/architecture/pages/fc-is.adoc)** - FC/IS patterns, design decisions
- **[Module Scaffolding](libs/scaffolder/README.md)** - Complete scaffolding workflow
- **[MFA Setup Guide](docs/modules/guides/pages/authentication.adoc)** - Multi-factor authentication
- **[Observability Integration](docs/modules/libraries/pages/observability.adoc)** - Custom adapters, configuration
- **[HTTP Interceptors](dev-docs/adr/ADR-010-http-interceptor-architecture.adoc)** - Technical specification
- **[PRD](dev-docs/reference/boundary-prd.adoc)** - Product vision and requirements

---

**Last Updated**: 2026-03-27
**Version**: 5.1.0 (tenant membership management: ADR-016 — membership lifecycle, email invite flow, tenant-aware interceptors)
