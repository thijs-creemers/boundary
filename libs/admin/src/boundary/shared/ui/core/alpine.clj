(ns boundary.shared.ui.core.alpine
  "Alpine.js helper functions for Hiccup templates.

   Provides declarative attributes for common Alpine patterns while
   maintaining compatibility with HTMX server communication.

   Alpine.js handles client-side reactivity (dropdowns, state, transitions)
   while HTMX handles server communication. Alpine's MutationObserver
   automatically detects HTMX DOM changes, eliminating manual re-initialization.

   Key patterns:
   - x-data: Component state initialization
   - x-bind: Dynamic attribute binding
   - x-on/@: Event handlers
   - x-show: Conditional visibility with transitions
   - x-model: Two-way data binding
   - $persist: LocalStorage persistence
   - $store: Global reactive stores"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Core Attribute Helpers
;; =============================================================================

(defn x-data
  "Generate x-data attribute for Alpine component initialization.

   Args:
     state: Map of initial state, or string expression

   Returns:
     Map with :x-data attribute

   Example:
     (x-data {:open false})
     => {:x-data \"{open: false}\"}"
  [state]
  {:x-data (if (string? state)
             state
             (-> (pr-str state)
                 (str/replace #":(\w+)" "$1")  ; Remove keyword colons
                 (str/replace "true" "true")
                 (str/replace "false" "false")))})

(defn x-show
  "Generate x-show attribute for conditional visibility.

   Args:
     condition: JavaScript expression string

   Returns:
     Map with :x-show attribute

   Example:
     (x-show \"open\")
     => {:x-show \"open\"}"
  [condition]
  {:x-show condition})

(defn x-bind
  "Generate x-bind attribute for dynamic attribute binding.

   Args:
     attr: Attribute name (keyword or string)
     expr: JavaScript expression string

   Returns:
     Map with x-bind attribute

   Example:
     (x-bind :disabled \"selectedIds.length === 0\")
     => {:x-bind:disabled \"selectedIds.length === 0\"}"
  [attr expr]
  {(keyword (str "x-bind:" (name attr))) expr})

(defn x-on
  "Generate event handler attribute.

   Args:
     event: Event name (keyword or string), can include modifiers
     handler: JavaScript expression string

   Returns:
     Map with @ event handler attribute

   Example:
     (x-on :click \"open = !open\")
     => {:@click \"open = !open\"}

     (x-on :click.outside \"open = false\")
     => {:@click.outside \"open = false\"}"
  [event handler]
  {(keyword (str "@" (name event))) handler})

(defn x-model
  "Generate x-model attribute for two-way binding.

   Args:
     binding: JavaScript variable name

   Returns:
     Map with :x-model attribute

   Example:
     (x-model \"selectedIds\")
     => {:x-model \"selectedIds\"}"
  [binding]
  {:x-model binding})

(defn x-transition
  "Generate x-transition attribute for enter/leave animations.

   Args:
     opts: Optional map with :origin, :duration, etc.

   Returns:
     Map with x-transition attributes

   Example:
     (x-transition)
     => {:x-transition true}

     (x-transition {:origin \"top right\"})
     => {:x-transition.origin.top.right true}"
  ([]
   {:x-transition true})
  ([opts]
   (if-let [origin (:origin opts)]
     {(keyword (str "x-transition.origin." (str/replace origin " " "."))) true}
     {:x-transition true})))

(defn x-cloak
  "Generate x-cloak attribute to prevent FOUC.

   Returns:
     Map with :x-cloak attribute"
  []
  {:x-cloak true})

;; =============================================================================
;; Persistence Helpers
;; =============================================================================

(defn x-persist
  "Generate Alpine $persist expression for localStorage.

   Args:
     key: Storage key string
     initial: Initial value (will be JSON encoded)

   Returns:
     String expression using Alpine.$persist

   Example:
     (x-persist \"sidebar-state\" \"expanded\")
     => \"Alpine.$persist('expanded').as('sidebar-state')\""
  [key initial]
  (str "Alpine.$persist(" (pr-str initial) ").as('" key "')"))

;; =============================================================================
;; Pre-built Component Patterns
;; =============================================================================

(defn dropdown-attrs
  "Standard dropdown component attributes.

   Provides open/close state, click-outside handling, and escape key support.

   Returns:
     Map of Alpine attributes for dropdown behavior

   Example:
     [:div (dropdown-attrs)
      [:button (toggle-button-attrs) \"Menu\"]
      [:div {:x-show \"open\"} \"Content\"]]"
  []
  {:x-data "{open: false}"
   (keyword "@click.outside") "open = false"
   (keyword "@keydown.escape.window") "open = false"})

(defn toggle-button-attrs
  "Toggle button attributes for controlling 'open' state.

   Returns:
     Map of Alpine attributes for toggle buttons"
  []
  {(keyword "@click") "open = !open"
   :x-bind:aria-expanded "open"})

(defn collapsible-attrs
  "Collapsible panel attributes with transition.

   Args:
     initial-open: Boolean for initial state (default false)

   Returns:
     Map of Alpine attributes for collapsible content"
  ([]
   (collapsible-attrs false))
  ([initial-open]
   {:x-data (str "{open: " initial-open "}")
    :x-show "open"
    :x-transition true}))

(defn bulk-selection-attrs
  "Bulk selection container attributes for table checkbox management.

   Provides reactive selectedIds array that automatically updates
   when checkboxes change, eliminating need for htmx:afterSwap handlers.

   Returns:
     Map of Alpine attributes for bulk selection container

   Example:
     [:div (bulk-selection-attrs)
      [:input (select-all-checkbox-attrs)]
      [:input (row-checkbox-attrs id)]
      [:button (delete-button-attrs) \"Delete\"]]"
  []
  {:x-data "{selectedIds: []}"})

(defn select-all-checkbox-attrs
  "Select-all checkbox attributes for bulk selection.

   Returns:
     Map of attributes for the select-all checkbox"
  []
  {:type "checkbox"
   :x-bind:checked "selectedIds.length === document.querySelectorAll('input[name=\"ids[]\"]').length && selectedIds.length > 0"
   :x-bind:indeterminate "selectedIds.length > 0 && selectedIds.length < document.querySelectorAll('input[name=\"ids[]\"]').length"
   (keyword "@change") "selectedIds = $event.target.checked ? [...document.querySelectorAll('input[name=\"ids[]\"]')].map(cb => cb.value) : []"})

(defn row-checkbox-attrs
  "Row checkbox attributes for bulk selection.

   Args:
     id: Record ID value

   Returns:
     Map of attributes for individual row checkbox"
  [id]
  {:type "checkbox"
   :name "ids[]"
   :value (str id)
   :x-model "selectedIds"})

(defn delete-button-attrs
  "Delete button attributes that disables when nothing selected.

   Returns:
     Map of attributes for bulk delete button"
  []
  {:x-bind:disabled "selectedIds.length === 0"})

;; =============================================================================
;; Sidebar Store Initialization
;; =============================================================================

(defn sidebar-store-init
  "Generate Alpine store initialization script for sidebar state.

   This creates a global Alpine store that:
   - Persists sidebar state to localStorage
   - Handles collapse/expand on hover
   - Manages mobile drawer open/close
   - Supports keyboard shortcuts (Ctrl+B)

   Returns:
     Hiccup script element with store initialization"
  []
  [:script
   "document.addEventListener('alpine:init', () => {
      Alpine.store('sidebar', {
        state: Alpine.$persist('expanded').as('boundary-admin-sidebar-state'),
        pinned: Alpine.$persist(false).as('boundary-admin-sidebar-pinned'),
        mobileOpen: false,

        toggle() {
          this.state = this.state === 'expanded' ? 'collapsed' : 'expanded';
        },

        togglePin() {
          this.pinned = !this.pinned;
        },

        expand() {
          if (!this.pinned && window.innerWidth > 768) {
            this.state = 'expanded';
          }
        },

        collapse() {
          if (!this.pinned && window.innerWidth > 768) {
            this.state = 'collapsed';
          }
        },

        toggleMobile() {
          this.mobileOpen = !this.mobileOpen;
        },

        closeMobile() {
          this.mobileOpen = false;
        }
      });
    });"])

(defn sidebar-shell-attrs
  "Admin shell attributes for sidebar state binding.

   Returns:
     Map of Alpine attributes for admin-shell div"
  []
  {:x-data true
   :x-bind:data-sidebar-state "$store.sidebar.state"
   :x-bind:data-sidebar-pinned "$store.sidebar.pinned"
   :x-bind:data-sidebar-open "$store.sidebar.mobileOpen"
   (keyword "@keydown.ctrl.b.window.prevent") "$store.sidebar.toggle()"
   (keyword "@keydown.escape.window") "$store.sidebar.closeMobile()"})

(defn sidebar-attrs
  "Sidebar element attributes for hover expand/collapse.

   Returns:
     Map of Alpine attributes for sidebar aside element"
  []
  {(keyword "@mouseenter") "$store.sidebar.expand()"
   (keyword "@mouseleave") "$store.sidebar.collapse()"})

(defn mobile-menu-toggle-attrs
  "Mobile menu toggle button attributes.

   Returns:
     Map of attributes for mobile menu toggle"
  []
  {(keyword "@click") "$store.sidebar.toggleMobile()"
   :x-bind:aria-expanded "$store.sidebar.mobileOpen"})

(defn sidebar-overlay-attrs
  "Overlay attributes for mobile drawer.

   Returns:
     Map of attributes for overlay div"
  []
  {(keyword "@click") "$store.sidebar.closeMobile()"})

(defn sidebar-nav-link-attrs
  "Sidebar navigation link attributes for mobile drawer auto-close.

   Returns:
     Map of attributes for nav links"
  []
  {(keyword "@click") "if (window.innerWidth <= 768) $store.sidebar.closeMobile()"})
