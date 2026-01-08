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
# Testing
clojure -M:test:db/h2                              # All tests
clojure -M:test:db/h2 --watch --focus-meta :unit   # Watch unit tests

# Code Quality
clojure -M:clj-kondo --lint src test               # Lint codebase

# REPL Development
clojure -M:repl-clj                                # Start REPL
# In REPL:
(require '[integrant.repl :as ig-repl])
(ig-repl/go)                                       # Start system
(ig-repl/reset)                                    # Reload and restart

# Tools
clj-nrepl-eval --discover-ports                    # Find nREPL ports
clj-nrepl-eval -p <port> "<code>"                  # Evaluate code
clj-paren-repair <file>                            # Fix parentheses

# Build
clojure -T:build clean && clojure -T:build uber    # Build uberjar
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
src/boundary/{module}/
├── core/          # Pure business logic
├── shell/         # I/O, validation, adapters
├── ports.clj      # Protocol definitions
└── schema.clj     # Malli validation schemas
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
;; ❌ WRONG - snake_case in service layer
(defn login [user password]
  (verify-password password (:password_hash user)))  ; BUG!

;; ✅ CORRECT - kebab-case everywhere internally
(defn login [user password]
  (verify-password password (:password-hash user)))

;; ✅ CORRECT - Convert ONLY at persistence boundary
(defn db->entity [db-row]
  (snake-case->kebab-case-map db-row))  ; boundary.shared.core.utils.case-conversion
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

1. **Define schema** in `{module}/schema.clj`
2. **Write core logic** in `{module}/core/{domain}.clj` (pure functions)
3. **Write unit tests** in `test/{module}/core/{domain}_test.clj`
4. **Define port** in `{module}/ports.clj` (protocol)
5. **Implement in service** in `{module}/shell/service.clj`
6. **Add HTTP endpoint** in `{module}/shell/http.clj`

### Testing Workflow

```bash
# Watch mode while developing
clojure -M:test:db/h2 --watch --focus-meta :unit

# Full test suite before committing
clojure -M:test:db/h2

# Lint before committing
clojure -M:clj-kondo --lint src test
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
clj-paren-repair src/boundary/user/core/user.clj
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

## Module Scaffolding

Generate complete production-ready modules:

```bash
# Generate module with entity
clojure -M -m boundary.scaffolder.shell.cli-entry generate \
  --module-name product \
  --entity Product \
  --field name:string:required \
  --field sku:string:required:unique \
  --field price:decimal:required

# Generates:
# - 9 source files (core, shell, ports, schema, wiring)
# - 3 test files (unit, integration, contract)
# - 1 migration file
# - Zero linting errors, complete FC/IS architecture
```

**Integration Steps**: See [Module Scaffolding Guide](docs/guides/module-scaffolding.md)

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

## Security Features

### Multi-Factor Authentication (MFA)

**Status**: ✅ Production Ready

```bash
# Setup MFA
curl -X POST http://localhost:3000/api/auth/mfa/setup \
  -H "Authorization: Bearer <token>"

# Enable MFA
curl -X POST http://localhost:3000/api/auth/mfa/enable \
  -H "Authorization: Bearer <token>" \
  -d '{"secret": "...", "verificationCode": "123456"}'

# Login with MFA
curl -X POST http://localhost:3000/api/auth/login \
  -d '{"email": "user@example.com", "password": "...", "mfa-code": "123456"}'
```

**Details**: [MFA Setup Guide](docs/guides/mfa-setup.md)

---

## Observability

**Multi-Layer Interceptor Pattern**: Automatic logging, metrics, and error reporting without boilerplate.

```clojure
;; Service layer - use interceptor
(defn create-user [this user-data]
  (service-interceptors/execute-service-operation
   :create-user
   {:user-data user-data}
   (fn [{:keys [params]}]
     ;; Business logic here - observability automatic
     (let [user (user-core/prepare-user (:user-data params))]
       (.create-user repository user)))))

;; Result: Automatic breadcrumbs, error reporting, logging
```

**Providers**: No-op (development), Datadog, Sentry

---

## HTTP Interceptors

**Declarative cross-cutting concerns** (auth, rate limiting, audit):

```clojure
;; Define interceptor
(def require-admin
  {:name :require-admin
   :enter (fn [ctx]
            (if (admin? (get-in ctx [:request :session :user]))
              ctx
              (assoc ctx :response {:status 403 :body {:error "Forbidden"}})))})

;; Use in routes
[{:path "/api/admin"
  :methods {:post {:handler 'handlers/create-resource
                   :interceptors ['auth/require-admin
                                  'audit/log-action]}}}]
```

**Built-in**: Request logging, metrics, error reporting, correlation IDs

---

## Testing Strategy

| Category | Location | Purpose |
|----------|----------|---------|
| **Unit** | `test/{module}/core/*` | Pure functions, no mocks |
| **Integration** | `test/{module}/shell/*` | Service with mocked deps |
| **Contract** | `test/{module}/shell/*` | Adapters with real DB |

```bash
clojure -M:test:db/h2 --focus-meta :unit        # Fast, no I/O
clojure -M:test:db/h2 --focus-meta :integration # Mocked I/O
clojure -M:test:db/h2 --focus-meta :contract    # Real database
```

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

## Detailed Documentation

**For in-depth information, see:**

- **[Full Agent Guide](docs/AGENTS_FULL.md)** - Complete 2,774-line reference
- **[Architecture Guide](https://github.com/thijs-creemers/boundary-docs/tree/main/content/architecture/)** - FC/IS patterns, design decisions
- **[Module Scaffolding](docs/guides/module-scaffolding.md)** - Complete scaffolding workflow
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
║ TEST    │ clojure -M:test:db/h2 --watch --focus-meta :unit     ║
║ LINT    │ clojure -M:clj-kondo --lint src test                 ║
║ REPL    │ clojure -M:repl-clj                                  ║
║         │ (ig-repl/go)    (ig-repl/reset)    (ig-repl/halt)    ║
║ BUILD   │ clojure -T:build clean && clojure -T:build uber      ║
║ REPAIR  │ clj-paren-repair <files>  # Fix parentheses          ║
║ EVAL    │ clj-nrepl-eval -p <port> "<code>"  # REPL eval       ║
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

**Last Updated**: 2026-01-08
**Version**: 2.0.0 (Streamlined)
