# DX Vision Phase 5: Advanced REPL

**Date:** 2026-04-19
**Status:** Draft
**Author:** Thijs Creemers + Claude
**Parent spec:** `docs/superpowers/specs/2026-04-18-mindblowing-dx-design.md` (Phase 5 section)

## Context

Phases 1-4 built the devtools foundation: error pipeline (classify → enrich → format → fix), REPL helpers (routes, simulate, query, schema, trace, fix!), guidance engine, and a 7-page dev dashboard. Phase 5 adds advanced REPL features that make the REPL a full development cockpit — time-travel debugging, runtime route injection, request tapping, component hot-swap, and schema-driven module generation.

## Scope

All 6 features from the parent spec's Phase 5:

1. `(recording)` — time-travel debugging (start/stop/replay/diff/save/load)
2. `(prototype!)` — schema-driven full-module generation
3. `(restart-component)` — Integrant component hot-swap
4. `(defroute!)` — runtime route addition
5. `(tap-handler!)` / `(untap-handler!)` — request interception
6. `(scaffold!)` — REPL wrapper around scaffolder

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Recording persistence | File-based (`.boundary/recordings/*.edn`) | Survives restarts, barely more complex than in-memory |
| `prototype!` generation | Delegate to `libs/scaffolder/` core | Scaffolder core is pure Clojure, JVM-compatible, avoids duplication |
| `defroute!` injection | Rebuild Reitit router | Dynamic routes get full interceptor support as first-class citizens |
| `tap-handler!` strategy | Interceptor injection via router rebuild | Shares router rebuild infra, gives access to full interceptor context |

---

## Architecture

### New Files

```
libs/devtools/src/boundary/devtools/
├── core/
│   ├── recording.clj       # Pure: session data, filtering, diffing, serialization
│   ├── router.clj           # Pure: route tree manipulation, interceptor injection
│   └── prototype.clj        # Pure: build scaffolder context from prototype spec
└── shell/
    ├── recording.clj        # Stateful: atom management, file I/O, middleware install
    ├── router.clj            # Stateful: router atom swap, dynamic route/tap tracking
    └── prototype.clj         # Effectful: file writes, migration, system reset
```

### Modified Files

- `shell/repl.clj` — add `restart-component`, `scaffold!` functions
- `dev/repl/user.clj` — expose new helpers to REPL namespace
- `.gitignore` — add `.boundary/recordings/`

### Shared Router Rebuild Infrastructure

`defroute!`, `tap-handler!`, and `recording` all need to rebuild the Reitit router. A shared `shell/router.clj` provides:

- Reference to the running system's router (via Integrant system map)
- `rebuild-router!` — takes current route tree, applies modifications, compiles + swaps
- `dynamic-routes` atom — tracks routes added via `defroute!`
- `taps` atom — tracks active handler taps
- On `(reset)`, all dynamic modifications clear — these are ephemeral dev aids

**Platform integration:** If `libs/platform/` stores the router in a compiled closure (not swappable), a small change is needed to wrap it in an atom or derefable. This will be verified during implementation.

---

## Feature Details

### 1. Recording (Time-Travel Debugging)

**Data model:**
```clojure
{:id "auth-flow"
 :entries [{:idx 0
            :request {:method :post :uri "/api/users" :body {...} :headers {...}}
            :response {:status 201 :body {...} :headers {...}}
            :duration-ms 42
            :timestamp #inst "..."}
           ...]
 :started-at #inst "..."
 :stopped-at #inst "..."}
```

**API:**
```clojure
(recording :start)                    ; Install capture middleware, start session
(recording :stop)                     ; Remove middleware, freeze session
(recording :list)                     ; Print captured requests as table
(recording :replay 3)                 ; Replay entry #3 via simulate
(recording :replay 3 {:email "x"})   ; Replay with modified body (deep-merge)
(recording :diff 3 5)                 ; Diff two entries (colored add/remove)
(recording :save "auth-flow")         ; Write to .boundary/recordings/auth-flow.edn
(recording :load "auth-flow")         ; Read back, set as active session
```

**Implementation:**
- `(recording :start)` installs a Ring middleware via router rebuild that captures full request/response pairs into a session atom
- `(recording :stop)` removes the middleware, freezes the session
- `(recording :replay N)` takes entry N and runs it through the existing `simulate` function in `repl.clj`
- `(recording :diff M N)` uses a pure data diff on both request and response maps, formatted with colored additions/removals
- Save/load uses EDN with `pr-str` / `edn/read-string` to `.boundary/recordings/`

**Core layer (`core/recording.clj`):**
- `create-session` — empty session with timestamp
- `add-entry` — append captured request/response to session
- `get-entry` — retrieve by index with bounds check
- `merge-request-modifications` — deep-merge user overrides into a captured request
- `diff-entries` — produce a structured diff of two entries
- `format-entry-table` — format entries as printable table
- `serialize-session` / `deserialize-session` — EDN round-trip

**Shell layer (`shell/recording.clj`):**
- `active-session` atom — current recording session
- `capture-middleware` — Ring middleware that captures into the atom
- `start-recording!` — installs middleware via `router/rebuild-router!`
- `stop-recording!` — removes middleware, freezes session
- `replay-entry!` — delegates to `repl/simulate-request`
- `save-session!` / `load-session!` — file I/O to `.boundary/recordings/`

### 2. Router Rebuild Infrastructure

**Core layer (`core/router.clj`):**
- `add-route` — merge a route definition `[method path handler-map]` into a route tree
- `remove-route` — remove by method + path
- `inject-tap-interceptor` — add a `:devtools/tap` interceptor to a handler's chain (by handler keyword)
- `remove-tap-interceptor` — remove it
- `inject-capture-interceptor` — add recording capture interceptor

All pure data transformations on Reitit route data structures.

**Shell layer (`shell/router.clj`):**
- `system-router-ref` — gets the router reference from the running Integrant system
- `dynamic-routes` atom — `{[:get "/api/test"] handler-map}`
- `taps` atom — `{:create-user callback-fn}`
- `rebuild-router!` — applies dynamic-routes + taps + recording middleware to the base route tree, compiles new Reitit router, swaps atom
- `clear-dynamic-state!` — called on `(reset)`, clears atoms

### 3. `(defroute!)`

```clojure
(defroute! :get "/api/test" (fn [req] {:status 200 :body {:hello "world"}}))
(remove-route! :get "/api/test")
(dynamic-routes)                     ; List injected routes
```

- Stores route in `dynamic-routes` atom
- Calls `rebuild-router!`
- Prints confirmation with full route path
- `(dynamic-routes)` lists all currently injected routes

### 4. `(tap-handler!)` / `(untap-handler!)`

```clojure
(tap-handler! :create-user (fn [ctx] (println "Request:" (:request ctx)) ctx))
(untap-handler! :create-user)
(taps)                               ; List active taps
```

- Stores tap in `taps` atom
- Calls `rebuild-router!` to inject a `:devtools/tap` interceptor at the start of the handler's interceptor chain
- The tap interceptor calls the user's callback on `:enter`, passing the full interceptor context
- Callback must return the context (identity is fine; modifying is advanced usage)
- `(taps)` lists active taps

### 5. `(restart-component)`

```clojure
(restart-component :boundary/http-server)
```

- Validates the key exists in the current system map
- Calls `ig/halt-key!` then `ig/init-key` with current config
- Updates the running system atom
- Prints component status after restart
- On invalid key: prints error with list of available component keys

Intentionally simple — thin wrapper around Integrant.

### 6. `(scaffold!)`

```clojure
(scaffold! "invoice" {:fields {:customer [:string {:min 1}]
                               :amount   [:decimal {:min 0}]}})
```

- Calls `boundary.scaffolder.core.generators` functions directly (JVM-compatible)
- Builds template context from name + field spec
- Writes generated files to `libs/<name>/`
- Does NOT auto-integrate (explicit step via `bb scaffold integrate`)
- Does NOT run migration or reset
- Prints post-scaffold guidance via `core/guidance.clj`

### 7. `(prototype!)`

```clojure
(prototype! :invoice
  {:fields {:customer [:string {:min 1}]
            :amount   [:decimal {:min 0}]
            :status   [:enum [:draft :sent :paid]]
            :due-date :date}
   :endpoints [:crud :list :search]})
```

Higher-level than `scaffold!` — the "zero to working module" experience:

1. Delegates to `scaffold!` for file generation
2. Generates a migration file via `generate-migration-file`
3. Runs the migration
4. Calls `(reset)` to load the new module
5. Prints summary: generated files, available routes, sample `(simulate)` command

**Core layer (`core/prototype.clj`):**
- `build-scaffold-context` — maps prototype spec to scaffolder template context
- `endpoints-to-generators` — maps `:endpoints` keywords (`:crud`, `:list`, `:search`) to generator function calls
- `build-migration-spec` — converts field spec to migration column definitions

**Shell layer (`shell/prototype.clj`):**
- `prototype!` — orchestrates: generate → migrate → reset → print summary

---

## User.clj REPL API Summary

New functions exposed in `dev/repl/user.clj`:

```clojure
;; Recording
(recording :start)
(recording :stop)
(recording :list)
(recording :replay N)
(recording :replay N overrides)
(recording :diff M N)
(recording :save "name")
(recording :load "name")

;; Dynamic routing
(defroute! method path handler-fn)
(remove-route! method path)
(dynamic-routes)

;; Handler tapping
(tap-handler! handler-kw callback-fn)
(untap-handler! handler-kw)
(taps)

;; Component management
(restart-component key)

;; Module generation
(scaffold! name opts)
(prototype! name spec)
```

---

## Testing Strategy

### Unit Tests (core layer)
- `core/recording_test.clj` — session creation, entry add/get, diff, serialization round-trip, merge modifications
- `core/router_test.clj` — add/remove route from route tree, inject/remove tap interceptor
- `core/prototype_test.clj` — context building, endpoint mapping, migration spec generation

### Integration Tests (shell layer)
- `shell/recording_test.clj` — start/stop recording, capture middleware integration, save/load file round-trip
- `shell/router_test.clj` — router rebuild with dynamic routes, verify routes are reachable
- `shell/prototype_test.clj` — full prototype! flow generates expected file structure

### Verification Criteria (from parent spec)
- `(recording :start)` / `(recording :stop)` captures requests; `(recording :replay N)` reproduces them
- `(prototype!)` generates a working module that passes tests after `(reset)`

---

## Dependencies

- No new external dependencies
- `libs/scaffolder/` core (already JVM-compatible, pure functions)
- Integrant API (`ig/halt-key!`, `ig/init-key`) for `restart-component`
- Reitit internals for router rebuild — need to verify how platform exposes the router

## Open Questions

1. **Router atom access:** How does `libs/platform/` store the compiled Reitit router? If it's in a closure, we need a small change to make it swappable. To be verified during implementation.
