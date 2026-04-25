# Boundary Framework

**Boundary** brings Django's productivity and Rails' conventions to Clojure — with functional programming rigor. It is a batteries-included web framework that enforces the **Functional Core / Imperative Shell (FC/IS)** pattern: pure business logic in `core/`, side effects in `shell/`, and clean interfaces through `ports.clj` protocols.

---

## Why Boundary?

**For developers:** 22 independently-publishable libraries on Clojars — use just `boundary-core` for validation utilities, or go full-stack with JWT + MFA auth, auto-generated CRUD UIs, background jobs, multi-tenancy, real-time WebSockets, and more. Every library follows the same FC/IS structure, making any Boundary codebase instantly familiar.

**Ship faster:** The scaffolder generates production-ready modules (entity + routes + tests) in seconds. The admin UI auto-generates CRUD interfaces from your schema — no manual forms. Built-in observability, RFC 5988 pagination, and declarative interceptors mean you write business logic, not plumbing. AI-powered tooling (`bb scaffold ai`, `bb ai gen-tests`, `bb ai sql`) accelerates common workflows.

**Production-ready:** Reference deployment configs (systemd, nginx, Fly.io, Render), an OWASP-aligned security checklist, scaling guides, health check endpoints, and zero-downtime migration patterns — everything you need to ship with confidence.

**Zero lock-in:** Each library is a standard `deps.edn` dependency. Swap what doesn't fit.

---

## Install Prerequisites

You need `curl`, `tar`, and Babashka (`bb`) for starter bootstrap.

**macOS**
```bash
brew install babashka
# curl and tar are preinstalled on macOS
```

**Linux (Debian/Ubuntu)**
```bash
sudo apt-get update
sudo apt-get install -y curl tar
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
```

**Windows (PowerShell + Scoop)**
```powershell
scoop install curl tar babashka
```

## AI Assistant Tooling

Boundary expects two assistant-friendly Clojure CLI helpers on `PATH`:

- `clj-nrepl-eval` for evaluating code in a running nREPL
- `clj-paren-repair` for repairing delimiter errors in edited Clojure files

The recommended source for both is
[`bhauman/clojure-mcp-light`](https://github.com/bhauman/clojure-mcp-light).
Despite the name, it is a small CLI toolset, not an MCP server.

Install them with `bbin`:

```bash
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 --as clj-nrepl-eval --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'
bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2 --as clj-paren-repair --main-opts '["-m" "clojure-mcp-light.paren-repair"]'
```

Verify the install:

```bash
clj-nrepl-eval --discover-ports
clj-paren-repair --help
```

---

## Quick Start

Get started with your Boundary project.

```bash
curl -fsSL https://raw.githubusercontent.com/thijs-creemers/boundary-starter/main/scripts/bootstrap.sh | bash
cd boundary-starter

bb setup
```

This downloads only starter essentials into `boundary-starter/`.

If you prefer to use the full repository, from the repo root:

```bash
export JWT_SECRET="change-me-dev-secret-min-32-chars"
export BND_ENV="dev"
clojure -M:repl-clj
```

You get: SQLite database (zero-config), HTTP server on port 3000, a complete Integrant system, REPL-driven development, and production-ready deployment templates.

---

## Documentation

| Resource | Description |
|----------|-------------|
| [Documentation](./docs/) | Architecture guides, tutorials, library reference (Antora) |
| [AGENTS.md](./AGENTS.md) | Commands, conventions, common pitfalls, debugging |
| [dev-docs/adr/](./dev-docs/adr/) | Architecture Decision Records |
| [Deployment Patterns](./docs/modules/guides/pages/deployment-patterns.adoc) | systemd, nginx, Fly.io, Render reference configs |
| [Migrations Guide](./docs/modules/guides/pages/migrations.adoc) | Zero-downtime schema change patterns |
| [Security Checklist](./dev-docs/security-checklist.adoc) | OWASP Top 10 aligned production checklist |
| [Scaling Guide](./dev-docs/scaling-guide.adoc) | JVM, HikariCP, Redis, and HTTP tuning |

Each library also has its own `AGENTS.md` with library-specific documentation.

---

## Libraries

Boundary is a monorepo of **22 independently publishable libraries** plus development tooling:

| Library | Description |
|---------|-------------|
| [core](libs/core/) | Foundation: validation, utilities, interceptor pipeline, feature flags |
| [observability](libs/observability/) | Logging, metrics, error reporting (Datadog, Sentry) |
| [platform](libs/platform/) | HTTP, database, CLI infrastructure |
| [user](libs/user/) | Authentication, authorization, MFA, session management |
| [admin](libs/admin/) | Auto-generated CRUD admin UI (Hiccup + HTMX) |
| [storage](libs/storage/) | File storage: local filesystem and S3 |
| [scaffolder](libs/scaffolder/) | Interactive module code generator |
| [cache](libs/cache/) | Distributed caching: Redis and in-memory |
| [jobs](libs/jobs/) | Background job processing with retry logic |
| [email](libs/email/) | Production-ready email: SMTP, async, jobs integration |
| [tenant](libs/tenant/) | Multi-tenancy with PostgreSQL schema-per-tenant isolation |
| [realtime](libs/realtime/) | WebSocket / SSE for real-time features |
| [external](libs/external/) | External service adapters: Twilio, IMAP |
| [payments](libs/payments/) | Payment provider abstraction: Stripe, Mollie, Mock |
| [reports](libs/reports/) | PDF, Excel, and Word (DOCX) generation via `defreport` |
| [calendar](libs/calendar/) | Recurring events, iCal export/import, conflict detection |
| [workflow](libs/workflow/) | Declarative state machine workflows with audit trail |
| [search](libs/search/) | Full-text search: PostgreSQL FTS with LIKE fallback for H2/SQLite |
| [geo](libs/geo/) | Geocoding (OSM/Google/Mapbox), DB cache, Haversine distance |
| [ai](libs/ai/) | Framework-aware AI tooling: NL scaffolding, error explainer, test generator, SQL copilot, docs wizard |
| [i18n](libs/i18n/) | Marker-based internationalisation with translation catalogues |
| [ui-style](libs/ui-style/) | Shared UI style bundles, design tokens, CSS/JS assets |
| [devtools](libs/devtools/) | Dev-only: error pipeline, dev dashboard, REPL power tools, guidance engine |
| [tools](libs/tools/) | Dev-only: deploy, doctor, setup, scaffolder integration, quality checks |

---

## Architecture

Boundary enforces the **Functional Core / Imperative Shell** pattern throughout:

```
libs/{library}/src/boundary/{library}/
├── core/       # Pure functions only — no I/O, no logging, no exceptions
├── shell/      # All side effects: persistence, services, HTTP handlers
├── ports.clj   # Protocol definitions (interfaces for dependency injection)
└── schema.clj  # Malli validation schemas
```

**Dependency rules (strictly enforced):**

- Shell → Core (allowed)
- Core → Ports (allowed)
- Core → Shell (**never** — this violates FC/IS)

This keeps business logic fast to test (no mocks needed), easy to reason about, and safe to refactor.

**Case conventions** — a frequent source of bugs:

| Boundary | Convention |
|----------|------------|
| Clojure code | `kebab-case` (`:password-hash`, `:created-at`) |
| Database | `snake_case` |
| API (JSON) | `camelCase` |

Use `boundary.core.utils.case-conversion` for conversions. Never convert manually.

---

## Essential Commands

```bash
# Testing (Kaocha, default test profile uses H2 in-memory DB)
clojure -M:test:db/h2                                          # All tests
clojure -M:test:db/h2 :core                                    # Single library
clojure -M:test:db/h2 --focus-meta :unit                       # Unit tests only
clojure -M:test:db/h2 --focus-meta :integration                # Integration tests only
clojure -M:test:db/h2 --watch :core                            # Watch mode
JWT_SECRET="dev-secret-32-chars-minimum" BND_ENV=test clojure -M:test:db/h2

# Linting
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test

# REPL (nREPL on port 7888)
clojure -M:repl-clj
# In REPL: (go) | (reset) | (halt)

# Build
clojure -T:build clean && clojure -T:build uber

# Database migrations
clojure -M:migrate up

# Scaffolding
bb scaffold   # Interactive module wizard
bb scaffold ai "product module with name, price, stock"  # NL scaffolding via AI (interactive confirm)
bb scaffold ai "product module with name, price, stock" --yes  # Non-interactive generation

# AI tooling
bb ai explain --file stacktrace.txt  # Explain error
bb ai gen-tests libs/user/src/boundary/user/core/validation.clj  # Generate tests
bb ai sql "find active users with orders in last 7 days"          # HoneySQL from NL
bb ai docs --module libs/user --type agents                       # Generate AGENTS.md

# Operations
bb doctor                          # Validate config for common mistakes
bb doctor --env all --ci           # Check all envs, exit non-zero (CI)
bb setup                           # Interactive config setup wizard
bb setup ai "PostgreSQL with Stripe payments"  # AI-powered config setup
bb deploy --all                    # Deploy all libraries to Clojars
bb deploy --missing                # Deploy only unpublished libraries
```

See [AGENTS.md](./AGENTS.md) for the complete command reference, common pitfalls, and debugging strategies.

### Running The Full Suite Against PostgreSQL

The default `test` profile runs against in-memory H2. There is currently no
dedicated `:db/pg` or `:test:db/postgres` alias in `deps.edn`.

To do one full run against PostgreSQL:

1. Start a disposable PostgreSQL instance that matches the credentials in
   [`resources/conf/test/config.edn`](./resources/conf/test/config.edn).
2. Temporarily move `:boundary/postgresql` from `:inactive` to `:active` in
   [`resources/conf/test/config.edn`](./resources/conf/test/config.edn), and
   move `:boundary/h2` out of `:active`.
3. Run:

```bash
BND_ENV=test JWT_SECRET="dev-secret-32-chars-minimum" clojure -M:migrate up
BND_ENV=test JWT_SECRET="dev-secret-32-chars-minimum" clojure -M:test:db/h2
```

4. Revert `resources/conf/test/config.edn` after the run so normal local and CI
   test runs keep using H2.

---

## Quality Gates

Six automated safeguards run in CI to catch regressions early. The FC/IS check also runs as a pre-commit hook.

```bash
bb check:fcis                    # Core namespaces must not import shell, I/O, logging, or DB
bb check:placeholder-tests       # No (is true) placeholders masking missing coverage
bb check:deps                    # Library dependency direction + cycle detection
clojure -M:test:db/h2 --focus-meta :security  # Error mapping, CSRF, XSS, SQL parameterization
```

See [ADR-021](./dev-docs/adr/ADR-021-fcis-boundary-rules.adoc) (FC/IS rules) and [ADR-022](./dev-docs/adr/ADR-022-error-handling-conventions.adoc) (error handling conventions) for rationale.

---

## Using Individual Libraries

```clojure
;; Validation utilities only
{:deps {org.boundary-app/boundary-core {:mvn/version "1.0.1-alpha-14"}}}

;; Full web application stack
{:deps {org.boundary-app/boundary-platform {:mvn/version "1.0.1-alpha-14"}
        org.boundary-app/boundary-user     {:mvn/version "1.0.1-alpha-14"}
        org.boundary-app/boundary-admin    {:mvn/version "1.0.1-alpha-14"}}}
```

---

## Deployment

Build the uberjar and deploy to any platform:

```bash
clojure -T:build clean && clojure -T:build uber
BND_ENV=prod java -jar target/boundary-*-standalone.jar
```

Reference configurations are provided under `resources/deploy/`:

| Template | Description |
|----------|-------------|
| [systemd](resources/deploy/systemd/) | Service unit + environment file for bare-metal/VM |
| [nginx](resources/deploy/nginx/) | Reverse proxy with TLS, WebSocket support, static caching |
| [Fly.io](resources/deploy/cloud/fly.toml) | Auto-scaling, health checks, Amsterdam region |
| [Render](resources/deploy/cloud/render.yaml) | Blueprint with managed PostgreSQL |

Health endpoints: `/health` (liveness), `/health/ready` (readiness with DB/cache checks), `/health/live` (container orchestrator).

See the [Deployment Patterns guide](./docs/modules/guides/pages/deployment-patterns.adoc) for full instructions.

---

## Website

https://boundary-app.org

---
## License

Copyright 2024–2026 Thijs Creemers. All rights reserved.
