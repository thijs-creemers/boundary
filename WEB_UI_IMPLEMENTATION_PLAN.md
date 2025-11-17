# Web UI Implementation Plan

## Executive Summary

Complete implementation plan for adding an HTMX + Hiccup web UI to the Boundary Framework. All architectural decisions have been documented and the system is ready for development.

## Status

- ✅ **Architecture Decision**: ADR-006 created - HTMX + Hiccup selected over ClojureScript
- ✅ **Shared UI Components**: Implemented in `boundary.shared.ui.core` with full test coverage
- ✅ **Component Library**: 9 reusable components (text-input, checkbox, forms, etc.)
- ✅ **Test Coverage**: 17 UI tests passing (84 assertions), 424 total tests passing
- ✅ **Architecture Validation**: Pure functions, FC/IS compliance, attribute passthrough
- 🔄 **HTTP Integration**: Designed integration with existing Reitit/Ring infrastructure  
- 🔄 **Observability Strategy**: Leverages shared interceptor pipeline with HTML adaptations
- 🔄 **Implementation Roadmap**: Updated with actual implementation progress

## Architecture Overview

### Technology Stack
- **Frontend**: HTMX (progressive enhancement) + Hiccup (server-side rendering)
- **Backend**: Same Clojure ports as REST API/CLI (user, billing business logic)
- **HTTP**: Reitit routes + Ring middleware + Integrant lifecycle management
- **Observability**: Shared interceptor pipeline with logging, metrics, error reporting

### Implemented Structure (Phase 1 - Shared Components)
```
src/boundary/shared/ui/core/
├── components.clj           # ✅ Reusable UI components (forms, inputs, layout)
└── layout.clj              # ✅ Page layout and template functions

src/boundary/user/core/
└── ui.clj                  # ✅ User-specific UI generation functions (placeholder)

src/boundary/user/shell/
└── web_handlers.clj        # ✅ User web route handlers (placeholder)

test/boundary/shared/ui/core/
├── components_test.clj     # ✅ Component tests (17 tests, 84 assertions)
└── layout_test.clj         # ✅ Layout tests
```

### Future Module Structure (Full Web UI)
```
src/boundary/web-ui/
├── core/                    # Functional Core (Pure Functions)
│   ├── ui.clj              # Base HTML generation (layouts, forms)
│   ├── transforms.clj      # Data transformations (user->row) 
│   └── validation.clj      # Form validation logic
├── shell/                   # Imperative Shell (Side Effects)
│   ├── http.clj            # Route definitions + HTTP handler
│   ├── handlers.clj        # HTTP request handlers
│   ├── templates.clj       # Template rendering
│   ├── interceptors.clj    # HTMX detection + observability
│   ├── middleware.clj      # Web-specific middleware
│   └── module_wiring.clj   # Integrant lifecycle
├── ports.clj               # Protocol definitions
└── schema.clj              # Data schemas
```

## HTTP Integration Design

### Route Registration Pattern
```clojure
;; src/boundary/web_ui/shell/http.clj
(defn web-ui-routes []
  [["/"                 {:get handlers/home-page}]
   ["/users"            {:get  handlers/list-users
                         :post handlers/create-user}]
   ["/users/:id/edit"   {:get  handlers/edit-user-form
                         :post handlers/update-user}]])

(defn create-handler []
  (-> (bh.routes/create-handler (web-ui-routes))
      (http.mw/wrap-global-middleware)
      (ring/wrap-interceptor-chain wi.ix/default-interceptors)
      (wrap-resource "public")
      (wrap-content-type)))
```

### HTMX Detection & Partial Rendering
```clojure
;; src/boundary/web_ui/shell/interceptors.clj
(def htmx-interceptor
  (interceptor
    {:name ::htmx
     :enter (fn [{:keys [request] :as ctx}]
              (let [hx? (boolean (get-in request [:headers "hx-request"]))]
                (assoc-in ctx [:request :htmx] hx?)))}))

;; In handlers: check (:htmx request) to return full page vs partial fragment
```

### System Integration
```clojure
;; Integrant configuration adds :web-ui to module list
{:boundary.shell.interfaces.http/routes/modules
  [:user :billing :web-ui]}

;; boundary.shell.modules/compose-http-handlers automatically includes web-ui routes
```

## Observability Integration

### Shared Interceptor Pipeline
The web UI uses the same observability infrastructure as REST API:
- **Context Management**: `boundary.shared.core.interceptor-context`
- **Logging**: Request/response logging with correlation IDs
- **Metrics**: Timing and counters per endpoint
- **Error Reporting**: Exception capture with breadcrumbs

### HTML-Specific Adaptations
```clojure
;; Error responses render HTML error pages instead of JSON Problem Details
;; HTMX requests get partial error fragments for inline display
;; Template rendering metrics tracked separately from API response times
```

## Implementation Progress

### ✅ Completed: Shared UI Component Foundation (Phase 1)

**Component Library** - `src/boundary/shared/ui/core/components.clj`:
- `text-input` - Text fields with full attribute passthrough (`{:placeholder "..." :required true}`)  
- `password-input` - Password fields
- `email-input` - Email fields with type validation
- `number-input` - Numeric input fields
- `textarea` - Multi-line text areas
- `checkbox` - Checkbox inputs with conditional checked state
- `submit-button` - Form submission buttons
- `button` - General purpose buttons  
- `form` - Form containers with method/action support

**Layout System** - `src/boundary/shared/ui/core/layout.clj`:
- `base-page` - HTML5 document structure with head/body
- `main-container` - Content container with consistent styling
- `card` - Card-based content containers

**Architecture Validation:**
- ✅ **Pure Functions**: All components are side-effect free
- ✅ **Attribute Passthrough**: `(merge base-attrs (dissoc opts :type))` pattern enables custom attributes
- ✅ **Composable Design**: Components nest and combine naturally
- ✅ **Test Coverage**: 17 tests, 84 assertions, 100% passing
- ✅ **FC/IS Compliance**: Strict separation between pure UI generation and side effects

**Key Implementation Decisions:**
- **Flexible Attribute Handling**: Components accept any HTML attributes, not just predefined ones
- **Conditional State Management**: Checkboxes only add `{:checked true}` when needed, not `{:checked false}`
- **Hiccup Data Structures**: All components return pure Hiccup vectors for maximum composability

## 6-Week Implementation Roadmap

### ✅ Week 1: Scaffold & "Hello World" - COMPLETED
**Files**: ✅ `shared/ui/core/components.clj`, ✅ `shared/ui/core/layout.clj`, ✅ component tests  
**Milestone**: ✅ Shared UI component library with full test coverage (17 tests, 84 assertions)  
**Outcome**: Foundation UI components ready for use across all modules

### Week 2: Route Integration & HTMX Setup
**Files**: `web-ui/shell/http.clj`, `web-ui/shell/handlers.clj`, `web-ui/shell/interceptors.clj`  
**Milestone**: GET "/" returns Hiccup page using shared components  
**Test**: Route registration smoke test

### Week 3: Static Assets & HTMX Detector  
**Files**: `resources/public/js/htmx.min.js`, `resources/public/css/site.css`, update middleware  
**Milestone**: Static files served, HTMX requests detected in interceptor context  
**Test**: Asset serving + HTMX header detection

### Week 4: List Users UI
**Files**: `core/transforms.clj`, update handlers/templates  
**Milestone**: GET `/users` returns full page OR HTMX partial based on request headers  
**Test**: Full page vs partial rendering logic

### Week 5: Create/Edit User Forms & Validation
**Files**: `core/validation.clj`, create/edit handlers, form templates  
**Milestone**: HTMX form submission with inline error rendering using shared components  
**Test**: Form validation + HTMX error handling

### Week 6: Integration Testing & Cross-Module Flows
**Files**: `test/integration/web_ui/user_flow_test.clj`  
**Milestone**: End-to-end user workflows tested  
**Test**: Complete user CRUD operations via HTMX

### Week 7: Production Readiness & Hardening
**Files**: Enhanced middleware, error handling, asset fingerprinting  
**Milestone**: Security, performance, and observability hardening  
**Test**: Security scan, load testing, error page rendering

## Key Integration Points

### Business Logic Reuse
- Web UI calls same `user-port/create-user`, `user-port/fetch-all` as REST API
- No duplication of business logic - only different presentation layer
- Session management can reuse existing user authentication ports

### Development Environment
- HTMX served from resources/public/js/ (CDN copy)
- CSS/images served via Ring static middleware
- Hot reloading works with existing REPL-driven development

### Deployment Strategy
- Same JVM process as REST API - no separate deployment
- Static assets can be CDN-cached with fingerprinting
- Feature flag controlled rollout via existing config system

## Risk Mitigation

### Technical Risks
- **CSRF Protection**: Include CSRF tokens in HTMX headers
- **Session State**: Use Ring session middleware for flash messages
- **Caching Issues**: No-cache headers on HTMX endpoints, ETag on static assets
- **Error Handling**: Comprehensive HTML error pages via error interceptor

### Integration Risks
- **Port Protocol Changes**: Test against real user module implementations
- **Route Conflicts**: Web UI routes prefixed to avoid conflicts with API routes
- **Performance**: Template caching and fragment optimization

## Next Steps

The system is now ready for implementation. To begin development:

1. Start with Week 1 scaffold - create the basic module structure
2. Follow the file creation order for proper dependency management  
3. Test each milestone before proceeding to the next week
4. Use the integration tests to validate cross-module functionality

All architectural decisions are documented in ADR-006, and the implementation follows established Boundary Framework patterns for consistency and maintainability.

## References

- **ADR-006**: `docs/modules/ROOT/pages/adr/ADR-006-web-ui-architecture-htmx-hiccup.adoc`
- **System Architecture**: Updated in `warp.md` and `PRD.adoc`
- **Existing Patterns**: Follow `boundary.user.shell.http` module structure
- **Observability**: Use `boundary.shared.core.interceptors` pipeline