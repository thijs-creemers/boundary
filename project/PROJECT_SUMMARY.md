# Boundary Framework — Developer Summary

A module-centric Clojure framework implementing the Functional Core / Imperative Shell (FC/IS) pattern.
Each domain module owns its complete functionality stack: pure business logic in `core/`, side effects in `shell/`, protocol interfaces in `ports.clj`.

---

## Architecture

### Module layout

```
libs/{library}/src/boundary/{library}/
├── core/       # Pure functions only — no I/O, no logging, no exceptions
├── shell/      # All side effects: persistence, services, HTTP handlers
├── ports.clj   # Protocol definitions (interfaces for dependency injection)
└── schema.clj  # Malli validation schemas
```

### Dependency rules

| Direction | Allowed |
|-----------|---------|
| Shell → Core | Yes |
| Core → Ports | Yes |
| Shell → Adapters | Yes |
| Core → Shell | **Never** |
| Core → Adapters | **Never** |

### Case conventions

| Boundary | Convention |
|----------|------------|
| Clojure code | `kebab-case` |
| Database | `snake_case` |
| API (JSON) | `camelCase` |

Use `boundary.shared.core.utils.case-conversion`. Never convert manually.

---

## Libraries (22)

| Library | Description |
|---------|-------------|
| `core` | Validation, utilities, interceptor pipeline, feature flags |
| `observability` | Logging, metrics, error reporting |
| `platform` | HTTP, database, CLI infrastructure |
| `i18n` | Internationalization and locale management |
| `user` | JWT auth, MFA, session management |
| `admin` | Auto-CRUD admin UI (Hiccup + HTMX) |
| `storage` | Local and S3 file storage |
| `scaffolder` | Interactive module generator |
| `cache` | Redis and in-memory caching |
| `jobs` | Background job processing |
| `email` | SMTP, async, jobs integration |
| `tenant` | Multi-tenancy, PostgreSQL schema-per-tenant |
| `realtime` | WebSocket / SSE |
| `external` | Twilio SMS/WhatsApp, SMTP transport, IMAP adapters |
| `payments` | PSP abstraction (Mollie, Stripe, Mock), checkout flow, webhooks |
| `geo` | Multi-provider geocoding (OSM/Google/Mapbox), caching, distance |
| `reports` | PDF / Excel / DOCX via `defreport` |
| `calendar` | Recurring events, iCal, conflict detection |
| `workflow` | State machine workflows with audit trail |
| `search` | Full-text search (PostgreSQL FTS / LIKE fallback) |
| `ai` | Multi-provider AI (Ollama/Anthropic/OpenAI), NL scaffolding, tools |
| `ui-style` | Shared UI style bundles, design tokens, CSS/JS assets |

Additionally, `boundary-tools/` provides developer tooling (scaffolding, AI assistance, i18n management, deploy) and is published separately.

---

## Key Technologies

| Category | Libraries |
|----------|-----------|
| Language | Clojure 1.12.4 |
| DI / lifecycle | Integrant |
| Configuration | Aero |
| HTTP | Ring, Reitit |
| Database | next.jdbc, HoneySQL, HikariCP |
| Validation | Malli |
| Auth | Buddy (JWT) |
| UI | Hiccup, HTMX |
| Tests | Kaocha |
| Lint | clj-kondo |

---

## Essential Commands

```bash
# Tests
clojure -M:test:db/h2                       # All tests
clojure -M:test:db/h2 :user                 # Single library
clojure -M:test:db/h2 --focus-meta :unit    # Unit tests only
clojure -M:test:db/h2 --watch :core         # Watch mode

# REPL (nREPL port 7888)
clojure -M:repl-clj
# (ig-repl/go) | (ig-repl/reset) | (ig-repl/halt)

# Lint
clojure -M:clj-kondo --lint src test libs/*/src libs/*/test

# Build
clojure -T:build clean && clojure -T:build uber

# Migrations
clojure -M:migrate up

# Scaffolding
bb scaffold
```

---

## Further Reading

| Resource | Description |
|----------|-------------|
| [README.md](../README.md) | Public overview and quick start |
| [AGENTS.md](../AGENTS.md) | Full command reference, pitfalls, debugging |
| [PROJECT_STATUS.adoc](PROJECT_STATUS.adoc) | Current library and CI status |
| [docs-site/](../docs-site/) | Architecture guides, tutorials, API reference |
