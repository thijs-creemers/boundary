# Routing Re-architecture Implementation Summary

## Branch: `feat/routing-rearchitecture-web-and-api-prefix`

## Objective
Fix routing architecture to properly support:
- `/api/*` prefix for REST API endpoints
- `/web/*` prefix for Web UI endpoints  
- Root-level health checks, API documentation, and static assets

## Problem Statement

**Before:** User module created its own complete router/handler, bypassing top-level route grouping logic. This caused:
- `/api/users` → 500 Server Error
- `/web/users` → Not properly prefixed
- Static assets → 406 Not Acceptable (content negotiation issues)

**Root Cause:** The user module exported a complete Ring handler via `:boundary/user-http-handler`, which used `routes/create-router` internally with all routes already defined. The system then used this handler directly, bypassing the intended route composition and prefix application.

## Solution Approach

Changed from **module-provides-handler** to **module-provides-route-definitions**:

1. Modules now return structured route definitions: `{:api [...] :web [...] :static []}`
2. Top-level HTTP handler composes routes and applies prefixes
3. Static files served via Ring's `wrap-resource` middleware (outside Reitit)

## Files Modified

### 1. `src/boundary/user/shell/http.clj`
**Changes:**
- Renamed `web-routes` → `web-ui-routes` (without `/web` prefix)
- Created `api-routes` function (without `/api` prefix)
- Restructured `user-routes` to return `{:api :web :static}` map
- Created `user-routes-flat` for backward compatibility
- Marked `create-handler`, `create-router`, `create-app` as DEPRECATED
- Set `:static []` (empty) since static files now served at handler level
- Updated namespace documentation

**Key Pattern:**
```clojure
(defn user-routes [user-service config]
  {:api (api-routes user-service)
   :web (when web-ui-enabled? (web-ui-routes user-service config))
   :static []})
```

### 2. `src/boundary/user/shell/module_wiring.clj`
**Changes:**
- Added new `:boundary/user-routes` init-key
- Deprecated `:boundary/user-http-handler` with warning logs
- Routes return structured format for composition

**Pattern:**
```clojure
(defmethod ig/init-key :boundary/user-routes
  [_ {:keys [user-service config]}]
  (user-http/user-routes user-service (or config {})))
```

### 3. `src/boundary/shell/system/wiring.clj`
**Changes:**
- Updated `:boundary/http-handler` to accept `:user-routes` instead of `:user-http-handler`
- Extracts `:api`, `:web`, `:static` from user-routes structure
- Adds `/web` prefix to web routes
- Passes routes to `routes/create-router` for `/api` prefix application
- Removed unused `boundary.platform.shell.modules` require

**Pattern:**
```clojure
(let [static-routes-vec (or (:static user-routes) [])
      web-routes-vec (or (:web user-routes) [])
      api-routes-vec (or (:api user-routes) [])
      web-routes-prefixed (mapv #(vector (str "/web" (first %)) (second %)) 
                                 web-routes-vec)
      all-routes (concat static-routes-vec web-routes-prefixed api-routes-vec)
      router (routes-create-router config all-routes)
      handler (routes-create-handler router)]
  handler)
```

### 4. `src/boundary/shell/interfaces/http/routes.clj`
**Changes:**
- Added `[clojure.string]` require (fixes clj-kondo warning)
- Added `[ring.middleware.resource :refer [wrap-resource]]` require
- Modified `create-handler` to use `wrap-resource` middleware
- Static files now served BEFORE Reitit routing, bypassing content negotiation

**Key Fix:**
```clojure
(defn create-handler [router & opts]
  (let [reitit-handler (ring/ring-handler router ...)]
    ;; Wrap with resource middleware - serves static files from public/ directory
    ;; This runs BEFORE Reitit, bypassing muuntaja content negotiation
    (wrap-resource reitit-handler "public")))
```

### 5. `src/boundary/config.clj`
**Changes:**
- Updated `user-module-config` to reference `:boundary/user-routes`
- Changed `:boundary/http-handler` dependency from `:user-http-handler` to `:user-routes`
- Updated documentation to reflect structured route format

**Pattern:**
```clojure
{:boundary/user-routes
 {:user-service (ig/ref :boundary/user-service)
  :config config}

 :boundary/http-handler
 {:config config
  :user-routes (ig/ref :boundary/user-routes)}}
```

## Technical Details

### Route Grouping Logic
The `routes/create-router` function (lines 178-185) groups routes based on path prefixes:

**Root-level routes** (no additional prefix):
- `/css/*`, `/js/*`, `/modules/*`, `/docs/*` - Static assets
- `/web/*` - Web UI (already prefixed)
- `/health*` - Health checks
- `/swagger.json`, `/api-docs` - API documentation

**API routes** (wrapped with `/api` prefix):
- Everything else gets grouped under `/api`

### Static Asset Serving
Static assets were causing 406 (Not Acceptable) errors because they were going through:
1. Reitit routing
2. Muuntaja content negotiation middleware
3. Resource handler

**Solution:** Moved static file serving to Ring middleware level using `wrap-resource`, which:
- Runs BEFORE Reitit routing
- Bypasses all Reitit middleware
- Serves files directly from `resources/public/` on classpath

### Middleware Stack Preservation
All existing middleware preserved:
- Correlation ID and request logging (all routes)
- Content negotiation (API routes only, via route grouping)
- Exception handling with error mappings
- Observability interceptors (unchanged)

## Verification Results

### Manual Testing (curl)
```
✅ GET /health                         → 200 OK
✅ GET /api/users?userId=...         → 200 OK (JSON)
✅ GET /web/users                      → 401 Unauthorized (auth required)
✅ GET /css/app.css                    → 200 OK (text/css)
✅ GET /js/htmx.min.js                 → 200 OK (application/javascript)
✅ GET /swagger.json                   → 200 OK (application/json)
✅ GET /api-docs                       → 302 Redirect
```

### Automated Testing
```
438 tests, 2283 assertions, 0 failures
```
All existing tests pass - no regressions.

### Code Quality
```
clj-kondo: 0 errors, 3 warnings (fixed in final cleanup)
```

## Backward Compatibility

### Deprecated Components
- `:boundary/user-http-handler` - Logs deprecation warning, still functional
- `create-handler`, `create-router`, `create-app` in user.shell.http - Marked deprecated

### Migration Path
For future modules:
1. Define `api-routes` - returns routes WITHOUT `/api` prefix
2. Define `web-routes` - returns routes WITHOUT `/web` prefix  
3. Define `user-routes` - returns `{:api [...] :web [...]}`
4. Wire as `:boundary/<module>-routes` returning the structured map
5. Update top-level handler to include new module routes

## Module Contract

**Structured Route Format:**
```clojure
{:api    [["/users" {:get handler :post handler}]
          ["/users/:id" {:get handler :put handler :delete handler}]]
 
 :web    [["/users" {:get page-handler}]
          ["/users/new" {:get new-page-handler}]]
          
 :static []}  ; Empty - handled at handler level
```

**Key Rules:**
- `:api` routes have NO `/api` prefix (added by top-level router)
- `:web` routes have NO `/web` prefix (added by system wiring)
- `:static` should be empty (served via wrap-resource middleware)
- Routes are plain Reitit route vectors

## Benefits

1. **Clear Separation**: Modules provide definitions, system handles composition
2. **Consistent Prefixing**: `/api` and `/web` applied uniformly
3. **Proper Middleware**: Static assets bypass content negotiation
4. **Extensibility**: Easy to add new modules following same pattern
5. **Testability**: Routes are data, easily tested without HTTP server
6. **Observability**: All middleware and interceptors preserved

## Known Limitations

1. Static routes still defined in user module but not used (kept for documentation)
2. `boundary.platform.shell.modules` compose-http-handlers deprecated but not removed
3. Some middleware grouping could be more explicit (works via route grouping)

## Next Steps (Optional)

1. ✅ Tests - All pass, no regressions
2. ⏭️ Documentation - ADR-007 for routing architecture
3. ⏭️ Remove deprecated code in next major version
4. ⏭️ Add middleware grouping documentation
5. ⏭️ Create module scaffolding tool

## Commit Message

```
feat: Implement routing re-architecture with proper /api and /web prefixing

- Restructure user module to export route definitions instead of handler
- Add top-level route composition in system wiring
- Move static file serving to wrap-resource middleware
- Add /api prefix to REST endpoints via route grouping
- Add /web prefix to web UI endpoints
- Fix static asset serving (406 → 200)
- Preserve all existing middleware and observability
- Add backward compatibility with deprecation warnings

BREAKING CHANGE: :boundary/user-http-handler replaced with :boundary/user-routes
Legacy handler still works but logs deprecation warnings.

Resolves routing issues where API and web endpoints were not accessible
at correct paths. All 438 tests pass with 0 failures.
```

## References

- **Branch**: `feat/routing-rearchitecture-web-and-api-prefix`
- **Test Results**: 438 tests, 2283 assertions, 0 failures
- **Modified Files**: 5 core routing files
- **Lines Changed**: ~300 additions, ~150 deletions
