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
| Handler swapping | Atom-based handler wrapper in platform | Jetty holds a direct function reference; atom indirection enables runtime swap without server restart |
| `restart-component` config | Re-resolve from Integrant config map | Picks up config changes; consistent with `(reset)` behavior |

---

## Architecture

### Platform Change: Handler Atom

The platform HTTP server (`libs/platform/`) currently passes the compiled handler directly to Jetty as a closure. Jetty holds a direct function reference — there is no indirection layer. To support runtime router rebuilds, we introduce an atom-based handler wrapper:

```clojure
;; In libs/platform/src/boundary/platform/shell/system/wiring.clj
(defonce ^:private handler-atom (atom nil))

(defn dispatch-handler [request]
  (@handler-atom request))

;; At init: (reset! handler-atom compiled-handler)
;; Jetty receives dispatch-handler (stable reference)
;; Router rebuilds swap handler-atom (no server restart)
```

This is a small, surgical change to `wiring.clj`:
1. Add `handler-atom` (defonce)
2. Store compiled handler in the atom at init time
3. Pass `dispatch-handler` to Jetty instead of the compiled handler directly
4. Expose a `swap-handler!` function that devtools can call

The atom is only used in dev profile. In production, the handler is passed directly (no indirection overhead).

### Recording vs Existing Request Capture

The dashboard's `wrap-request-capture` middleware (Phase 4) captures sanitized request summaries for the request inspector page. Recording needs full request/response bodies for faithful replay. Rather than modifying the existing capture:

- **Dashboard capture** stays as-is (sanitized, bounded at 200, always-on in dev)
- **Recording capture** is a separate middleware installed only during active recording sessions, capturing full bodies

They serve different purposes and operate independently.

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
- `libs/platform/src/boundary/platform/shell/system/wiring.clj` — handler-atom wrapper (dev profile only)
- `.gitignore` — add `.boundary/recordings/`

### Shared Router Rebuild Infrastructure

`defroute!`, `tap-handler!`, and `recording` all need to rebuild the Reitit router. A shared `shell/router.clj` provides:

- Reference to the running system's router (via Integrant system map)
- `rebuild-router!` — takes current route tree, applies modifications, compiles + swaps
- `dynamic-routes` atom — tracks routes added via `defroute!`
- `taps` atom — tracks active handler taps
- On `(reset)`, all dynamic modifications clear — these are ephemeral dev aids

**Platform integration:** Platform stores the handler as a direct closure passed to Jetty. The handler-atom wrapper (see Architecture section above) enables runtime swapping. `shell/router.clj` calls `swap-handler!` after recompiling the router.

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

**Error handling:**
- `(recording :replay N)` with no active session: prints "No active recording session. Use (recording :start) or (recording :load \"name\")."
- `(recording :replay N)` with out-of-bounds index: prints "Entry N not found. Session has M entries (0 to M-1)."
- `(recording :save "name")` with no active session: prints "No active recording session."
- `(recording :load "name")` with missing file: prints "Recording 'name' not found. Available: ..." (lists `.boundary/recordings/` contents).

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
- Calls `ig/halt-key!` on the component
- Re-resolves the component's config from the Integrant config map (picks up any config changes, consistent with `(reset)` behavior)
- Calls `ig/init-key` with the resolved config value
- Updates the running system atom atomically
- Prints component status after restart
- On invalid key: prints error with list of available component keys
- **Limitation:** does not restart dependent components. If component B depends on A and you restart A, B still holds the old reference. For cascading restarts, use `(reset)`.

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
- `libs/scaffolder/` core — JVM-compatible pure functions. Specifically: `boundary.scaffolder.core.generators` (generate-schema-file, generate-ports-file, generate-core-file, generate-migration-file, generate-service-file, generate-persistence-file, generate-http-file) and `boundary.scaffolder.core.template` (field/entity context builders). `libs/scaffolder` must be on the `:dev` classpath in `deps.edn`.
- Integrant API (`ig/halt-key!`, `ig/init-key`) for `restart-component`
- Reitit router internals for route tree manipulation
- `libs/platform/` — small change to `shell/system/wiring.clj` for handler-atom wrapper (see Architecture section)
