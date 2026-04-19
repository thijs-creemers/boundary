# Dev Dashboard — Phase 4 Design Spec

**Date:** 2026-04-19
**Status:** Approved
**Parent spec:** `docs/superpowers/specs/2026-04-18-mindblowing-dx-design.md` (Phase 4)

## Summary

A local web dashboard at `localhost:9999` providing x-ray vision into the running Boundary system. Six pages: System Overview, Route Explorer, Request Inspector, Schema Browser, Database Explorer, and Error Dashboard. Dev-only, zero production overhead.

**Scope:** This spec covers the 6 pages listed in Phase 4 of the parent spec. The remaining 5 dashboard pages (Jobs & Queues, Config Editor, Web REPL, Git Worktree Manager, Security Status) are deferred to Phase 6.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Layout | Sidebar navigation | Consistent with admin UI patterns, room for future pages (phases 5-6) |
| Theming | Hybrid — ui-style JS + custom dark CSS | Shared HTMX/Alpine.js infra, distinct dev-tool visual identity |
| Data access | Server-rendered HTMX with Hiccup | Matches codebase patterns, simple, no separate API layer |
| Live updates | HTMX polling (2s interval) | Simple to implement, adequate for dev tool, upgradeable to SSE later |
| Architecture | Integrated Integrant component | Direct access to system map, single `(go)` starts everything |

## Architecture

### Integrant Component

The dashboard is wired as a `:boundary/dashboard` Integrant component. Configuration is assembled programmatically in `src/boundary/config.clj` (matching the existing pattern — no `#ig/ref` in EDN files):

```clojure
;; In src/boundary/config.clj — new dashboard-module-config function
(defn- dashboard-module-config [config]
  (when (= (:boundary/profile config) "dev")
    {:boundary/dashboard
     {:port            (get-in config [:active :boundary/dashboard :port] 9999)
      :http-handler    (ig/ref :boundary/http-handler)
      :db-context      (ig/ref :boundary/db-context)
      :router          (ig/ref :boundary/router)
      :logging         (ig/ref :boundary/logging)}}))

;; Added to ig-config merge chain
(defn ig-config [config]
  (merge (core-system-config config)
         ...existing modules...
         (dashboard-module-config config)))
```

The component starts a Ring/Jetty server on port 9999 with its own Reitit router. It receives specific component refs (not the whole system map) following the existing pattern. The system map is accessed via `integrant.repl.state/system` for full introspection when needed (same as `dev/repl/user.clj` does).

### Component Wiring

Init/halt methods live in `libs/devtools/src/boundary/devtools/shell/dashboard/server.clj`. The namespace is required from `dev/repl/user.clj` (not from platform wiring, since devtools is dev-only and not published to Clojars).

### Request Capture

A dev-only Ring middleware wraps the main HTTP handler (port 3000). It is inserted in `src/boundary/config.clj` within the `:boundary/http-handler` config, conditionally added only when `(:boundary/profile config)` is `"dev"`. The middleware captures request/response pairs into a shared bounded atom (last 200 entries, oldest dropped via simple `swap!` + size check — no ring buffer library needed):

```clojure
{:id        (random-uuid)
 :timestamp (Instant/now)
 :method    :get
 :path      "/api/users"
 :status    200
 :duration-ms 12
 :module    :user
 :handler   :user/list
 :request   {...}   ;; headers, params (sanitized — no passwords/tokens)
 :response  {...}}  ;; status, headers, body preview (truncated)
```

### Error Capture

Reuses the existing error pipeline from Phase 3. A bounded error log atom (last 100 errors) stores classified/enriched errors. The error dashboard reads from this atom.

### Data Flow

Each page calls pure introspection functions from `devtools/core/` that accept the system map and return data. Shell page handlers thread the system map through:

```
System map (Integrant refs)
  → core/introspection.clj  (routes, config, modules — exist from Phase 2)
  → core/schema_tools.clj   (schema tree, examples — exist from Phase 2)
  → shell/dashboard/pages/*  (Hiccup rendering)
```

## File Layout

### New files in `libs/devtools/`

```
libs/devtools/
├── src/boundary/devtools/
│   ├── shell/dashboard/
│   │   ├── server.clj           ;; Integrant component, Reitit router, port 9999
│   │   ├── middleware.clj       ;; Request capture middleware for main pipeline
│   │   ├── layout.clj           ;; Dashboard shell (sidebar, top bar) Hiccup
│   │   ├── components.clj       ;; Shared UI: stat cards, tables, badges, code blocks
│   │   └── pages/
│   │       ├── overview.clj     ;; System Overview
│   │       ├── routes.clj       ;; Route Explorer
│   │       ├── requests.clj     ;; Request Inspector
│   │       ├── schemas.clj      ;; Schema Browser
│   │       ├── database.clj     ;; Database Explorer
│   │       └── errors.clj       ;; Error Dashboard
├── test/boundary/devtools/
│   ├── core/                    ;; Unit tests for introspection, schema tools
│   └── shell/dashboard/
│       ├── server_test.clj      ;; Integration: pages return 200
│       ├── middleware_test.clj   ;; Integration: request capture
│       └── fragments_test.clj   ;; Integration: HTMX polling endpoints
└── resources/dashboard/
    └── assets/
        └── dashboard.css        ;; Dark theme CSS
```

### Modified files

- `src/boundary/config.clj` — add `dashboard-module-config` function, add to `ig-config` merge chain, add request capture middleware conditionally for dev profile
- `resources/conf/dev/config.edn` — add `:boundary/dashboard {:port 9999}` under `:active`
- `dev/repl/user.clj` — require `boundary.devtools.shell.dashboard.server` to register Integrant methods
- `tests.edn` — verify existing `:devtools` test suite entry

## Routing

### Page routes (port 9999)

```
GET /dashboard              → System Overview
GET /dashboard/routes       → Route Explorer
GET /dashboard/requests     → Request Inspector
GET /dashboard/schemas      → Schema Browser
GET /dashboard/db           → Database Explorer
GET /dashboard/errors       → Error Dashboard
GET /dashboard/assets/*     → Static CSS/JS/fonts (via ring.middleware.resource/wrap-resource
                               serving from classpath "dashboard/assets" for dashboard.css,
                               and from ui-style classpath resources for Alpine.js/HTMX/fonts)
```

### HTMX fragment endpoints (polling targets)

```
GET /dashboard/fragments/request-list    → Request stream (2s polling)
GET /dashboard/fragments/error-list      → Error stream (2s polling)
GET /dashboard/fragments/pool-status     → HikariCP pool stats (2s polling)
```

## Page Designs

### 1. System Overview (`/dashboard`)

Top row of 4 stat cards: Components (count + health), Routes (count + method breakdown), Modules (count + names), Errors in last hour.

Below: two-column layout with Integrant component list (name + status) on the left, environment info (profile, database, web URL, admin URL, nREPL port, guidance level, Java version) on the right.

### 2. Route Explorer (`/dashboard/routes`)

Filter bar: search input, module dropdown, method dropdown, route count.

Route table: method (color-coded badge), path, handler, module, "inspect" link.

Clicking "inspect" expands an interceptor chain visualization below the table — shows the full chain as a horizontal flow: `cors → content-type → auth → rate-limit → handler`.

"Try it" button on expanded route detail: pre-fills method and path, lets user add params/body, sends a simulated request via the existing `simulate` function from `devtools/shell/repl.clj`, and displays the response inline.

### 3. Request Inspector (`/dashboard/requests`)

Filter bar: path search, status filter, module filter. Live indicator showing polling status.

Request stream table: status (color-coded), method, path, handler, duration (yellow if >100ms), relative time.

Click a row to expand: split view with request body (left) and response body (right). Error responses highlighted with red border.

Polls `/dashboard/fragments/request-list` every 2 seconds via `hx-trigger="every 2s"`.

### 4. Schema Browser (`/dashboard/schemas`)

Two-column layout. Left: searchable schema list (monospace, active item highlighted). Right: schema detail.

Schema detail shows field tree with required markers (`*` red, `○` gray for optional), field name, and type. Below: tabbed section with "Example" (generated sample value) and "Validate" (paste data, see validation result) tabs.

### 5. Database Explorer (`/dashboard/db`)

Top row: two-column with migration status (list with applied/pending indicators) and HikariCP pool stats (active, idle, waiting, max — polls every 2s).

Below: table browser with dropdown to select table (showing row count), displays columns and sample rows.

Bottom: query runner with a textarea for HoneySQL or raw SQL input, execute button, and results table. Uses the existing `query` function from `devtools/shell/repl.clj`. Results limited to 50 rows.

### 6. Error Dashboard (`/dashboard/errors`)

Top row: 4 stat cards for error counts by category (total 24h, validation, persistence, FC/IS violations).

Error list: grouped by BND code with occurrence count badges, relative timestamps. Polls every 2s.

Click to expand: full error detail with message, suggested fix (REPL command + CLI command), and filtered stack trace (user code highlighted, framework frames dimmed).

## Dashboard Shell

### Sidebar (left)

- Dark background (`#1e293b`), collapsible via Alpine.js
- Logo: "Boundary Dev" with lightning bolt icon
- 6 nav items with icons, active state highlighted with accent color (`#38bdf8`)
- Collapse toggle for icon-only mode
- System status badge at bottom

### Top bar

- Current page title
- System status summary: `running · 12 components · 0 errors`
- Links to main app (`localhost:3000`) and admin (`localhost:3000/admin`)

### Content area

- Dark background (`#0f172a`), full width, scrollable
- Consistent card-based layout with `#1e293b` cards, `#334155` borders

### CSS

Custom `dashboard.css` in `libs/devtools/resources/dashboard/assets/`. Dark theme with:
- Background: `#0f172a` (content), `#1e293b` (cards/sidebar)
- Borders: `#334155`
- Text: `#e2e8f0` (primary), `#94a3b8` (secondary), `#64748b` (muted)
- Accent: `#38bdf8` (blue), `#22c55e`/`#4ade80` (green), `#f87171` (red), `#fbbf24` (yellow), `#a78bfa` (purple)
- Monospace: JetBrains Mono (from ui-style fonts)

JS from ui-style bundles: Alpine.js (sidebar collapse), HTMX (polling, fragment swaps).

## Testing Strategy

### Unit tests (`^:unit`)

- Introspection functions: route extraction, config redaction, module summary from mock system maps
- Hiccup rendering: pass mock data to page functions, assert expected HTML structure
- Extend existing `schema_tools_test.clj` if needed

### Integration tests (`^:integration`)

- `server_test.clj` — Dashboard starts on configured port, all 6 page routes return 200
- `middleware_test.clj` — Request capture middleware records entries, respects 200-entry buffer limit, sanitizes sensitive fields
- `fragments_test.clj` — HTMX polling endpoints return valid HTML fragments

### Test suite

`:devtools` entry in `tests.edn`, runnable with `clojure -M:test:db/h2 :devtools`.

## Verification Criteria

From the parent spec (Phase 4):

- Dashboard loads at `localhost:9999`; all pages render without errors
- Route explorer shows accurate route data matching `(routes)` output
- Request inspector shows live request stream
- Schema browser generates valid example values from Malli schemas
- All dashboard pages work after `(go)` and `(reset)`
- Database explorer shows migration status and pool stats
- Error dashboard shows errors captured by Phase 3 pipeline, grouped by BND code
- Route explorer "Try it" sends a simulated request and displays the response
- Database explorer query runner executes SQL and displays results
- `clojure -M:test:db/h2 :devtools` passes

## Existing Code to Leverage

- `devtools/core/introspection.clj` — route table, config tree, module summary (Phase 2)
- `devtools/core/schema_tools.clj` — schema tree, diff, examples (Phase 2)
- `devtools/core/error_codes.clj` — BND error catalog (Phase 3)
- `devtools/core/error_enricher.clj` — error enrichment pipeline (Phase 3)
- `devtools/shell/repl.clj` — request simulation, data queries (Phase 2)
- `shared/ui/core/layout.clj` — admin layout patterns to reference
- `shared/ui/core/components.clj` — admin UI components to reference
- `boundary.ui-style` — CSS/JS bundles
