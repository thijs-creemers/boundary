# Boundary Framework - AI Agent Quick Reference

**AI Agent Quick Reference**: Essential patterns, commands, and conventions for working effectively with the Boundary Framework.

> **🛑 CRITICAL REMINDERS - READ THESE FIRST**
> 
> **GIT OPERATIONS - REQUIRE EXPLICIT PERMISSION:**
> - ❌ NEVER stage, commit, or push without explicit user permission
> - ✅ ALWAYS show changes and ASK before committing
> 
> **CODE EDITING:**
> - Use `clj-paren-repair` to fix unbalanced parentheses (never manually repair)
> - Use `clj-nrepl-eval` for REPL evaluation during development
> - **CRITICAL**: Always use kebab-case internally; convert snake_case/camelCase ONLY at system boundaries
> - All documentation must be accurate and in English

---

## Quick Reference

### Essential Commands

```bash
# Testing
clojure -M:test:db/h2                              # All tests
clojure -M:test:db/h2 --watch --focus-meta :unit   # Watch unit tests

# Code Quality
clojure -M:clj-kondo --lint src test               # Lint codebase

# REPL Development
clojure -M:repl-clj                                # Start REPL
# In REPL:
(require '[integrant.repl :as ig-repl])
(ig-repl/go)                                       # Start system
(ig-repl/reset)                                    # Reload and restart

# Tools
clj-nrepl-eval --discover-ports                    # Find nREPL ports
clj-nrepl-eval -p <port> "<code>"                  # Evaluate code
clj-paren-repair <file>                            # Fix parentheses

# Build
clojure -T:build clean && clojure -T:build uber    # Build uberjar
```

### Architecture Quick Facts

```
┌─────────────────────────────────────────────┐
│         IMPERATIVE SHELL (shell/*)          │
│  I/O, validation, logging, side effects     │
└─────────────────────────────────────────────┘
                    ↓ calls
┌─────────────────────────────────────────────┐
│           PORTS (ports.clj)                 │
│  Protocol definitions (interfaces)          │
└─────────────────────────────────────────────┘
                    ↑ implements
┌─────────────────────────────────────────────┐
│         FUNCTIONAL CORE (core/*)            │
│  Pure functions, business logic only        │
└─────────────────────────────────────────────┘
```

**Module Structure:**
```
src/boundary/{module}/
├── core/          # Pure business logic
├── shell/         # I/O, validation, adapters
├── ports.clj      # Protocol definitions
└── schema.clj     # Malli validation schemas
```

---

## Critical Conventions

### 1. ALWAYS Use kebab-case Internally

| Location | Format | Example |
|----------|--------|---------|
| **All Clojure code** | kebab-case | `:password-hash`, `:created-at` |
| **Database (at boundary only)** | snake_case | `password_hash`, `created_at` |
| **API (at boundary only)** | camelCase | `passwordHash`, `createdAt` |

```clojure
;; ❌ WRONG - snake_case in service layer
(defn login [user password]
  (verify-password password (:password_hash user)))  ; BUG!

;; ✅ CORRECT - kebab-case everywhere internally
(defn login [user password]
  (verify-password password (:password-hash user)))

;; ✅ CORRECT - Convert ONLY at persistence boundary
(defn db->entity [db-row]
  (snake-case->kebab-case-map db-row))  ; boundary.shared.core.utils.case-conversion
```

**Why**: Recent bug caused authentication failures because service layer used `:password_hash` but entities had `:password-hash`.

### 2. Layer Responsibilities

| Layer | Allowed | Never |
|-------|---------|-------|
| **Core** | Pure functions, calculations | I/O, logging, exceptions |
| **Shell** | I/O, validation, side effects | Business logic |
| **Ports** | Protocol definitions | Implementations |

### 3. Dependency Rules

- ✅ Shell → Core (shell calls core)
- ✅ Core → Ports (core depends on protocols)
- ✅ Shell → Adapters (shell implements protocols)
- ❌ Core → Shell (NEVER)
- ❌ Core → Adapters (NEVER)

---

## Common Workflows

### Adding New Functionality

1. **Define schema** in `{module}/schema.clj`
2. **Write core logic** in `{module}/core/{domain}.clj` (pure functions)
3. **Write unit tests** in `test/{module}/core/{domain}_test.clj`
4. **Define port** in `{module}/ports.clj` (protocol)
5. **Implement in service** in `{module}/shell/service.clj`
6. **Add HTTP endpoint** in `{module}/shell/http.clj`

### Testing Workflow

```bash
# Watch mode while developing
clojure -M:test:db/h2 --watch --focus-meta :unit

# Full test suite before committing
clojure -M:test:db/h2

# Lint before committing
clojure -M:clj-kondo --lint src test
```

### REPL Debugging

```clojure
;; Get system component
(def user-svc (get integrant.repl.state/system :boundary/user-service))

;; Test directly
(ports/list-users user-svc {:limit 10})

;; Reload namespace with changes (use :reload)
(require '[boundary.user.core.user :as user-core] :reload)

;; Full system reset
(ig-repl/halt)
(ig-repl/go)
```

---

## Common Pitfalls

### 1. snake_case vs kebab-case Mixing

**Problem**: Using snake_case internally causes nil lookups.

**Solution**: 
- Always use kebab-case in all internal code
- Convert at boundaries only (persistence layer, HTTP layer)
- Use utilities: `snake-case->kebab-case-map`, `kebab-case->snake-case-map`

### 2. defrecord Changes Not Taking Effect

**Problem**: `(ig-repl/reset)` doesn't recreate defrecord instances.

**Solution**:
```clojure
(ig-repl/halt)
(ig-repl/go)
;; OR restart REPL entirely
```

### 3. Unbalanced Parentheses

**Problem**: Manual editing creates syntax errors.

**Solution**:
```bash
# ALWAYS use the tool, never manually fix
clj-paren-repair src/boundary/user/core/user.clj
```

### 4. Validation in Wrong Layer

```clojure
;; ❌ WRONG - Validation in core
(defn create-user [user-data]
  (when-not (valid? user-data)  ; Side effect in pure code!
    (throw ...)))

;; ✅ CORRECT - Validation in shell
(defn create-user-service [user-data]
  (let [[valid? errors data] (validate user-data)]
    (if valid?
      (user-core/create-user data)  ; Core gets clean data
      (throw ...))))
```

### 5. Core Depending on Shell

```clojure
;; ❌ WRONG - Core requires shell namespace
(ns boundary.user.core.user
  (:require [boundary.user.shell.persistence :as db]))  ; BAD!

;; ✅ CORRECT - Core depends on ports only
(ns boundary.user.core.user)
(defn find-user-decision [user-id existing-user] ...)  ; Pure logic
```

---

## Key Technologies

| Library | Purpose | Critical Knowledge |
|---------|---------|-------------------|
| **Integrant** | Lifecycle | Use `ig-repl/go`, `ig-repl/reset`, `ig-repl/halt` |
| **Aero** | Config | Environment-based profiles in `resources/conf/` |
| **Malli** | Validation | Schemas in `{module}/schema.clj` |
| **next.jdbc** | Database | Connection pooling, prepared statements |
| **Ring/Reitit** | HTTP | Normalized routes, interceptors |
| **HTMX** | Web UI | Server-side rendering, no build step |

---

## Module Scaffolding

Generate complete production-ready modules:

```bash
# Generate module with entity
clojure -M -m boundary.scaffolder.shell.cli-entry generate \
  --module-name product \
  --entity Product \
  --field name:string:required \
  --field sku:string:required:unique \
  --field price:decimal:required

# Generates:
# - 9 source files (core, shell, ports, schema, wiring)
# - 3 test files (unit, integration, contract)
# - 1 migration file
# - Zero linting errors, complete FC/IS architecture
```

**Integration Steps**: See [Module Scaffolding Guide](docs/guides/module-scaffolding.md)

---

## Configuration

**Current Setup (Development)**:
- **Database**: SQLite (`dev-database.db`) - no setup required
- **HTTP**: Port 3000 (auto-find if busy: 3001-3099)
- **Environment**: Set `BND_ENV=development`

**Environment Variables**:
```bash
export JWT_SECRET="dev-secret-minimum-32-characters"
export DB_PASSWORD="dev_password"
```

**Configuration File**: `resources/conf/dev/config.edn`

---

## Security Features

### Multi-Factor Authentication (MFA)

**Status**: ✅ Production Ready

```bash
# Setup MFA
curl -X POST http://localhost:3000/api/auth/mfa/setup \
  -H "Authorization: Bearer <token>"

# Enable MFA
curl -X POST http://localhost:3000/api/auth/mfa/enable \
  -H "Authorization: Bearer <token>" \
  -d '{"secret": "...", "verificationCode": "123456"}'

# Login with MFA
curl -X POST http://localhost:3000/api/auth/login \
  -d '{"email": "user@example.com", "password": "...", "mfa-code": "123456"}'
```

**Details**: [MFA Setup Guide](docs/guides/mfa-setup.md)

---

## Observability

**Multi-Layer Interceptor Pattern**: Automatic logging, metrics, and error reporting without boilerplate.

```clojure
;; Service layer - use interceptor
(defn create-user [this user-data]
  (service-interceptors/execute-service-operation
   :create-user
   {:user-data user-data}
   (fn [{:keys [params]}]
     ;; Business logic here - observability automatic
     (let [user (user-core/prepare-user (:user-data params))]
       (.create-user repository user)))))

;; Result: Automatic breadcrumbs, error reporting, logging
```

**Providers**: No-op (development), Datadog, Sentry

---

## HTTP Interceptors

**Declarative cross-cutting concerns** (auth, rate limiting, audit):

```clojure
;; Define interceptor
(def require-admin
  {:name :require-admin
   :enter (fn [ctx]
            (if (admin? (get-in ctx [:request :session :user]))
              ctx
              (assoc ctx :response {:status 403 :body {:error "Forbidden"}})))})

;; Use in routes
[{:path "/api/admin"
  :methods {:post {:handler 'handlers/create-resource
                   :interceptors ['auth/require-admin
                                  'audit/log-action]}}}]
```

**Built-in**: Request logging, metrics, error reporting, correlation IDs

---

## UI/Frontend Development

### Technology Stack

| Technology | Purpose | Location |
|------------|---------|----------|
| **Hiccup** | HTML generation | `src/{module}/core/ui.clj` |
| **HTMX** | Dynamic interactions | Inline attributes in Hiccup |
| **Pico CSS** | Base framework | `resources/public/css/` |
| **Lucide Icons** | Icon system | `src/boundary/shared/ui/core/icons.clj` |

### UI Architecture Principles

1. **Server-side rendering**: All HTML generated via Hiccup (no build step)
2. **Progressive enhancement**: HTMX for dynamic behavior
3. **Design tokens**: Centralized in `resources/public/css/tokens.css`
4. **Icon library**: Use Lucide icons, never emoji in UI (CLI emoji is OK)

### Common UI Patterns

#### REPL Reload for UI Changes

```bash
# After modifying any ui.clj file
clj-nrepl-eval -p <port> "(require '[integrant.repl :as ig-repl]) (ig-repl/reset)"
```

**Important**: UI changes require REPL reload to take effect.

#### JavaScript in Hiccup Attributes

```clojure
;; ❌ WRONG - Inconsistent or broken logic
[:input {:type "checkbox"
         :onchange "if (this.checked) { /* count all */ } else { /* show 0 */ }"}]

;; ❌ ALSO WRONG - Queries before DOM updates complete
[:input {:type "checkbox"
         :onchange "elements.forEach(el => el.checked = this.checked);
                    const checked = document.querySelectorAll('input:checked').length;
                    document.getElementById('count').textContent = checked + ' selected';"}]

;; ✅ CORRECT - Use setTimeout to query AFTER DOM updates
[:input {:type "checkbox"
         :onchange "elements.forEach(el => el.checked = this.checked);
                    setTimeout(() => {
                      const checked = document.querySelectorAll('input:checked').length; 
                      document.getElementById('count').textContent = checked + ' selected';
                    }, 0);"}]
```

**Key Lesson**: When toggling multiple elements, always query the actual DOM state AFTER the update completes. Use `setTimeout(..., 0)` to defer the query to the next event loop tick, ensuring all `.checked` properties are updated first.

#### Icon Usage

```clojure
;; ❌ WRONG - Using emoji
[:button "🗑️ Delete"]

;; ✅ CORRECT - Using Lucide icons
[:button 
 (icons/icon :trash {:size 18})
 " Delete"]

;; Available in: src/boundary/shared/ui/core/icons.clj
```

#### HTMX Loading States

```clojure
;; Add loading indicators to forms
[:form {:hx-post "/api/endpoint"
        :hx-indicator "#spinner"}
 [:button "Submit"]
 [:span#spinner.htmx-indicator "Loading..."]]
```

### UI Component Hierarchy

```
src/boundary/
├── shared/ui/core/
│   ├── layout.clj        # Page layouts, navigation
│   ├── icons.clj         # Icon definitions (50+ Lucide icons)
│   └── components.clj    # Reusable components
├── admin/core/
│   └── ui.clj            # Admin interface (tables, forms)
└── {module}/core/
    └── ui.clj            # Module-specific UI components
```

### Styling Conventions

**Location**: `resources/public/css/`

```
css/
├── tokens.css     # Design tokens (colors, spacing, typography)
├── app.css        # Main app styles
├── admin.css      # Admin interface styles
└── components.css # Reusable component styles
```

**CSS Organization**:
1. Use design tokens for all values (colors, spacing, font sizes)
2. Component-specific styles in dedicated sections
3. Dark mode via CSS variables (no duplicate declarations)
4. Mobile-first responsive design

**Example**:
```css
/* ✅ Use design tokens */
.button {
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--color-primary);
  border-radius: var(--radius-md);
}

/* ❌ Don't use hardcoded values */
.button {
  padding: 8px 16px;
  background: #3b82f6;
  border-radius: 6px;
}
```

### Common UI Pitfalls

#### 1. JavaScript Event Handler Logic and DOM Timing

**Problem**: Select-all checkbox shows "0 selected" when checked, or count doesn't update.

**Root Causes**:
1. Using conditional logic based on trigger element state instead of querying actual DOM state
2. Querying DOM state before the browser has finished updating all elements

```clojure
;; ❌ WRONG - Assumes state without checking
[:input {:onchange "elements.forEach(el => el.checked = this.checked);
                    const count = this.checked ? elements.length : 0;"}]

;; ❌ ALSO WRONG - Queries before DOM updates complete
[:input {:onchange "elements.forEach(el => el.checked = this.checked);
                    const count = document.querySelectorAll(':checked').length;"}]

;; ✅ CORRECT - Query actual state AFTER DOM updates via setTimeout
[:input {:onchange "elements.forEach(el => el.checked = this.checked);
                    setTimeout(() => {
                      const count = document.querySelectorAll(':checked').length;
                      document.getElementById('count').textContent = count + ' selected';
                    }, 0);"}]
```

**Why**: 
- The DOM update happens asynchronously, so always query the actual state rather than inferring it
- Use `setTimeout(..., 0)` to defer execution until after the browser completes updating all element states
- This pushes the query to the next event loop tick, ensuring all `.checked` properties are updated first

#### 2. Inline JavaScript String Escaping

**Problem**: Clojure string escaping in Hiccup attributes with complex JavaScript.

**Solution**: 
- Keep inline JavaScript simple (1-2 statements)
- For complex logic, extract to external JS file: `resources/public/js/`
- Use `\"` for nested quotes in Clojure strings

```clojure
;; ❌ AVOID - Complex inline JavaScript
[:input {:onclick "var x = document.querySelector(\"#foo\"); 
                   if (x.value == \"bar\") { /* ... */ }"}]

;; ✅ BETTER - Extract to external file
[:input {:onclick "handleClick(this)"}]
;; Define handleClick() in resources/public/js/app.js
```

#### 3. Inconsistent Event Handlers

**Problem**: Different logic for related actions (e.g., select-all vs individual checkboxes).

**Solution**: Keep event handler logic consistent across related elements.

```clojure
;; Individual checkbox logic
[:input {:onchange "const checked = document.querySelectorAll(':checked').length;
                    updateCount(checked);"}]

;; Select-all checkbox - MUST use same logic pattern
[:input {:onchange "elements.forEach(el => el.checked = this.checked);
                    const checked = document.querySelectorAll(':checked').length;
                    updateCount(checked);"}]
```

**Key**: Both use `querySelectorAll(':checked')` to ensure consistency.

#### 4. Icon Inconsistency

**Problem**: Mixing emoji and icon library usage.

**Solution**: Always use Lucide icons in UI, never emoji (emoji OK in CLI output only).

```clojure
;; ❌ WRONG - Emoji in UI
[:button "🔍 Search"]

;; ✅ CORRECT - Lucide icon
[:button (icons/icon :search) " Search"]
```

### UI Testing Checklist

When making UI changes, always test:

- [ ] **Desktop view** (1920x1080)
- [ ] **Mobile view** (375x667)
- [ ] **Dark mode** (toggle and verify all elements)
- [ ] **Keyboard navigation** (Tab, Enter, Escape)
- [ ] **Loading states** (HTMX indicators work)
- [ ] **Form validation** (error messages display)
- [ ] **JavaScript interactions** (event handlers work correctly)

### UI Development Workflow

1. **Modify Hiccup** in `src/{module}/core/ui.clj`
2. **Reload REPL** via `clj-nrepl-eval -p <port> "(ig-repl/reset)"`
3. **Refresh browser** (Cmd+R / Ctrl+R)
4. **Test in both light and dark mode**
5. **Test responsive behavior** (resize window)
6. **Commit changes** (after explicit user permission)

---

## Testing Strategy

| Category | Location | Purpose |
|----------|----------|---------|
| **Unit** | `test/{module}/core/*` | Pure functions, no mocks |
| **Integration** | `test/{module}/shell/*` | Service with mocked deps |
| **Contract** | `test/{module}/shell/*` | Adapters with real DB |

```bash
clojure -M:test:db/h2 --focus-meta :unit        # Fast, no I/O
clojure -M:test:db/h2 --focus-meta :integration # Mocked I/O
clojure -M:test:db/h2 --focus-meta :contract    # Real database
```

---

## Troubleshooting

### System Won't Start

```bash
rm -rf .cpcache target
clojure -M:repl-clj
```

### Tests Failing

```bash
# Run unit tests only (should always pass)
clojure -M:test:db/h2 --focus-meta :unit

# Verbose output
clojure -M:test:db/h2 -n boundary.user.core.user-test --reporter documentation
```

### Parenthesis Errors

```bash
# NEVER fix manually, always use the tool
clj-paren-repair <file>
```

### defrecord Not Updating

```clojure
;; In REPL
(ig-repl/halt)
(ig-repl/go)  ; Fresh start
```

---

## Detailed Documentation

**For in-depth information, see:**

- **[Full Agent Guide](docs/AGENTS_FULL.md)** - Complete 2,774-line reference
- **[Architecture Guide](https://github.com/thijs-creemers/boundary-docs/tree/main/content/architecture/)** - FC/IS patterns, design decisions
- **[Module Scaffolding](docs/guides/module-scaffolding.md)** - Complete scaffolding workflow
- **[MFA Setup Guide](docs/guides/mfa-setup.md)** - Multi-factor authentication
- **[API Pagination](docs/API_PAGINATION.md)** - Offset and cursor pagination
- **[Observability Integration](https://github.com/thijs-creemers/boundary-docs/tree/main/content/guides/integrate-observability.adoc)** - Custom adapters, configuration
- **[HTTP Interceptors](https://github.com/thijs-creemers/boundary-docs/tree/main/content/adr/ADR-010-http-interceptor-architecture.adoc)** - Technical specification
- **[PRD](https://github.com/thijs-creemers/boundary-docs/tree/main/content/reference/boundary-prd.adoc)** - Product vision and requirements

---

## Quick Reference Card

```
╔════════════════════════════════════════════════════════════════╗
║                  BOUNDARY FRAMEWORK CHEAT SHEET                ║
╠════════════════════════════════════════════════════════════════╣
║ TEST    │ clojure -M:test:db/h2 --watch --focus-meta :unit     ║
║ LINT    │ clojure -M:clj-kondo --lint src test                 ║
║ REPL    │ clojure -M:repl-clj                                  ║
║         │ (ig-repl/go)    (ig-repl/reset)    (ig-repl/halt)    ║
║ BUILD   │ clojure -T:build clean && clojure -T:build uber      ║
║ REPAIR  │ clj-paren-repair <files>  # Fix parentheses          ║
║ EVAL    │ clj-nrepl-eval -p <port> "<code>"  # REPL eval       ║
╠════════════════════════════════════════════════════════════════╣
║ CORE    │ Pure functions only, no side effects                 ║
║ SHELL   │ All I/O, validation, error handling                  ║
║ PORTS   │ Protocol definitions (abstractions)                  ║
║ SCHEMA  │ Malli schemas for validation                         ║
╠════════════════════════════════════════════════════════════════╣
║ ALWAYS  │ Use kebab-case internally (ALL code)                 ║
║ NEVER   │ Use snake_case except at DB boundary                 ║
║ NEVER   │ Put business logic in shell layer                    ║
║ NEVER   │ Make core depend on shell                            ║
╚════════════════════════════════════════════════════════════════╝
```

---

**Last Updated**: 2026-01-08
**Version**: 2.0.0 (Streamlined)
