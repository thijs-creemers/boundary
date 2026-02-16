# Boundary Framework - AI Agent Quick Reference

**AI Agent Quick Reference**: Essential patterns, commands, and conventions for working effectively with the Boundary Framework.

> **🛑 CRITICAL REMINDERS - READ THESE FIRST**
>
> **GIT OPERATIONS - REQUIRE EXPLICIT PERMISSION:**
> - ❌ NEVER stage, commit, or push without explicit user permission
> - ✅ ALWAYS show changes and ASK before committing
>
> **CODE EDITING:**
> - Use `clj-paren-repair` to fix unbalanced parentheses (never manually repair)
> - Use `clj-nrepl-eval` for REPL evaluation during development
> - **CRITICAL**: Always use kebab-case internally; convert snake_case/camelCase ONLY at system boundaries
> - All documentation must be accurate and in English

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

# Testing - By metadata category
clojure -M:test:db/h2 --focus-meta :unit           # Unit tests only
clojure -M:test:db/h2 --focus-meta :integration    # Integration tests
clojure -M:test:db/h2 --focus-meta :contract       # Database contract tests

# Testing - Watch mode and specific namespaces
clojure -M:test:db/h2 --watch :core                # Watch core library tests
clojure -M:test:db/h2 --focus boundary.core.validation-test  # Single namespace

# Update validation snapshots
UPDATE_SNAPSHOTS=true clojure -M:test:db/h2 --focus boundary.user.core.user-validation-snapshot-test

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
clojure -M:migrate migrate                         # Run migrations
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
└── external/      # External service adapters (In Development)
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
(require '[boundary.shared.core.utils.case-conversion :as cc])

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
   tail -100 logs/boundary.log | grep -A 10 "ERROR"
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

## Testing Strategy

| Category | Location | Purpose |
|----------|----------|---------|
| **Unit** | `libs/{library}/test/{library}/core/*` | Pure functions, no mocks |
| **Integration** | `libs/{library}/test/{library}/shell/*` | Service with mocked deps |
| **Contract** | `libs/{library}/test/{library}/shell/*` | Adapters with real DB |

```bash
# Run all tests
clojure -M:test:db/h2

# Run specific library tests
clojure -M:test:db/h2 :core                        # Core library
clojure -M:test:db/h2 :user                        # User library
clojure -M:test:db/h2 :platform                    # Platform library

# Run by metadata
clojure -M:test:db/h2 --focus-meta :unit           # Fast, no I/O
clojure -M:test:db/h2 --focus-meta :integration    # Mocked I/O
clojure -M:test:db/h2 --focus-meta :contract       # Real database
```

### Validation Snapshot Testing

The framework includes snapshot-based validation testing:

```bash
# Generate validation graph (requires graphviz)
clojure -M:repl-clj <<'EOF'
(require '[boundary.shared.tools.validation.repl :as v])
(spit "build/validation-user.dot" (v/rules->dot {:modules #{:user}}))
(System/exit 0)
EOF

dot -Tpng build/validation-user.dot -o docs/diagrams/validation-user.png

# View coverage reports
cat test/reports/coverage/user.txt
cat test/reports/coverage/user.edn
```

### Test Database Configuration

- **Development**: SQLite (`dev-database.db`)
- **Testing**: H2 in-memory (configured via `:test` alias)
- All database drivers loaded via `:db` alias

---

## Troubleshooting

### System Won't Start

```bash
rm -rf .cpcache target
clojure -M:repl-clj
```

### Tests Failing

```bash
# Run unit tests only (should always pass)
clojure -M:test:db/h2 --focus-meta :unit

# Verbose output
clojure -M:test:db/h2 -n boundary.user.core.user-test --reporter documentation
```

### Parenthesis Errors

```bash
# NEVER fix manually, always use the tool
clj-paren-repair <file>
```

### defrecord Not Updating

```clojure
;; In REPL
(ig-repl/halt)
(ig-repl/go)  ; Fresh start
```

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
| **tenant** | [`libs/tenant/AGENTS.md`](libs/tenant/AGENTS.md) | Multi-tenancy, schema-per-tenant, provisioning, lifecycle states |
| **realtime** | [`libs/realtime/AGENTS.md`](libs/realtime/AGENTS.md) | WebSocket messaging, JWT auth, pub/sub, message routing |
| **external** | [`libs/external/AGENTS.md`](libs/external/AGENTS.md) | External service adapters (skeleton - in development) |

---

## Detailed Documentation

**For in-depth information, see:**

- **[Full Agent Guide](docs/archive/AGENTS_FULL.md)** - Legacy comprehensive reference (pre-library-split)
- **[Architecture Guide](https://github.com/thijs-creemers/boundary-docs/tree/main/content/architecture/)** - FC/IS patterns, design decisions
- **[Module Scaffolding](libs/scaffolder/README.md)** - Complete scaffolding workflow
- **[MFA Setup Guide](docs/guides/mfa-setup.md)** - Multi-factor authentication
- **[API Pagination](docs/API_PAGINATION.md)** - Offset and cursor pagination
- **[Observability Integration](https://github.com/thijs-creemers/boundary-docs/tree/main/content/guides/integrate-observability.adoc)** - Custom adapters, configuration
- **[HTTP Interceptors](https://github.com/thijs-creemers/boundary-docs/tree/main/content/adr/ADR-010-http-interceptor-architecture.adoc)** - Technical specification
- **[PRD](https://github.com/thijs-creemers/boundary-docs/tree/main/content/reference/boundary-prd.adoc)** - Product vision and requirements

---

## Quick Reference Card

```
╔════════════════════════════════════════════════════════════════╗
║                  BOUNDARY FRAMEWORK CHEAT SHEET                ║
╠════════════════════════════════════════════════════════════════╣
║ TEST    │ clojure -M:test:db/h2                 # All tests    ║
║         │ clojure -M:test:db/h2 :core           # Core library ║
║         │ clojure -M:test:db/h2 :user           # User library ║
║         │ clojure -M:test:db/h2 --watch :core   # Watch mode   ║
║ LINT    │ clojure -M:clj-kondo --lint libs/*/src               ║
║ REPL    │ clojure -M:repl-clj                                  ║
║         │ (ig-repl/go)    (ig-repl/reset)    (ig-repl/halt)    ║
║ BUILD   │ clojure -T:build clean && clojure -T:build uber      ║
║ REPAIR  │ clj-paren-repair <files>  # Fix parentheses          ║
║ EVAL    │ clj-nrepl-eval -p <port> "<code>"  # REPL eval       ║
╠════════════════════════════════════════════════════════════════╣
║ LIBS    │ core, observability, platform, user, admin, storage, ║
║         │ scaffolder, cache, jobs, email, tenant, realtime      ║
╠════════════════════════════════════════════════════════════════╣
║ CORE    │ Pure functions only, no side effects                 ║
║ SHELL   │ All I/O, validation, error handling                  ║
║ PORTS   │ Protocol definitions (abstractions)                  ║
║ SCHEMA  │ Malli schemas for validation                         ║
╠════════════════════════════════════════════════════════════════╣
║ ALWAYS  │ Use kebab-case internally (ALL code)                 ║
║ NEVER   │ Use snake_case except at DB boundary                 ║
║ NEVER   │ Put business logic in shell layer                    ║
║ NEVER   │ Make core depend on shell                            ║
╚════════════════════════════════════════════════════════════════╝
```

---

**Last Updated**: 2026-02-14
**Version**: 3.0.0 (Library AGENTS.md Split)
