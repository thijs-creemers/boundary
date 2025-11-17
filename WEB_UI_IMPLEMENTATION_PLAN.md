# Web UI Implementation Plan

## Executive Summary

Implementation plan for the HTMX + Hiccup web UI in the Boundary Framework, using a module-integrated approach with shared UI components rather than a separate web-ui module.

## Status: Phase 1 Complete ✅

- ✅ **Architecture Decision**: ADR-006 - HTMX + Hiccup selected over ClojureScript  
- ✅ **Shared UI Components**: Complete component library in `boundary.shared.ui.core`
- ✅ **Test Coverage**: 17 UI tests passing (84 assertions), full test suite 424 tests passing
- ✅ **Integration Foundation**: Web handlers and UI functions created in user module
- ✅ **Documentation**: ADR-006 and implementation plan aligned with actual architecture

## Architecture Overview

### Actual Implementation Approach

**Key Decision**: Web UI is integrated directly into existing domain modules (user, billing, workflow) rather than creating a separate `boundary.web-ui` module. This approach:

- Leverages existing HTTP infrastructure in each module
- Keeps web UI close to business logic  
- Avoids over-engineering with separate modules
- Uses shared components for consistency

### Technology Stack
- **Frontend**: HTMX (progressive enhancement) + Hiccup (server-side rendering)
- **Backend**: Same Clojure ports as REST API/CLI (user, billing business logic)
- **HTTP**: Existing Reitit routes + Ring middleware per module
- **Components**: Shared component library in `boundary.shared.ui.core`

## Current Implementation Structure

### Phase 1: Shared Components Foundation ✅

```
src/boundary/shared/ui/core/
├── components.clj           # ✅ 9 reusable UI components
└── layout.clj              # ✅ Page layout and template functions

src/boundary/user/core/
└── ui.clj                  # ✅ User-specific UI generation functions

src/boundary/user/shell/
└── web_handlers.clj        # ✅ User web route handlers

test/boundary/shared/ui/core/
├── components_test.clj     # ✅ Component tests (17 tests, 84 assertions)
└── layout_test.clj         # ✅ Layout tests
```

### Shared Component Library

**Components Available** - `src/boundary/shared/ui/core/components.clj`:
- `text-input` - Text fields with attribute passthrough
- `password-input` - Password fields  
- `email-input` - Email fields with validation
- `number-input` - Numeric inputs
- `textarea` - Multi-line text areas
- `checkbox` - Checkbox inputs with conditional state
- `submit-button` - Form submission buttons
- `button` - General purpose buttons
- `form` - Form containers with method/action

**Layout System** - `src/boundary/shared/ui/core/layout.clj`:
- `base-page` - HTML5 document structure
- `main-container` - Content containers
- `card` - Card-based layouts

**Architecture Validation:**
- ✅ **Pure Functions**: All components are side-effect free
- ✅ **Attribute Passthrough**: Full HTML attribute support via `(merge base-attrs (dissoc opts :type))`
- ✅ **Composable Design**: Components nest naturally
- ✅ **FC/IS Compliance**: Clear separation of pure UI generation from side effects

## Integration Pattern: Module-Based Web UI

Instead of a separate web-ui module, each domain module handles its own web interface:

### User Module Example
```clojure
;; src/boundary/user/shell/web_handlers.clj
(ns boundary.user.shell.web-handlers
  (:require [boundary.user.core.ui :as user-ui]
            [boundary.shared.ui.core.components :as ui]))

(defn list-users-page [request]
  (let [users (user-port/fetch-all)]
    {:status 200
     :headers {"content-type" "text/html"}
     :body (user-ui/users-list-page users)}))

;; src/boundary/user/core/ui.clj  
(ns boundary.user.core.ui
  (:require [boundary.shared.ui.core.components :as ui]
            [boundary.shared.ui.core.layout :as layout]))

(defn users-list-page [users]
  (layout/base-page
    {:title "Users"}
    [:div
     [:h1 "Users"]
     (for [user users]
       (ui/card [:p (:name user)]))]))
```

### HTTP Routes Integration
Each module's existing HTTP infrastructure handles web routes:

```clojure
;; In boundary.user.shell.http/routes (existing file)
(defn routes []
  [["/api/users" {...existing API routes...}]
   ["/users"     {:get  web-handlers/list-users-page
                  :post web-handlers/create-user}]
   ["/users/:id" {:get  web-handlers/show-user}]])
```

## Implementation Roadmap

### ✅ Phase 1: Foundation (Completed)
**Duration**: 1 week  
**Status**: ✅ Complete  
**Deliverables**: 
- Shared component library with 9 components
- Layout system for consistent page structure  
- Full test coverage (17 tests, 84 assertions)
- User module web handler and UI function placeholders

### Phase 2: User Management UI (Next)
**Duration**: 2 weeks  
**Status**: 🔄 Ready to start  
**Deliverables**:
- Complete user list, create, edit, delete web interface
- HTMX integration for dynamic updates
- Form validation with inline error display
- Integration with existing user business logic

**Implementation Steps**:
1. **Week 1**: Static pages (list users, user details)
2. **Week 2**: Forms and HTMX interactions (create, edit, delete)

### Phase 3: Additional Modules (Future)
**Duration**: 2-3 weeks per module  
**Status**: 📋 Planned  
**Modules**: Billing, Workflow  
**Pattern**: Follow same module-integrated approach as user module

### Phase 4: Production Readiness (Future)
**Duration**: 1 week  
**Status**: 📋 Planned  
**Deliverables**:
- CSRF protection
- Session management
- Error handling and user feedback
- Performance optimization

## Development Approach

### Module Integration Pattern
1. **Business Logic Reuse**: Web handlers call same ports as API endpoints
2. **UI Functions**: Pure functions in `{module}/core/ui.clj` generate Hiccup
3. **Web Handlers**: Side-effect functions in `{module}/shell/web_handlers.clj`
4. **Route Integration**: Add web routes to existing `{module}/shell/http.clj`
5. **Shared Components**: Use `boundary.shared.ui.core` for consistency

### HTMX Integration Strategy
- **Progressive Enhancement**: Pages work without JavaScript
- **Partial Updates**: HTMX requests return HTML fragments
- **Form Handling**: Submit forms asynchronously with validation feedback
- **Error Display**: Inline error messages using shared components

### Testing Strategy
- **Component Tests**: Test individual UI components in isolation
- **Integration Tests**: Test complete page rendering
- **User Flow Tests**: Test HTMX interactions end-to-end
- **Existing Test Suite**: Leverage existing business logic tests

## Key Benefits of Module-Integrated Approach

### Architectural Benefits
- **Simplicity**: No additional module complexity
- **Cohesion**: Web UI stays close to business logic
- **Reuse**: Shared components ensure consistency
- **Maintainability**: Changes to business logic automatically available to web UI

### Development Benefits  
- **REPL-Friendly**: Develop UI functions in same namespace as business logic
- **Fast Iteration**: No cross-module dependencies to manage
- **Test Coverage**: Reuse existing test infrastructure
- **Deployment**: No additional deployment complexity

## Next Steps

To continue implementation:

1. **Start Phase 2**: Implement complete user management web interface
2. **HTMX Setup**: Add HTMX library and basic interaction patterns  
3. **Form Handling**: Implement create/edit user forms with validation
4. **Testing**: Add integration tests for web UI functionality
5. **Module Expansion**: Repeat pattern for billing and workflow modules

## Technical Decisions Made

### Component Architecture
- **Pure Functions**: All UI components return Hiccup data structures
- **Attribute Passthrough**: Components accept any HTML attributes for flexibility
- **Composability**: Components designed to nest and combine naturally
- **Testing**: Complete test coverage for all components and layouts

### Integration Strategy
- **Module-Based**: Web UI integrated into existing domain modules
- **HTTP Reuse**: Leverage existing Reitit/Ring infrastructure per module
- **Port Reuse**: Web handlers call same business logic ports as API endpoints
- **Shared Assets**: Common UI components and layouts in shared namespace

### Observability Strategy
- **Existing Pipeline**: Use same interceptor pipeline as REST API
- **HTML Adaptation**: Error responses render HTML instead of JSON
- **HTMX Detection**: Partial rendering based on request headers
- **Metrics**: Web UI metrics integrated with existing metrics system

## Future Technology Considerations

### Replicant (ClojureScript Reactive UI) - Deferred

**Status**: Evaluated but not adopted for current phases (1-3)

**When to Reconsider**:
- Interaction density exceeds 5 distinct partial update regions per page
- User experience requires <150ms perceived response times
- Multiple UI elements need shared ephemeral state (wizards, dashboards)
- HTMX round-trip latency becomes UX bottleneck

**Adoption Approach (If Needed)**:
1. **Island Strategy**: Mount Replicant components within existing HTMX pages
2. **Hybrid Rendering**: Server-side Hiccup with client-side hydration
3. **Gradual Migration**: Replace high-interaction HTMX endpoints with Replicant islands
4. **Metrics-Driven**: Adopt only when ≥30% latency improvement or ≥40% server request reduction proven

**Framework Compliance Safeguards**:
- Keep domain logic server-side (FC/IS compliance)
- Client limited to view-state and intent dispatch
- Preserve existing Hiccup functions for rollback capability
- All mutations require server confirmation (no optimistic domain changes)

**Decision Point**: Reevaluate after Phase 2 completion based on user interaction patterns and performance metrics.

This approach provides a pragmatic, maintainable foundation for web UI development while leveraging the existing Boundary Framework architecture and avoiding over-engineering. The Replicant evaluation ensures we have a clear upgrade path if interaction complexity increases beyond HTMX capabilities.