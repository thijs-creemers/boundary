# Boundary Framework

**Boundary** brings Django's productivity and Rails' conventions to Clojure — with functional programming rigor. It is a batteries-included web framework that enforces the **Functional Core / Imperative Shell (FC/IS)** pattern: pure business logic in `core/`, side effects in `shell/`, and clean interfaces through `ports.clj` protocols.

---

## Why Boundary?

**For developers:** 19 independently-publishable libraries on Clojars — use just `boundary-core` for validation utilities, or go full-stack with JWT + MFA auth, auto-generated CRUD UIs, background jobs, multi-tenancy, real-time WebSockets, and more. Every library follows the same FC/IS structure, making any Boundary codebase instantly familiar.

**Ship faster:** The scaffolder generates production-ready modules (entity + routes + tests) in seconds. The admin UI auto-generates CRUD interfaces from your schema — no manual forms. Built-in observability, RFC 5988 pagination, and declarative interceptors mean you write business logic, not plumbing.

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

---

## Quick Start

Get started with your Boundary project.

```bash
curl -fsSL https://raw.githubusercontent.com/thijs-creemers/boundary/main/starter/scripts/bootstrap.sh | bash
cd boundary-starter

bb setup
```

This downloads only starter essentials into `boundary-starter/`.

If you prefer to use the full repository, from the repo root:

```bash
export JWT_SECRET="change-me-dev-secret-min-32-chars"
export BND_ENV="development"
clojure -M:repl-clj
```

You get: SQLite database (zero-config), HTTP server on port 3000, a complete Integrant system, REPL-driven development, and a production-ready Dockerfile.

---

## Documentation

| Resource | Description |
|----------|-------------|
| [Documentation](./docs-site/) | Architecture guides, tutorials, API reference, ADRs |
| [AGENTS.md](./AGENTS.md) | Commands, conventions, common pitfalls, debugging |
| [Publishing Guide](./docs-site/content/reference/publishing.md) | Publishing libraries to Clojars |

Each library also has its own `AGENTS.md` with library-specific documentation.

---

## Libraries

Boundary is a monorepo of **19 independently publishable libraries**:

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
| [external](libs/external/) | External service adapters: Stripe, Twilio, IMAP |
| [reports](libs/reports/) | PDF, Excel, and Word (DOCX) generation via `defreport` |
| [calendar](libs/calendar/) | Recurring events, iCal export/import, conflict detection |
| [workflow](libs/workflow/) | Declarative state machine workflows with audit trail |
| [search](libs/search/) | Full-text search: PostgreSQL FTS with LIKE fallback for H2/SQLite |
| [geo](libs/geo/) | Geocoding (OSM/Google/Mapbox), DB cache, Haversine distance |
| [ai](libs/ai/) | Framework-aware AI tooling: NL scaffolding, error explainer, test generator, SQL copilot, docs wizard |

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

Use `boundary.shared.core.utils.case-conversion` for conversions. Never convert manually.

---

## Essential Commands

```bash
# Testing (Kaocha, H2 in-memory DB)
clojure -M:test:db/h2                                    # All tests
clojure -M:test:db/h2 :core                              # Single library
clojure -M:test:db/h2 --focus-meta :unit                 # Unit tests only
clojure -M:test:db/h2 --focus-meta :integration          # Integration tests only
clojure -M:test:db/h2 --watch :core                      # Watch mode
JWT_SECRET="dev-secret-32-chars-minimum" clojure -M:test:db/h2

# Linting
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test

# REPL (nREPL on port 7888)
clojure -M:repl-clj
# In REPL: (ig-repl/go) | (ig-repl/reset) | (ig-repl/halt)

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
```

See [AGENTS.md](./AGENTS.md) for the complete command reference, common pitfalls, and debugging strategies.

---

## Installing Prerequisites

**macOS**
```bash
brew install openjdk clojure/tools/clojure
```

**Linux (Debian/Ubuntu)**
```bash
sudo apt-get install -y openjdk-17-jdk
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh && sudo ./linux-install.sh
```

**Linux (RHEL/Fedora)**
```bash
sudo dnf install -y java-17-openjdk java-17-openjdk-devel
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh && sudo ./linux-install.sh
```

**Windows**
```powershell
scoop bucket add java && scoop install openjdk17
scoop bucket add scoop-clojure && scoop install clojure
```

---

## Using Individual Libraries

```clojure
;; Validation utilities only
{:deps {org.boundary-app/boundary-core {:mvn/version "1.0.0-alpha"}}}

;; Full web application stack
{:deps {org.boundary-app/boundary-platform {:mvn/version "1.0.0-alpha"}
        org.boundary-app/boundary-user     {:mvn/version "1.0.0-alpha"}
        org.boundary-app/boundary-admin    {:mvn/version "1.0.0-alpha"}}}
```

---

## License

Copyright 2024–2026 Thijs Creemers. All rights reserved.
