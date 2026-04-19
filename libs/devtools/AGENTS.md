# libs/devtools — AGENTS.md

Development-only tools: REPL helpers, error pipeline, guidance engine, dev dashboard. Zero production overhead.

## Error Pipeline (Phase 3)

Layered pipeline: `classify -> enrich -> format -> output`

All pure functions in `core/`, side effects in `shell/`.

### Core Modules

| File | Purpose |
|------|---------|
| `core/error_classifier.clj` | Exception -> BND-xxx code (5 strategies: ex-data code, ex-data pattern, exception type, message regex, unclassified) |
| `core/error_enricher.clj` | Adds filtered stacktrace, suggestions, fix descriptor, URLs. Self-protected: sub-call failures omit the field |
| `core/error_formatter.clj` | Rich formatted output with BND code header, stack trace, fix hint. Also `format-unclassified-error` fallback |
| `core/stacktrace.clj` | Reorder stack traces: user code first, framework/JVM collapsed. Namespace prefix classification |
| `core/auto_fix.clj` | Pure fix descriptor registry. Maps BND codes to `{:fix-id :action :safe? :label}` |
| `core/error_codes.clj` | BND error catalog: BND-1xx (config), BND-2xx (validation), BND-3xx (persistence), BND-4xx (auth), BND-5xx (interceptor), BND-6xx (FC/IS) |

### Shell Modules

| File | Purpose |
|------|---------|
| `shell/repl_error_handler.clj` | `last-exception*` atom + `handle-repl-error!` — runs full pipeline |
| `shell/auto_fix.clj` | Executes fix descriptors: migrations, env vars, JWT, module wiring. Multimethod dispatch on `:action` |
| `shell/http_error_middleware.clj` | `wrap-dev-error-enrichment` — catches exceptions, attaches `:boundary/dev-info` to ex-data, re-throws |
| `shell/fcis_checker.clj` | Post-reset namespace scan for BND-601 (core imports shell). Runs after `(go)` and `(reset)` |

### REPL Commands

- `(fix!)` — Auto-fix last error. Safe fixes auto-apply, risky fixes always confirm
- `(fix! ex)` — Fix a specific exception

### Safety Model

| Safe? | Behavior at `:full` | Behavior at `:minimal` |
|-------|--------------------|-----------------------|
| `true` | Apply with message | Apply silently |
| `false` | Always confirm | Always confirm |

The safety gate is never overridden by guidance level.

## Guidance Engine (Phase 1)

- `core/guidance.clj` — Startup dashboard, post-scaffold guidance, contextual tips, command palette
- Levels: `:full` (default), `:minimal`, `:off`

## Introspection (Phase 2)

- `core/introspection.clj` — Route table, config tree (secrets redacted), module summary
- `core/schema_tools.clj` — Schema tree, diff, example generation
- `core/state_analyzer.clj` — Module/migration/test state analysis
- `core/documentation.clj` — In-REPL help topics
- `shell/repl.clj` — Route extraction, request simulation, data queries, test/lint runners

## Dev Dashboard (Phase 4)

Local web UI at `localhost:9999` providing x-ray vision into the running system.

### Pages

- `/dashboard` — System Overview: components, routes, modules, environment
- `/dashboard/routes` — Route Explorer: filterable route table with interceptor chain
- `/dashboard/requests` — Request Inspector: live request stream (HTMX polling 2s)
- `/dashboard/schemas` — Schema Browser: Malli schema tree with example generation
- `/dashboard/db` — Database Explorer: migrations, pool stats, query runner
- `/dashboard/errors` — Error Dashboard: BND-coded errors with fix suggestions

### Architecture

- Integrant component (`:boundary/dashboard`) starts Jetty on port 9999
- Server-rendered Hiccup + HTMX polling for live updates
- Request capture middleware wraps main HTTP handler (port 3000)
- Dark theme CSS in `resources/dashboard/assets/dashboard.css`
- All data access through existing introspection functions

### Key Files

- `shell/dashboard/server.clj` — Integrant component, Reitit router
- `shell/dashboard/middleware.clj` — Request capture middleware
- `shell/dashboard/layout.clj` — Sidebar, top bar, page wrapper
- `shell/dashboard/components.clj` — Reusable UI components
- `shell/dashboard/pages/*.clj` — Individual page renders
