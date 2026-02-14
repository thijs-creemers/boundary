# Admin Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Admin Entity Configuration

Admin entity configurations are modular - each module owns its entity config in `resources/conf/{env}/admin/{module}.edn`:

```
resources/conf/dev/
├── config.edn              ← Main config, uses #include
└── admin/
    └── users.edn           ← User module's entity config
```

**Main config uses Aero's `#include`**:
```clojure
:boundary/admin
{:enabled?         true
 :entities         #merge [#include "admin/users.edn"]
 :pagination       {...}}
```

**Module entity file** (`admin/users.edn`):
```clojure
{:users
 {:label           "Users"
  :list-fields     [:email :name :role :created-at]
  :search-fields   [:email :name]
  :hide-fields     #{:password-hash :deleted-at}
  :readonly-fields #{:id :created-at :updated-at}}}
```

**Adding new modules**: Create `admin/{module}.edn` and add to the `#merge` vector:
```clojure
:entities #merge [#include "admin/users.edn"
                  #include "admin/inventory.edn"]
```

---

## UI/Frontend Development

### Technology Stack

| Technology | Purpose | Location |
|------------|---------|----------|
| **Hiccup** | HTML generation | `libs/{library}/src/boundary/{library}/core/ui.clj` |
| **HTMX** | Dynamic interactions | Inline attributes in Hiccup |
| **Pico CSS** | Base framework | `resources/public/css/` |
| **Lucide Icons** | Icon system | `libs/admin/src/boundary/shared/ui/core/icons.clj` |

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

;; Available in: libs/admin/src/boundary/shared/ui/core/icons.clj
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
libs/admin/src/boundary/
├── shared/ui/core/
│   ├── layout.clj        # Page layouts, navigation
│   ├── icons.clj         # Icon definitions (50+ Lucide icons)
│   └── components.clj    # Reusable components
├── admin/core/
│   └── ui.clj            # Admin interface (tables, forms)

libs/{module}/src/boundary/{module}/core/
└── ui.clj                # Module-specific UI components
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

---

## Common Pitfalls

### 1. Form Parsing - Array Values from Checkboxes

**Problem**: Checkboxes using hidden field + checkbox pattern submit as arrays `["false", "true"]`.

**Symptom**: `ClassCastException: PersistentVector cannot be cast to CharSequence`

**Root Cause**: HTML forms with this pattern:
```html
<input type="hidden" name="active" value="false">
<input type="checkbox" name="active" value="true" checked>
```
Both values are submitted when checkbox is checked, resulting in an array.

**Solution**: Normalize array values in form parsing:
```clojure
(defn parse-form-params [params entity-config]
  (reduce-kv
    (fn [acc field-name value]
      (let [; Handle array values - take the last value
            normalized-value (if (vector? value)
                              (last value)
                              value)]
        ; ... rest of parsing logic using normalized-value
        ))
    {}
    params))
```

**Prevention**: Always assume form values might be arrays (Ring can submit multiple values for same field name).

### 2. Flash Messages - Map vs Sequence Confusion

**Problem**: Iterating over flash message map produces invalid Hiccup.

**Symptom**: `IllegalArgumentException: No implementation of method: :write-body-to-stream`

**Root Cause**: Code expecting sequence of messages, but receiving single map:
```clojure
;; ❌ WRONG - Iterating over map structure
(for [[type message] flash]  ; flash is {:type :error :message "..."}
  [:div {:class (str "alert-" type)} message])
;; Produces: [:div {:class "alert-type"} :error] [:div {:class "alert-message"} "..."]
```

**Solution**: Access map keys directly:
```clojure
;; ✅ CORRECT - Direct map access
(when flash
  [:div {:class (str "alert alert-" (name (:type flash)))}
   (:message flash)])
```

**Prevention**: Be explicit about data structure contracts. If flash is a map, document it and access it as a map. Don't iterate over maps unless you truly want key-value pairs.

### 3. HTMX Target Mismatch - Fragment Nesting

**Problem**: HTMX replaces target with response that contains duplicate parent elements.

**Symptom**: Clicking table header to sort adds extra filter box to page.

**Root Cause**:
- HTMX targets `#entity-table-container`
- Server returns `#filter-table-container` (which contains filter builder + table)
- HTMX replaces table with entire filter-table container
- Result: Nested duplicate filter builders

**Solution**: Ensure HTMX response matches the target selector:
```clojure
;; ❌ WRONG - Returning parent container when targeting child
(defn table-fragment-handler [request]
  (htmx-fragment-response
    [:div#filter-table-container     ; Parent
     (render-filter-builder ...)     ; Filter
     [:div#entity-table-container    ; Child (the actual target)
      (render-table ...)]]))

;; ✅ CORRECT - Return exactly what's targeted
(defn table-fragment-handler [request]
  (htmx-fragment-response
    [:div#entity-table-container     ; Match the hx-target
     (render-table ...)]))
```

**Prevention**:
- HTMX target selector should match the root element of the response
- Use `hx-target="#foo"` → response should have `[:div#foo ...]` as root
- Keep filter UI outside HTMX-updated regions if it shouldn't change

### 4. Direct Navigation to HTMX Fragment Endpoints

**Problem**: Refreshing page on HTMX fragment URL shows unstyled HTML.

**Symptom**: URL changes to `/web/admin/users/table?sort=...`, page loses CSS.

**Root Cause**: HTMX `hx-push-url` updates browser history with fragment endpoint URLs. When user refreshes, browser requests fragment (HTML without layout) as a full page.

**Solution**: Detect non-HTMX requests and redirect to full page:
```clojure
(defn entity-table-fragment-handler [request]
  (let [is-htmx? (get-in request [:headers "hx-request"])]
    ; Redirect non-HTMX requests back to full page
    (when-not is-htmx?
      (let [query-string (:query-string request)
            redirect-url (str "/web/admin/users"
                             (when query-string (str "?" query-string)))]
        (throw (ex-info "Redirect to full page"
                        {:type :redirect
                         :location redirect-url
                         :status 303}))))
    ; ... normal fragment response
    ))
```

**Prevention**:
- Always check `hx-request` header in fragment-only endpoints
- Redirect non-HTMX requests to corresponding full page routes
- Preserve query parameters in redirect for proper state restoration
- Ensure error interceptor handles `:redirect` type with proper HTTP redirects

---

## UI Pitfalls

### 1. JavaScript Event Handler Logic and DOM Timing

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

### 2. Inline JavaScript String Escaping

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

### 3. Inconsistent Event Handlers

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

### 4. Icon Inconsistency

**Problem**: Mixing emoji and icon library usage.

**Solution**: Always use Lucide icons in UI, never emoji (emoji OK in CLI output only).

```clojure
;; ❌ WRONG - Emoji in UI
[:button "🔍 Search"]

;; ✅ CORRECT - Lucide icon
[:button (icons/icon :search) " Search"]
```

---

## UI Testing Checklist

When making UI changes, always test:

- [ ] **Desktop view** (1920x1080)
- [ ] **Mobile view** (375x667)
- [ ] **Dark mode** (toggle and verify all elements)
- [ ] **Keyboard navigation** (Tab, Enter, Escape)
- [ ] **Loading states** (HTMX indicators work)
- [ ] **Form validation** (error messages display)
- [ ] **JavaScript interactions** (event handlers work correctly)

## UI Development Workflow

1. **Modify Hiccup** in `src/{module}/core/ui.clj`
2. **Reload REPL** via `clj-nrepl-eval -p <port> "(ig-repl/reset)"`
3. **Refresh browser** (Cmd+R / Ctrl+R)
4. **Test in both light and dark mode**
5. **Test responsive behavior** (resize window)
6. **Commit changes** (after explicit user permission)
