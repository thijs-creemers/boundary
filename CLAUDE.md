# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
# Testing (Kaocha runner, H2 in-memory DB)
clojure -M:test:db/h2                                    # All tests
clojure -M:test:db/h2 :core                              # Single library (core, user, platform, admin, etc.)
clojure -M:test:db/h2 --focus-meta :unit                 # By metadata (:unit, :integration, :contract)
clojure -M:test:db/h2 --focus boundary.core.validation-test  # Single namespace
clojure -M:test:db/h2 --watch :core                      # Watch mode
JWT_SECRET="dev-secret-32-chars-minimum" clojure -M:test:db/h2  # When user/auth tests need JWT

# Linting
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test

# REPL (nREPL on port 7888)
clojure -M:repl-clj
# Then: (require '[integrant.repl :as ig-repl]) (ig-repl/go) / (ig-repl/reset) / (ig-repl/halt)

# Build uberjar
clojure -T:build clean && clojure -T:build uber

# Database migrations
clojure -M:migrate migrate

# Parenthesis repair (never fix manually)
clj-paren-repair <file>
```

## Architecture: Functional Core / Imperative Shell

This is a Clojure monorepo with 12 independently publishable libraries under `libs/`. Each library follows the FC/IS pattern:

```
libs/{library}/src/boundary/{library}/
├── core/       # Pure functions ONLY - no I/O, no logging, no exceptions
├── shell/      # All side effects: persistence, services, HTTP handlers
├── ports.clj   # Protocol definitions (interfaces)
└── schema.clj  # Malli validation schemas
```

**Dependency rules (strictly enforced):**
- Shell → Core (allowed)
- Core → Ports (allowed)
- Core → Shell (NEVER - this violates FC/IS)

**Libraries:** core, observability, platform, user, admin, storage, scaffolder, cache, jobs, email, tenant, realtime

The main application source in `src/boundary/` follows the same core/shell structure. The `examples/` directory contains reference applications (ecommerce-api is the most comprehensive).

## Critical Conventions

**Case conversion** - a frequent source of bugs:
- All Clojure code: **kebab-case** (`:password-hash`, `:created-at`)
- Database boundary only: snake_case
- API boundary only: camelCase
- Use `boundary.shared.core.utils.case-conversion` for conversions

**Adding new fields checklist** - always synchronize:
1. Malli schema in `schema.clj`
2. Database column (migration)
3. Persistence layer transformations in `shell/persistence.clj`

**Configuration:** Aero-based configs in `resources/conf/{dev,test,prod,acc}/config.edn`. System lifecycle managed by Integrant.

## Key Technologies

Clojure 1.12.4, Integrant (DI/lifecycle), Aero (config), Ring/Reitit (HTTP), next.jdbc + HoneySQL (DB), HikariCP (pool), Malli (validation), Buddy (auth/JWT), Hiccup + HTMX (UI), Kaocha (tests), clj-kondo (linting)

## Testing Strategy

- **Unit tests** (`^:unit`): Pure core functions, no mocks
- **Integration tests** (`^:integration`): Shell services with mocked adapters
- **Contract tests** (`^:contract`): Adapters against real DB (H2 in-memory)

Test suites are defined in `tests.edn`. Each library has its own `:id` for isolated runs.

## Library-Specific Guides

Each library has its own `AGENTS.md` with library-specific documentation:
- `libs/core/AGENTS.md` - Validation, case conversion, interceptor pipeline, feature flags
- `libs/observability/AGENTS.md` - Interceptor patterns
- `libs/platform/AGENTS.md` - HTTP interceptor architecture
- `libs/user/AGENTS.md` - MFA/security features
- `libs/admin/AGENTS.md` - UI/Frontend, entity config, form/HTMX pitfalls
- `libs/storage/AGENTS.md` - File storage (local/S3), image processing
- `libs/scaffolder/AGENTS.md` - Module generation
- `libs/cache/AGENTS.md` - Distributed caching, TTL, atomic ops
- `libs/jobs/AGENTS.md` - Background job processing, retry logic
- `libs/email/AGENTS.md` - SMTP sending, async/queued modes
- `libs/tenant/AGENTS.md` - Multi-tenancy, schema-per-tenant
- `libs/realtime/AGENTS.md` - WebSocket messaging, pub/sub
- `libs/external/AGENTS.md` - External service adapters (skeleton)

## Further Reading

See `AGENTS.md` for detailed development workflows, common pitfalls (8 documented patterns), debugging strategies, and general conventions.
