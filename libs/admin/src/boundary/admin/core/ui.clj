(ns boundary.admin.core.ui
  "Admin UI components using Hiccup for server-side HTML generation.

   This namespace contains pure functions for generating admin interface
   Hiccup structures. Components are reusable and follow the existing
   UI patterns from boundary.shared.ui.core.

   Key responsibilities:
   - Admin layout with entity sidebar navigation
   - Entity list pages with search, sort, pagination
   - Entity detail/edit forms with field widgets
   - HTMX-powered dynamic updates
   - Field rendering based on entity configuration"
  (:require [boundary.shared.ui.core.components :as ui]
            [boundary.shared.ui.core.icons :as icons]
            [boundary.shared.ui.core.layout :as layout]
            [boundary.shared.ui.core.table :as table-ui]
            [boundary.shared.ui.core.alpine :as alpine]
            [clojure.string :as str]))

;; =============================================================================
;; URL Helpers
;; =============================================================================

(defn entity-create-url
  "Resolve the URL used for the 'New' button on an entity.

   Entities may expose a dedicated create flow via `:create-redirect-url`
   (e.g. split-table entities that cannot be created via the generic admin
   CRUD path). Falls back to `/web/admin/<entity>/new` when no override is
   configured."
  [entity-name entity-config]
  (or (:create-redirect-url entity-config)
      (str "/web/admin/" (name entity-name) "/new")))

;; =============================================================================
;; Admin Layout Components
;; =============================================================================

(defn admin-sidebar
  "Admin sidebar with entity navigation and icons.

   Args:
     entities: Vector of entity name keywords available to user
     entity-configs: Map of entity-name -> entity-config
     current-entity: Currently active entity name (optional)
     opts: Optional map; supports :logo-url for a custom brand image

   Returns:
     Hiccup sidebar structure with entity list"
  ([entities entity-configs current-entity]
   (admin-sidebar entities entity-configs current-entity {}))
  ([entities entity-configs current-entity opts]
   ;; Alpine.js sidebar - hover expand/collapse via $store.sidebar
   [:aside.admin-sidebar (alpine/sidebar-attrs)
    [:div.admin-sidebar-header
     (if-let [logo-url (:logo-url opts)]
       [:img.sidebar-brand-logo {:src logo-url :alt "" :style "height:40px; width:auto; display:block;"}]
       (icons/brand-logo {:size 140 :class "sidebar-brand-logo"}))
     [:div.sidebar-controls
      [:button.sidebar-toggle {:type "button"
                               :aria-label [:t :admin/sidebar-toggle-button]
                               :title [:t :admin/sidebar-toggle-hint]
                               (keyword "@click") "$store.sidebar.toggle()"}
       (icons/icon :panel-left {:size 20})]
      [:button.sidebar-pin {:type "button"
                            :aria-label [:t :admin/sidebar-pin-button]
                            :title [:t :admin/sidebar-pin-hint]
                            (keyword "@click") "$store.sidebar.togglePin()"
                            :x-bind:aria-pressed "$store.sidebar.pinned"}
       (icons/icon :pin {:size 20})]]]
    [:nav.admin-sidebar-nav
     [:h3 [:t :admin/sidebar-entities-title]]
     [:ul.entity-list
      (for [entity entities
            :let  [entity-config (get entity-configs entity)]
            :when (not (:sidebar-hidden entity-config))]
        (let [label      (:label entity-config (str/capitalize (name entity)))
              icon       (keyword (or (:icon entity-config) "database"))
              is-active? (= entity current-entity)]
          [:li {:class (when is-active? "active")}
           [:a (merge {:href (str "/web/admin/" (name entity))
                       :data-label label}
                      (alpine/sidebar-nav-link-attrs))
            (icons/icon icon {:size 20})
            [:span.nav-text label]]]))]]
    [:div.admin-sidebar-footer
     [:a (merge {:href "/web/dashboard"}
                (alpine/sidebar-nav-link-attrs))
      (icons/icon :home {:size 20})
      [:span.nav-text [:t :admin/sidebar-dashboard]]]
     [:a (merge {:href "/web"}
                (alpine/sidebar-nav-link-attrs))
      (icons/icon :external-link {:size 20})
      [:span.nav-text [:t :admin/sidebar-main-site]]]]]))

(defn admin-shell
  "New admin shell layout with collapsible sidebar (Phase 2).

   Args:
     content: Main content (Hiccup structure)
     opts: Map with :user, :current-entity, :entities, :entity-configs, :flash, :page-title

   Returns:
     Admin shell structure with sidebar and topbar"
  [content opts]
  (let [{:keys [user current-entity entities entity-configs page-title]} opts]
    ;; Alpine.js sidebar shell - replaces sidebar.js
    ;; State is persisted to localStorage via $store.sidebar
    ;; Note: Using a wrapper div instead of fragment [:<>] for Hiccup compatibility with Alpine.js initialization
    [:div.hiccup-fragment-wrapper
     ;; Initialize Alpine store for sidebar state management
     (alpine/sidebar-store-init)
     ;; Toast notification container
     [:div.toast-container {:role "status" :aria-live "polite"}]
     [:div.admin-shell (alpine/sidebar-shell-attrs)
      (admin-sidebar entities entity-configs current-entity opts)
      [:div.admin-overlay (alpine/sidebar-overlay-attrs)]
      [:div.admin-main
       [:header.admin-topbar
        [:button.mobile-menu-toggle (merge {:type "button"
                                            :aria-label [:t :admin/menu-open-button]}
                                           (alpine/mobile-menu-toggle-attrs))
         (icons/icon :menu {:size 24})]
        [:h1 {:class "text-lg font-semibold"} (or page-title [:t :admin/page-heading])]
        [:div.admin-topbar-actions
         [:span {:class "badge badge-ghost"} [:t :admin/header-welcome {:name (:display-name user (:email user))}]]
         (icons/theme-toggle-button)
         [:form {:method "POST" :action "/web/logout" :class "logout-form"}
          [:button {:type "submit" :class "logout-button" :aria-label [:t :admin/button-logout]}
           [:span.logout-icon
            (icons/icon :log-out {:size 18 :aria-label [:t :admin/button-logout]})]
           [:span.sr-only [:t :admin/button-logout]]]]]]
       [:main.admin-content
        content]]]]))

(defn admin-layout
  "Main admin layout with new shell structure (Phase 2).

   Args:
     content: Main content (Hiccup structure)
     opts: Map with :user, :current-entity, :entities, :entity-configs, :flash

   Returns:
     Complete HTML page structure with new admin shell"
  [content opts]
  (let [{:keys [user current-entity entity-configs flash]} opts
        title (if current-entity
                [:t :admin/page-title {:label (:label (get entity-configs current-entity))}]
                [:t :admin/page-title-dashboard])
        page-title (when current-entity
                     (:label (get entity-configs current-entity)))]
    (layout/admin-pilot-page-layout
     title
     (admin-shell content (assoc opts :page-title page-title))
     {:user user
      :flash flash
      :skip-header true})))

(defn admin-home
  "Admin dashboard home page content.

   Args:
     entities: Vector of available entity names
     entity-configs: Map of entity configurations
     stats: Optional map of dashboard statistics

   Returns:
     Hiccup structure for dashboard"
  [entities entity-configs & [stats]]
  [:div.admin-home {:class "space-y-4"}
   [:section.admin-home-hero
    [:div.admin-home-hero-inner
     [:div
      [:span.admin-home-kicker [:t :admin/page-kicker]]
      [:h1 [:t :admin/page-heading]]
      [:p [:t :admin/page-description]]]]]
   [:div.entity-grid
    (for [entity entities]
      (let [entity-config (get entity-configs entity)
            label (:label entity-config)
            description (:description entity-config)
            icon (keyword (or (:icon entity-config) "database"))
            count (get-in stats [entity :count] 0)]
        [:div.entity-card
         [:a {:href (str "/web/admin/" (name entity))
              :class "entity-card-link"}
          [:div.entity-card-icon (icons/icon icon {:size 20})]
          [:div.entity-card-title label]
          (when description
            [:div.entity-card-description description])
          [:div.entity-card-meta
           [:span.entity-card-count [:t :admin/entity-card-count {:count count}]]]]]))
    [:div.entity-card
     [:a {:href "/web/audit" :class "entity-card-link"}
      [:div.entity-card-icon (icons/icon :file-text {:size 20})]
      [:div.entity-card-title [:t :admin/audit-trail-title]]
      [:div.entity-card-description [:t :admin/audit-trail-description]]]]]])

;; =============================================================================
;; Entity List Components
;; =============================================================================

(defn entity-search-form
  "Compact inline search form for entity list filtering.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     current-search: Current search term (optional)
     current-filters: Current filter values (optional)

    Returns:
      Hiccup search form structure"
  [entity-name entity-config current-search _current-filters]
  (let [search-fields (:search-fields entity-config)
        has-search? (seq search-fields)]
    (when has-search?
      [:div.entity-search-form
       [:form {:hx-get (str "/web/admin/" (name entity-name) "/table")
               :hx-target "#entity-table-container"
               :hx-push-url "true"
               :hx-trigger "submit"}
        [:div.search-controls
         [:input {:type "text"
                  :name "search"
                  :placeholder [:t :admin/filter-placeholder-search {:fields (str/join ", " (map name search-fields))}]
                  :value (or current-search "")
                  :autofocus true
                  :class "search-input"}]
         [:button.icon-button {:type "submit" :aria-label [:t :common/button-search]}
          (icons/icon :search {:size 20})]
         (when (seq current-search)
           [:button.icon-button.secondary {:type "button"
                                           :aria-label [:t :admin/button-clear-search]
                                           :onclick (str "window.location.href='/web/admin/" (name entity-name) "';")}
            (icons/icon :x {:size 20})])]]])))

;; =============================================================================
;; Advanced Filter Components (Week 2)
;; =============================================================================

(defn get-operators-for-field-type
  "Get available filter operators for a field type.
   
   Args:
     field-type: Keyword field type (:string, :int, :boolean, etc.)
   
   Returns:
     Vector of [operator-keyword display-label] tuples"
  [field-type]
  (case field-type
    :string [[:eq [:t :admin/filter-op-equals]]
             [:ne [:t :admin/filter-op-not-equals]]
             [:contains [:t :admin/filter-op-contains]]
             [:starts-with [:t :admin/filter-op-starts-with]]
             [:ends-with [:t :admin/filter-op-ends-with]]
             [:is-null [:t :admin/filter-op-is-empty]]
             [:is-not-null [:t :admin/filter-op-is-not-empty]]]

    (:int :decimal) [[:eq [:t :admin/filter-op-equals]]
                     [:ne [:t :admin/filter-op-not-equals]]
                     [:gt [:t :admin/filter-op-greater-than]]
                     [:gte [:t :admin/filter-op-gte]]
                     [:lt [:t :admin/filter-op-less-than]]
                     [:lte [:t :admin/filter-op-lte]]
                     [:between [:t :admin/filter-op-between]]
                     [:is-null [:t :admin/filter-op-is-empty]]
                     [:is-not-null [:t :admin/filter-op-is-not-empty]]]

    (:instant :date) [[:eq [:t :admin/filter-op-on-date]]
                      [:ne [:t :admin/filter-op-not-on-date]]
                      [:gt [:t :admin/filter-op-after]]
                      [:gte [:t :admin/filter-op-on-or-after]]
                      [:lt [:t :admin/filter-op-before]]
                      [:lte [:t :admin/filter-op-on-or-before]]
                      [:between [:t :admin/filter-op-between]]
                      [:is-null [:t :admin/filter-op-is-empty]]
                      [:is-not-null [:t :admin/filter-op-is-not-empty]]]

    :boolean [[:eq [:t :admin/filter-op-equals]]]

    :enum [[:eq [:t :admin/filter-op-equals]]
           [:ne [:t :admin/filter-op-not-equals]]
           [:in [:t :admin/filter-op-any-of]]
           [:not-in [:t :admin/filter-op-none-of]]]

    ;; Default for unknown types
    [[:eq [:t :admin/filter-op-equals]]
     [:ne [:t :admin/filter-op-not-equals]]
     [:is-null [:t :admin/filter-op-is-empty]]
     [:is-not-null [:t :admin/filter-op-is-not-empty]]]))

(defn render-filter-value-inputs
  "Render value input(s) for a filter based on operator and field type.
   
   Args:
     field-name: Keyword field name
     field-config: Field configuration map
     operator: Current operator keyword
     filter-value: Current filter value map
   
   Returns:
     Hiccup input structure(s)"
  [field-name field-config operator filter-value]
  (let [field-type (:type field-config :string)
        options (:options field-config)]
    (cond
      ;; No input needed for null checks
      (#{:is-null :is-not-null} operator)
      nil

      ;; Between needs two inputs (min/max)
      (= operator :between)
      [:div.filter-value-range
       [:input.filter-value-input
        {:type (if (#{:int :decimal} field-type) "number" "text")
         :name (str "filters[" (name field-name) "][min]")
         :placeholder [:t :admin/filter-range-min]
         :value (get filter-value :min "")}]
       [:span.range-separator [:t :admin/filter-range-separator]]
       [:input.filter-value-input
        {:type (if (#{:int :decimal} field-type) "number" "text")
         :name (str "filters[" (name field-name) "][max]")
         :placeholder [:t :admin/filter-range-max]
         :value (get filter-value :max "")}]]

      ;; Multi-select for :in and :not-in operators
      (#{:in :not-in} operator)
      (if options
        ;; Enum field with predefined options - show multi-select
        [:select.filter-value-input
         {:name (str "filters[" (name field-name) "][values][]")
          :multiple true
          :size (min 5 (count options))}
         (for [[value label] options]
           [:option {:value (name value)
                     :selected (some #{value} (:values filter-value))}
            label])]
        ;; Free-form multi-value input (comma-separated)
        [:input.filter-value-input
         {:type "text"
          :name (str "filters[" (name field-name) "][values]")
          :placeholder "value1, value2, value3"
          :value (str/join ", " (or (:values filter-value) []))}])

      ;; Boolean field - dropdown
      (= field-type :boolean)
      [:select.filter-value-input
       {:name (str "filters[" (name field-name) "][value]")}
       [:option {:value ""} [:t :admin/select-placeholder]]
       [:option {:value "true" :selected (= (:value filter-value) true)} [:t :common/option-yes]]
       [:option {:value "false" :selected (= (:value filter-value) false)} [:t :common/option-no]]]

      ;; Enum field with options - dropdown
      (and (= field-type :enum) options)
      [:select.filter-value-input
       {:name (str "filters[" (name field-name) "][value]")}
       [:option {:value ""} [:t :admin/select-placeholder]]
       (for [[value label] options]
         [:option {:value (name value)
                   :selected (= (:value filter-value) value)}
          label])]

      ;; Date/instant fields - date input
      (#{:instant :date} field-type)
      [:input.filter-value-input
       {:type "date"
        :name (str "filters[" (name field-name) "][value]")
        :value (or (:value filter-value) "")}]

      ;; Numeric fields - number input
      (#{:int :decimal} field-type)
      [:input.filter-value-input
       {:type "number"
        :name (str "filters[" (name field-name) "][value]")
        :placeholder [:t :admin/filter-enter-value]
        :value (or (:value filter-value) "")}]

      ;; Default: text input
      :else
      [:input.filter-value-input
       {:type "text"
        :name (str "filters[" (name field-name) "][value]")
        :placeholder [:t :admin/filter-enter-value]
        :value (or (:value filter-value) "")}])))

(defn render-filter-row
  "Render a single filter row with field, operator, value inputs.
   
   Args:
     entity-name: Keyword entity name
     field-name: Keyword field name
     field-config: Field configuration map
     filter-value: Current filter value map (with :op, :value, etc.)
   
   Returns:
     Hiccup filter row structure"
  [entity-name field-name field-config filter-value]
  (let [field-type (:type field-config :string)
        operators (get-operators-for-field-type field-type)
        current-op (:op filter-value :eq)
        field-label (:label field-config (str/capitalize (name field-name)))]
    [:div.filter-row
     ;; Field name (read-only label)
     [:span.filter-field-label field-label]

      ;; Operator selector
     [:select.filter-operator-select
      {:name (str "filters[" (name field-name) "][op]")
       :hx-get (str "/web/admin/" (name entity-name) "/table")
       :hx-target "#filter-table-container"
       :hx-trigger "change"
       :hx-include "closest form"}
      (for [[op label] operators]
        [:option {:value (name op)
                  :selected (= current-op op)}
         label])]

     ;; Value input(s) - changes based on operator
     (render-filter-value-inputs field-name field-config current-op filter-value)

      ;; Remove filter button
     [:button.icon-button.secondary
      {:type "button"
       :aria-label [:t :admin/button-remove-filter]
       :hx-get (str "/web/admin/" (name entity-name) "/table")
       :hx-target "#filter-table-container"
       :hx-trigger "click"
       :hx-vals (str "{\"remove_filter\": \"" (name field-name) "\"}")
       :hx-include "closest form"}
      (icons/icon :x {:size 16})]]))

(defn render-filter-builder
  "Render advanced filter builder UI.
   
   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     current-filters: Map of current filters {field-name -> filter-spec}
   
   Returns:
     Hiccup filter builder structure"
  [entity-name entity-config current-filters]
  (let [;; Use :list-fields as the candidate set — those are the fields the user
        ;; already sees in the table. Fall back to all known fields for entities
        ;; without a manual :list-fields.
        candidate-fields (or (seq (:list-fields entity-config))
                             (keys (:fields entity-config)))
        filterable-fields (filter #(get-in entity-config [:fields % :filterable] true)
                                  candidate-fields)
        has-active-filters? (seq current-filters)
        current-filters (or current-filters {})]
    (when (or (seq filterable-fields) (seq current-filters))
      [:div.filter-builder
       [:div.filter-builder-header
        [:span.filter-builder-title [:t :admin/filters-title]]
        (when has-active-filters?
          [:button.text-button
           {:type "button"
            :hx-get (str "/web/admin/" (name entity-name) "/table")
            :hx-target "#filter-table-container"
            :hx-trigger "click"}
           (icons/icon :x {:size 14})
           " " [:t :admin/button-clear-all-filters]])]

      ;; Form wrapper for all filters
       [:form.filter-form
        {:hx-get (str "/web/admin/" (name entity-name) "/table")
         :hx-target "#filter-table-container"
         :hx-trigger "submit, change delay:500ms from:find input, change from:find select"
         :hx-push-url "true"}

      ;; Active filter rows
        (when has-active-filters?
          [:div.filter-rows
           (for [[field-name filter-value] current-filters]
             (when-let [field-config (get-in entity-config [:fields field-name])]
               (render-filter-row entity-name field-name field-config filter-value)))])

      ;; Add filter dropdown
        (when (seq filterable-fields)
          [:div.filter-add-row
           [:select.filter-field-select
            {:name "add_filter_field"
             :hx-get (str "/web/admin/" (name entity-name) "/table")
             :hx-target "#filter-table-container"
             :hx-trigger "change"
             :hx-include "closest form"}
            [:option {:value ""} [:t :admin/filter-add-option]]
            (for [field-name filterable-fields
                  :when (not (contains? current-filters field-name))]
              (let [field-config (get-in entity-config [:fields field-name])
                    field-label (:label field-config (str/capitalize (name field-name)))]
                [:option {:value (name field-name)} field-label]))]])

      ;; Apply button (for manual submission if auto-trigger doesn't work)
        (when has-active-filters?
          [:button.button.primary {:type "submit"} [:t :admin/button-apply-filters]])]])))

(defn render-field-value
  "Render field value for display in table or detail view.

   Args:
     field-name: Keyword field name
     value: Field value to render
     field-config: Field configuration map

    Returns:
      Hiccup structure or string for display"
  [_field-name value field-config]
  (let [field-type (:type field-config :string)]
    (cond
      (nil? value)
      [:span.null-value {:class "badge ui-badge ui-badge-neutral null-value"} "—"]

      (= field-type :boolean)
      (ui/badge (if value [:t :common/option-yes] [:t :common/option-no])
                {:variant (if value :success :neutral)
                 :class (str "admin-bool-badge "
                             (if value "admin-bool-badge-true" "admin-bool-badge-false"))})

      (= field-type :instant)
      (str value)

      (= field-type :date)
      (str value)

      (= field-type :uuid)
      [:span.uuid-value {:class "font-mono text-xs opacity-80"} (str value)]

      (= field-type :enum)
      [:span.enum-badge {:class "badge ui-badge ui-badge-outline enum-badge"}
       (str/capitalize (name value))]

      (= field-type :json)
      [:code (str value)]

      (string? value)
      (if (> (count value) 50)
        (str (subs value 0 47) "...")
        value)

      :else
      (str value))))

(defn entity-table-row
  "Generate entity table row.

   Args:
     entity-name: Keyword entity name
     record: Entity record map
     entity-config: Entity configuration map
     permissions: Permission flags for this entity

   Returns:
     Hiccup table row"
  [entity-name record entity-config permissions]
  (let [list-fields (:list-fields entity-config)
        primary-key (:primary-key entity-config :id)
        record-id (get record primary-key)
        readonly-fields (set (:readonly-fields entity-config))]
    [:tr {:class "entity-row clickable-row"
          :data-href (str "/web/admin/" (name entity-name) "/" record-id)}
     [:td.checkbox-cell
       ;; Alpine.js row checkbox with x-model binding to selectedIds array
      [:input (alpine/row-checkbox-attrs record-id)]]
     (for [field list-fields]
       (let [field-config (get-in entity-config [:fields field])
             field-label (:label field-config (str/capitalize (name field)))
             value (get record field)
             editable? (and (:can-edit permissions)
                            (not (contains? readonly-fields field))
                            (not= field primary-key))]
         (if editable?
           ; Editable cell with double-click to edit (Week 2)
           [:td {:class (str "field-" (name field) " editable")
                 :data-label field-label
                 :hx-get (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/edit")
                 :hx-trigger "dblclick"
                 :hx-target "this"
                 :hx-swap "innerHTML"
                 :title [:t :admin/cell-dblclick-hint]}
            (render-field-value field value field-config)]
           ; Non-editable cell
           [:td {:class (str "field-" (name field))
                 :data-label field-label}
            (render-field-value field value field-config)])))
     [:td.actions-cell
      (when (:can-edit permissions)
        [:a.icon-button.secondary
         {:href (str "/web/admin/" (name entity-name) "/" record-id)
          :aria-label [:t :common/button-edit]}
         (icons/icon :edit {:size 18})])
      [:span.row-nav-hint
       (icons/icon :chevron-right {:size 14})]]]))

;; =============================================================================
;; Inline Editing Components (Week 2)
;; =============================================================================

(defn render-inline-edit-cell
  "Render an editable table cell (normal display mode).

   Args:
     entity-name: Keyword entity name
     record-id: Record ID
     field: Keyword field name
     value: Current field value
     field-config: Field configuration map

   Returns:
     Hiccup td element"
  [entity-name record-id field value field-config]
  (let [field-label (:label field-config (str/capitalize (name field)))]
    [:td {:class (str "field-" (name field) " editable")
          :data-label field-label
          :hx-get (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/edit")
          :hx-trigger "dblclick"
          :hx-target "this"
          :hx-swap "innerHTML"
          :title [:t :admin/cell-dblclick-hint]}
     [:span.cell-content
      (render-field-value field value field-config)]
     [:span.inline-edit-hint
      (icons/icon :pencil {:size 14})
      " " [:t :common/button-edit]]]))

(defn render-inline-edit-form
  "Render inline edit form for a single field.

   Args:
     entity-name: Keyword entity name
     record-id: Record ID
     field: Keyword field name
     value: Current field value
     field-config: Field configuration map

   Returns:
     Hiccup form structure"
  [entity-name record-id field value field-config]
  (let [widget-type (:widget field-config :text-input)
        _field-type (:type field-config :string)
        required? (:required field-config false)
        _cancel-url (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/cancel")]
    [:form.inline-edit-form
     {:hx-patch (str "/web/admin/" (name entity-name) "/" record-id "/" (name field))
      :hx-target "closest td"
      :hx-swap "outerHTML"
      :onsubmit "event.preventDefault(); htmx.trigger(this, 'submit');"}

     ; Render appropriate input widget
     (cond
       (= widget-type :checkbox)
       [:input {:type "checkbox"
                :name (name field)
                :checked (boolean value)
                :autofocus true}]

       (= widget-type :textarea)
       [:textarea.inline-input
        {:name (name field)
         :required required?
         :autofocus true
         :rows 2}
        (str value)]

       (= widget-type :number-input)
       [:input.inline-input
        {:type "number"
         :name (name field)
         :value (str value)
         :required required?
         :autofocus true}]

       ; Default: text input
       :else
       [:input.inline-input
        {:type "text"
         :name (name field)
         :value (str value)
         :required required?
         :autofocus true}])

     ; Action buttons
     [:span.inline-actions
      [:button.inline-save {:type "submit" :title [:t :admin/inline-save]}
       (icons/icon :check {:size 14})]
      [:button.inline-cancel
       {:type "button"
        :title [:t :admin/inline-cancel]
        :hx-get (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/cancel")
        :hx-target "closest td"
        :hx-swap "outerHTML"}
       (icons/icon :x {:size 14})]]]))

(defn render-inline-edit-form-with-error
  "Render inline edit form with validation error.

   Args:
     entity-name: Keyword entity name
     record-id: Record ID
     field: Keyword field name
     value: Current (invalid) field value
     field-config: Field configuration map
     errors: Collection of error messages

   Returns:
     Hiccup form structure with error display"
  [entity-name record-id field value field-config errors]
  (let [widget-type (:widget field-config :text-input)
        _field-type (:type field-config :string)
        required? (:required field-config false)]
    [:div.inline-edit-error
     [:form.inline-edit-form
      {:hx-patch (str "/web/admin/" (name entity-name) "/" record-id "/" (name field))
       :hx-target "closest td"
       :hx-swap "outerHTML"}

      ; Render input with error class
      (cond
        (= widget-type :checkbox)
        [:input {:type "checkbox"
                 :name (name field)
                 :checked (boolean value)
                 :class "error"
                 :autofocus true}]

        (= widget-type :textarea)
        [:textarea.inline-input.error
         {:name (name field)
          :required required?
          :autofocus true
          :rows 2}
         (str value)]

        (= widget-type :number-input)
        [:input.inline-input.error
         {:type "number"
          :name (name field)
          :value (str value)
          :required required?
          :autofocus true}]

        ; Default: text input
        :else
        [:input.inline-input.error
         {:type "text"
          :name (name field)
          :value (str value)
          :required required?
          :autofocus true}])

      ; Action buttons
      [:span.inline-actions
       [:button.inline-save {:type "submit" :title [:t :admin/inline-save]}
        (icons/icon :check {:size 14})]
       [:button.inline-cancel
        {:type "button"
         :title [:t :admin/inline-cancel]
         :hx-get (str "/web/admin/" (name entity-name) "/" record-id "/" (name field) "/cancel")
         :hx-target "closest td"
         :hx-swap "outerHTML"}
        (icons/icon :x {:size 14})]]]

     ; Error message
     [:div.inline-error-message
      (str/join ", " errors)]]))

(defn entity-table
  "Generate entity table with sorting and pagination.

   Args:
     entity-name: Keyword entity name
     records: Collection of entity records
     entity-config: Entity configuration map
     table-query: Table query parameters (sort, page, etc.)
     total-count: Total number of records
     permissions: Permission flags
     filters: Optional search filters

   Returns:
     Hiccup table structure"
  [entity-name records entity-config table-query total-count permissions & [filters]]
  (let [{:keys [sort dir page page-size]} table-query
        base-url (str "/web/admin/" (name entity-name) "/table")
        hx-target "#entity-table-container"
        table-params (table-ui/table-query->params table-query)
        filter-params (table-ui/search-filters->params (or filters {}))
        qs-map (merge table-params filter-params)
        hx-url (str base-url "?" (table-ui/encode-query-params qs-map))
        list-fields (:list-fields entity-config)]
    ;; Table container - Alpine.js scope is at parent entity-list-page level
    ;; MutationObserver automatically handles HTMX DOM updates (no afterSwap needed)
    [:div#entity-table-container
     {:hx-get hx-url
      :hx-trigger "entityCreated from:body, entityUpdated from:body, entityDeleted from:body"
      :hx-target hx-target}
     (if (empty? records)
       [:div.empty-state {:class "p-10 text-center"}
        [:div.empty-state-icon
         (icons/icon :inbox {:size 48})]
        [:p {:class "mt-2 text-base-content/70"} [:t :admin/empty-state-no-records]]
        (when (:can-create permissions)
          [:a.button.primary
           {:class "mt-4"
            :href (entity-create-url entity-name entity-config)}
           [:t :admin/button-create-first-record]])]
       [:div.table-wrapper
        ;; Form for checkbox submission (hidden inputs + table)
        [:form#table-form
         {:hx-post (str "/web/admin/" (name entity-name) "/bulk")
          :hx-target hx-target
          :hx-swap "outerHTML"}
         ;; Preserve table state
         (for [[k v] table-params]
           [:input {:type "hidden" :name k :value v}])
         (for [[k v] filter-params]
           [:input {:type "hidden" :name k :value v}])

         [:table.data-table {:class "data-table table"}
           ;; Explicit column widths for table-layout: fixed
          [:colgroup
           [:col {:class "col-select"}]  ; Checkbox
           (for [_ list-fields]
             [:col])  ; Auto-width for data columns
           [:col {:class "col-actions"}]]  ; Actions
          [:thead
           [:tr
            [:th {:class "checkbox-header"}
             ;; Alpine.js select-all checkbox with reactive binding
             [:input (alpine/select-all-checkbox-attrs)]]
            (for [field list-fields]
              (let [field-config (get-in entity-config [:fields field])
                    sortable? (:sortable field-config true)]
                (if sortable?
                  (table-ui/sortable-th {:label (:label field-config (str/capitalize (name field)))
                                         :field field
                                         :current-sort sort
                                         :current-dir dir
                                         :base-url base-url
                                         :push-url-base (str "/web/admin/" (name entity-name))
                                         :page page
                                         :page-size page-size
                                         :hx-target hx-target
                                         :hx-push-url? true
                                         :extra-params filters})
                  [:th (:label field-config (str/capitalize (name field)))])))
            [:th {:class "actions-header"} [:t :admin/column-actions]]]]
          [:tbody
           (for [record records]
             (entity-table-row entity-name record entity-config permissions))]]]
        (table-ui/pagination {:table-query table-query
                              :total-count total-count
                              :base-url base-url
                              :push-url-base (str "/web/admin/" (name entity-name))
                              :hx-target hx-target
                              :extra-params filters})])]))

(defn entity-list-page
  "Complete entity list page with search, table, and actions.

   Args:
     entity-name: Keyword entity name
     records: Collection of entity records
     entity-config: Entity configuration map
     table-query: Table query parameters
     total-count: Total number of records
     permissions: Permission flags
     opts: Optional map with :search, :filters, :flash

   Returns:
     Hiccup page structure"
  [entity-name records entity-config table-query total-count permissions & [opts]]
  (let [{:keys [search filters flash]} opts
        label (:label entity-config)
        search-fields (:search-fields entity-config)
        has-search? (seq search-fields)
        search-value (:search search)]
    ;; Alpine.js bulk selection scope - wraps toolbar and table
    ;; selectedIds array is shared between delete button and checkboxes
    [:div.entity-list-page (merge (alpine/bulk-selection-attrs)
                                  {:class "space-y-4"})
     (when flash
       (for [[type message] flash]
         [:div {:class (str "alert alert-" (name type) " mb-2")} message]))

     [:section.entity-list-hero
      [:div.entity-list-hero-inner
       [:div.entity-list-title-group
        [:span.entity-list-kicker [:t :admin/list-kicker]]
        [:h1.entity-list-title label]
        [:p.entity-list-subtitle
         [:t :admin/list-description {:label (str/lower-case label) :count total-count}]]]
       [:div.entity-list-hero-actions {:class "flex items-center gap-2"}
        (when (:can-create permissions)
          [:a.button.primary
           {:class "gap-2"
            :href (entity-create-url entity-name entity-config)
            :aria-label (str "Create new " (name entity-name))}
           (icons/icon :plus {:size 18})
           [:t :admin/button-new {:entity (str/capitalize (name entity-name))}]])]]]

       ;; Consolidated toolbar (OUTSIDE HTMX target - won't be replaced)
     [:div.table-toolbar-container {:class "space-y-3"}
        ;; Search bar (separate row)
      [:div.toolbar-row-search
       (when has-search?
         [:div.toolbar-search {:class "flex items-center gap-2"}
          [:input.search-input {:type "text"
                                :name "search"
                                :class "search-input w-full"
                                :placeholder [:t :admin/filter-placeholder-search {:fields (str/join ", " (map name search-fields))}]
                                :value (or search-value "")
                                :hx-get (str "/web/admin/" (name entity-name) "/table")
                                :hx-target "#entity-table-container"
                                :hx-push-url "true"
                                :hx-trigger "keyup changed delay:300ms, search"
                                :hx-include "this"}]
          [:button.icon-button {:type "button"
                                :aria-label [:t :common/button-search]
                                :hx-get (str "/web/admin/" (name entity-name) "/table")
                                :hx-target "#entity-table-container"
                                :hx-push-url "true"
                                :hx-include "previous .search-input"}
           (icons/icon :search {:size 20})]
          (when (seq search-value)
            [:button.icon-button.secondary {:type "button"
                                            :aria-label [:t :admin/button-clear-search]
                                            :onclick (str "window.location.href='/web/admin/" (name entity-name) "';")}
             (icons/icon :x {:size 20})])])]

       ;; Actions row (delete + create buttons)
      [:div.toolbar-row-actions {:class "gap-3"}
       [:div.record-meta {:class "flex items-center gap-2"}
        [:span.record-count
         (str total-count " " label)]
        [:span.selection-count
         {:x-show "selectedIds.length > 0"
          :x-text "selectedIds.length + ' selected'"}]]

         ;; Delete button - Alpine.js reactively disables when nothing selected
       [:form#bulk-action-form
        {:hx-post (str "/web/admin/" (name entity-name) "/bulk-delete")
         :hx-target "#entity-table-container"
         :hx-swap "outerHTML"
         :hx-include "[name='ids[]']"}
        [:button.icon-button.danger
         (merge (alpine/delete-button-attrs)
                {:type "submit"
                 :name "action"
                 :value "delete"
                 :id "bulk-delete-btn"
                 :form "bulk-action-form"
                 :aria-label [:t :admin/button-delete-selected]
                 :hx-confirm [:t :admin/confirm-delete-selected]
                 :data-confirm-title [:t :admin/modal-confirm-delete-title]
                 :data-confirm-cancel [:t :admin/modal-button-cancel]
                 :data-confirm-label [:t :admin/modal-button-delete]})
         (icons/icon :trash {:size 18})]]

       [:div.toolbar-actions {:class "flex items-center gap-2"}
        [:button.icon-button.ghost {:type "button"
                                    :aria-label [:t :admin/button-refresh]
                                    :hx-get (str "/web/admin/" (name entity-name) "/table")
                                    :hx-target "#entity-table-container"
                                    :hx-push-url "true"}
         (icons/icon :refresh {:size 18})]]]]

       ;; Filter builder + Table wrapper (THIS is the HTMX target for filter updates)
     [:div#filter-table-container {:class "space-y-3"}
      ;; Filter builder (will be updated by HTMX)
      (render-filter-builder entity-name entity-config filters)
      ;; Table (will also be updated by HTMX)
      (entity-table entity-name records entity-config table-query total-count permissions filters)]]))

;; =============================================================================
;; Entity Detail/Edit Components
;; =============================================================================

(defn- field-width-class
  "Determine CSS width class for a form field based on widget type.
   
   Args:
     widget-type: Keyword widget type
   
   Returns:
     String CSS class ('full-width' or 'half-width')"
  [widget-type]
  (if (#{:textarea :file-input :hidden} widget-type)
    "full-width"
    "half-width"))

(defn render-field-widget
  "Render input widget for field based on its configuration.

   Args:
     field-name: Keyword field name
     value: Current field value
     field-config: Field configuration map
     errors: Optional collection of error messages for this field

   Returns:
     Hiccup form field structure"
  [field-name value field-config errors]
  (let [widget-type (:widget field-config :text-input)
        label (:label field-config (str/capitalize (name field-name)))
        required? (:required field-config false)
        readonly? (:readonly field-config false)
        placeholder (:placeholder field-config)
        help-text (:help-text field-config)
        options (:options field-config)
        field-type (:type field-config :string)
        min (:min field-config)
        max (:max field-config)
        pattern (:pattern field-config)
        width-class (field-width-class widget-type)
        has-errors? (seq errors)]
    [:div.form-field {:class (str width-class
                                  (when required? " is-required")
                                  (when has-errors? " has-errors"))}
     [:label {:for (name field-name)}
      label
      (when required? [:span.required " *"])]
     (cond
       ;; Text input widgets
       (= widget-type :text-input)
       (ui/text-input field-name value
                      (merge {:placeholder placeholder
                              :required required?
                              :readonly readonly?
                              :class "form-control"}
                             (when pattern {:pattern pattern})
                             (when (and (= field-type :string) min) {:minlength min})
                             (when (and (= field-type :string) max) {:maxlength max})))

       (= widget-type :email-input)
       (ui/email-input field-name value
                       {:placeholder placeholder
                        :required required?
                        :readonly readonly?
                        :class "form-control"})

       (= widget-type :password-input)
       (ui/password-input field-name value
                          {:placeholder placeholder
                           :required required?
                           :readonly readonly?
                           :class "form-control"
                           :autocomplete "new-password"})

       (= widget-type :url-input)
       (ui/text-input field-name value
                      {:type "url"
                       :placeholder placeholder
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       (= widget-type :number-input)
       (ui/text-input field-name value
                      (merge {:type "number"
                              :placeholder placeholder
                              :required required?
                              :readonly readonly?
                              :class "form-control"}
                             (when min {:min min})
                             (when max {:max max})))

       ;; Boolean input
       (= widget-type :checkbox)
       (list
        (ui/checkbox field-name value {:required required?
                                       :disabled readonly?
                                       :class "checkbox checkbox-sm"})
        (when help-text
          [:span.help-text help-text]))

       ;; Select/dropdown
       (= widget-type :select)
       (ui/select-field field-name options value
                        {:required required?
                         :disabled readonly?
                         :class "form-control"})

       ;; Textarea
       (= widget-type :textarea)
       (ui/textarea field-name value
                    {:placeholder placeholder
                     :required required?
                     :readonly readonly?
                     :class "form-control"
                     :rows (or (:rows field-config) 4)})

       ;; Date/time inputs
       (= widget-type :date-input)
       (ui/text-input field-name value
                      {:type "date"
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       (= widget-type :datetime-input)
       (ui/text-input field-name value
                      {:type "datetime-local"
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       ;; Color picker
       (= widget-type :color-input)
       (ui/text-input field-name value
                      {:type "color"
                       :required required?
                       :readonly readonly?
                       :class "form-control"})

       ;; File upload
       (= widget-type :file-input)
       [:input {:type "file"
                :id (name field-name)
                :name (name field-name)
                :required required?
                :disabled readonly?
                :class "form-control"}]

       ;; Hidden field
       (= widget-type :hidden)
       [:input {:type "hidden"
                :id (name field-name)
                :name (name field-name)
                :value (or value "")}]

       ;; Default fallback
       :else
       (ui/text-input field-name value
                      {:placeholder placeholder
                       :required required?
                       :readonly readonly?
                       :class "form-control"}))

     (when help-text
       [:small.help-text help-text])
     (when (seq errors)
       [:div.field-errors
        (for [error errors]
          [:span.error error])])]))

;; =============================================================================
;; Field Grouping Helpers
;; =============================================================================

(defn- compute-field-groups
  "Compute field groups for form rendering.

   Takes configured :field-groups and :editable-fields and returns a vector
   of group maps with only the fields that are actually editable.
   Ungrouped editable fields are appended as a final 'Other' group.

   Args:
     entity-config: Entity configuration map with :field-groups, :editable-fields, :ui

   Returns:
     Vector of group maps: [{:id :identity :label \"Identity\" :fields [:email :name]} ...]
     Each group only contains fields that are in :editable-fields.
     Returns nil if no :field-groups configured (use flat rendering)."
  [entity-config]
  (when-let [field-groups (:field-groups entity-config)]
    (let [editable-set (set (:editable-fields entity-config []))
          ;; Filter each group's fields to only include editable ones
          groups-with-editable (for [group field-groups
                                     :let [editable-in-group (filterv editable-set (:fields group))]
                                     ;; Only include groups that have at least one editable field
                                     :when (seq editable-in-group)]
                                 (assoc group :fields editable-in-group))
          ;; Collect all fields that are in configured groups
          grouped-fields (set (mapcat :fields groups-with-editable))
          ;; Find ungrouped editable fields (preserve order from editable-fields)
          ungrouped-fields (filterv #(not (grouped-fields %))
                                    (:editable-fields entity-config []))]
      (if (seq ungrouped-fields)
        ;; Append "Other" group for ungrouped fields
        (let [other-label (get-in entity-config [:ui :field-grouping :other-label] [:t :admin/fieldgroup-other])]
          (conj (vec groups-with-editable)
                {:id :other
                 :label other-label
                 :fields ungrouped-fields}))
        ;; All fields are grouped
        (vec groups-with-editable)))))

(defn- render-field-group
  "Render a single field group with heading and fields.

   Args:
     group: Group map with :id, :label, :fields
     entity-config: Entity configuration map
     record: Entity record (nil for create)
     errors: Validation errors map

   Returns:
     Hiccup fieldset/group structure"
  [group entity-config record errors]
  [:div.form-field-group {:class "form-field-group"
                          :data-group-id (name (:id group))}
   [:h3.form-section-title (:label group)]
   [:div.form-fields
    (for [field-name (:fields group)]
      (let [field-config (get-in entity-config [:fields field-name])
            field-value (get record field-name)
            field-errors (get errors field-name)]
        (render-field-widget field-name field-value field-config field-errors)))]])

(defn entity-form
  "Render entity create/edit form.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     record: Entity record (nil for create)
     errors: Validation errors map
     permissions: Permission flags

    Returns:
      Hiccup form structure

   Notes:
     If :field-groups is configured, renders fields in grouped sections.
     Otherwise, renders flat list of editable fields."
  [entity-name entity-config record errors _permissions & [cancel-url]]
  (let [editable-fields (:editable-fields entity-config)
        field-groups (compute-field-groups entity-config)
        primary-key (:primary-key entity-config :id)
        record-id (get record primary-key)
        is-edit? (some? record)
        form-action-base (if is-edit?
                           (str "/web/admin/" (name entity-name) "/" record-id)
                           (str "/web/admin/" (name entity-name)))
        default-list-url (str "/web/admin/" (name entity-name))
        ; Preserve return_to through form submission so the update handler can redirect back
        form-action (if (and is-edit? cancel-url (not= cancel-url default-list-url))
                      (str form-action-base "?return_to=" cancel-url)
                      form-action-base)
        _form-method (if is-edit? "PUT" "POST")
        hx-attr (if is-edit? :hx-put :hx-post)
        required-fields (filter (fn [field-name]
                                  (true? (get-in entity-config [:fields field-name :required])))
                                editable-fields)
        optional-fields (filter (fn [field-name]
                                  (not (true? (get-in entity-config [:fields field-name :required]))))
                                editable-fields)
        optional-details-key (str "admin_optional_details_open_" (name entity-name))]
    [:form.entity-form
     (merge {hx-attr form-action
             :hx-target "body"
             :hx-swap "outerHTML"
             "hx-on::afterRequest" (str "if (event.detail.successful) { localStorage.setItem('"
                                        optional-details-key
                                        "', 'false'); }")}
            ;; After a successful edit, push the same URL (including return_to) so the
            ;; browser stays on the detail page with context intact
            (when is-edit?
              {:hx-push-url form-action}))
     ;; No longer need hidden _method field since HTMX sends proper HTTP method
     [:div.form-card {:class "form-card overflow-hidden"}
      [:div.form-card-body {:class "form-card-body space-y-4"}
       [:div.form-meta
        [:span.form-meta-label [:t :admin/form-required-fields-note]]]
       (cond
         ;; Priority 1: Use configured field groups if present
         field-groups
         [:div.form-sections {:class "form-sections"}
          (for [group field-groups]
            (render-field-group group entity-config record errors))]

         ;; Priority 2: Split into required/optional sections if both exist
         (and (seq required-fields) (seq optional-fields))
         [:div.form-sections {:class "form-sections"}
          [:div.form-section {:class "form-section"}
           [:div.form-section-header {:class "form-section-header"}
            [:h3.form-section-title [:t :admin/form-section-required]]
            [:p.form-section-description [:t :admin/form-section-required-description]]]
           [:div.form-fields {:class "form-fields"}
            (for [field-name required-fields]
              (let [field-config (get-in entity-config [:fields field-name])
                    field-value (get record field-name)
                    field-errors (get errors field-name)]
                (render-field-widget field-name field-value field-config field-errors)))]]
          [:details.form-section.form-section-optional
           {:class "form-section form-section-optional"
            :x-data "{open: true}"
            :x-init (str "open = (localStorage.getItem('"
                         optional-details-key
                         "') ?? 'true') === 'true'")
            :x-bind:open "open"
            :x-on:toggle (str "localStorage.setItem('"
                              optional-details-key
                              "', $el.open ? 'true' : 'false')")}
           [:summary.form-section-toggle {:class "form-section-toggle"}
            [:div.form-section-header {:class "form-section-header"}
             [:h3.form-section-title [:t :admin/form-section-optional]]
             [:p.form-section-description [:t :admin/form-section-optional-description]]]
            [:span.form-section-toggle-icon
             (icons/icon :chevron-down {:size 16})]]
           [:div.form-fields {:class "form-fields"}
            (for [field-name optional-fields]
              (let [field-config (get-in entity-config [:fields field-name])
                    field-value (get record field-name)
                    field-errors (get errors field-name)]
                (render-field-widget field-name field-value field-config field-errors)))]]]

         ;; Priority 3: Flat rendering (all fields in one section)
         :else
         [:div.form-fields {:class "form-fields"}
          (for [field-name editable-fields]
            (let [field-config (get-in entity-config [:fields field-name])
                  field-value (get record field-name)
                  field-errors (get errors field-name)]
              (render-field-widget field-name field-value field-config field-errors)))])]
      [:div.form-actions {:class "form-actions justify-end border-t border-base-300 pt-4 mt-4"}
       [:button.button.primary {:class "gap-2" :type "submit"}
        (if is-edit? [:t :admin/button-update] [:t :admin/button-create])]
       [:a.button.secondary
        {:class "gap-2"
         :href (or cancel-url (str "/web/admin/" (name entity-name)))}
        [:t :common/button-cancel]]]]]))

(defn parent-context-banner
  "Renders a read-only info strip showing key fields from a parent record.
   Shown at the top of a child entity edit page (e.g. order-item → order).

   Args:
     ctx: {:config {:label \"Order\" :fields [:order-number :status ...]}
           :record  parent-record-map}"
  [{:keys [config record]}]
  (let [label  (:label config)
        fields (:fields config [])]
    [:div.parent-context-banner
     [:span.parent-context-label label]
     [:div.parent-context-fields
      (for [f fields]
        (when-let [v (get record f)]
          [:div.parent-context-field
           [:span.parent-context-field-label
            (-> (name f) (str/replace #"-" " ") str/capitalize)]
           [:span.parent-context-field-value (str v)]]))]]))

(defn related-records-table
  "Render a table of related records for a has-many relationship.
   When :editable true, adds an Edit link per row.

   Args:
     relationship: {:label \"Order Items\" :fields [...] :editable true}
     records:      vector of record maps (kebab-case keys)

   Returns:
     Hiccup table structure"
  [relationship records]
  (let [fields    (:fields relationship)
        label     (:label relationship)
        editable? (:editable relationship)
        entity    (name (:entity relationship))]
    [:div.related-records {:class "space-y-3 mt-6"}
     [:h2.section-title label]
     (if (empty? records)
       [:p.empty-state [:t :admin/relationship-empty-state {:label label}]]
       [:div.table-wrapper
        [:table.related-table
         [:thead
          [:tr
           (for [f fields]
             [:th (-> (name f) (str/replace #"-" " ") str/capitalize)])
           (when editable?
             [:th])]]
         [:tbody
          (for [record records]
            [:tr
             (for [f fields]
               [:td (or (get record f) "—")])
             (when editable?
               [:td
                [:a.button.secondary
                 {:href (str "/web/admin/" entity "/" (:id record)
                             (when-let [rt (:return-to relationship)]
                               (str "?return_to=" rt)))}
                 [:t :common/button-edit]]])])]]])]))

(defn entity-detail-page
  "Entity detail/edit page.

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     record: Entity record (nil for create)
     errors: Optional validation errors
     permissions: Permission flags
     opts: Optional map with :flash and :related-records

   Returns:
     Hiccup page structure"
  [entity-name entity-config record errors permissions & [opts]]
  (let [{:keys [flash return-to sibling-nav]} opts
        label      (:label entity-config)
        is-edit?   (some? record)
        page-title (if is-edit? [:t :admin/page-edit-title {:label label}] [:t :admin/page-create-title {:label label}])
        list-url   (or return-to (str "/web/admin/" (name entity-name)))]
    [:div.entity-detail-page {:class "space-y-4"}
     (when flash
       (let [flash-type (or (:type flash)
                            (first (keys flash)))
             flash-msg  (or (:message flash)
                            (first (vals flash)))]
         [:div {:class (str "alert alert-" (name flash-type))}
          flash-msg]))
     [:div.page-header {:class "space-y-3"}
      [:div.page-header-row
       [:div.page-breadcrumbs
        [:a {:href "/web/admin"} [:t :admin/breadcrumb-admin]]
        " / "
        [:a {:href list-url} label]
        " / "
        [:span page-title]]
       [:div.page-header-actions {:class "flex flex-wrap items-center gap-2"}
        [:a.button.secondary
         {:class "gap-2"
          :href list-url}
         (icons/icon :chevron-left {:size 16})
         [:t :admin/button-back-to-list]]
        (when sibling-nav
          [:div.sibling-nav
           (if (:prev-url sibling-nav)
             [:a.button.secondary {:href (:prev-url sibling-nav) :title "Previous"}
              (icons/icon :chevron-left {:size 14})]
             [:span.button.secondary.disabled {:aria-disabled "true"}
              (icons/icon :chevron-left {:size 14})])
           [:span.sibling-nav-count
            (:position sibling-nav) " / " (:total sibling-nav)]
           (if (:next-url sibling-nav)
             [:a.button.secondary {:href (:next-url sibling-nav) :title "Next"}
              (icons/icon :chevron-right {:size 14})]
             [:span.button.secondary.disabled {:aria-disabled "true"}
              (icons/icon :chevron-right {:size 14})])])
        (when is-edit?
          [:a.button.primary
           {:class "gap-2"
            :href (entity-create-url entity-name entity-config)}
           (icons/icon :plus {:size 16})
           [:t :admin/form-create-heading {:label label}]])
        (when (and is-edit? (:can-delete permissions))
          ;; URL-encode return-to because contextual list URLs may already
          ;; carry their own query string (filters, pagination). Without
          ;; encoding, any `&` inside return-to would be parsed as additional
          ;; params on the delete request, so the server would drop the
          ;; original list state when redirecting back.
          (let [delete-url (cond-> (str "/web/admin/" (name entity-name) "/" (get record (:primary-key entity-config :id)))
                             return-to (str "?return_to=" (java.net.URLEncoder/encode ^String return-to "UTF-8")))]
            [:button.button.danger
             {:type "button"
              :class "gap-2"
              :hx-delete delete-url
              :hx-target "body"
              :hx-swap "outerHTML"
              :hx-confirm [:t :admin/confirm-delete-record]
              :data-confirm-title [:t :admin/modal-confirm-delete-title]
              :data-confirm-cancel [:t :admin/modal-button-cancel]
              :data-confirm-label [:t :admin/modal-button-delete]}
             (icons/icon :trash {:size 16})
             [:t :common/button-delete]]))]]
      [:h1.page-title page-title]]
     (when-let [ctx (:parent-context opts)]
       (parent-context-banner ctx))
     (when (seq errors)
       (ui/validation-errors errors))
     (entity-form entity-name entity-config record errors permissions list-url)
     (when-let [related-records (:related-records opts)]
       (for [[rel records] related-records]
         (related-records-table rel records)))]))

(defn entity-new-page
  "Entity creation page (convenience wrapper).

   Args:
     entity-name: Keyword entity name
     entity-config: Entity configuration map
     errors: Optional validation errors
     permissions: Permission flags
     opts: Optional map with :flash

   Returns:
     Hiccup page structure"
  [entity-name entity-config errors permissions & [opts]]
  (entity-detail-page entity-name entity-config nil errors permissions opts))

;; =============================================================================
;; Confirmation Dialog
;; =============================================================================

(defn confirm-delete-dialog
  "Confirmation dialog for delete operations.

   Args:
     entity-name: Keyword entity name
     record-id: ID of record to delete

   Returns:
     Hiccup modal structure"
  [entity-name record-id]
  [:div.modal#confirm-delete-modal
   [:div.modal-content
    [:h3 [:t :admin/modal-confirm-delete-title]]
    [:p [:t :admin/modal-confirm-delete-message]]
    [:div.modal-actions
     [:button.button.danger
      {:hx-delete (str "/web/admin/" (name entity-name) "/" record-id)
       :hx-target "#entity-table-container"}
      [:t :common/button-delete]]
     [:button.button.secondary
      {:onclick "closeModal('confirm-delete-modal')"}
      [:t :common/button-cancel]]]]])

;; =============================================================================
;; Error Pages
;; =============================================================================

(defn admin-forbidden-page
  "403 Forbidden page for admin access denial.

   Args:
     reason: Explanation of why access was denied
     user: Current user (optional)

   Returns:
     Hiccup page structure"
  [reason & [user]]
  (layout/admin-pilot-page-layout
   [:t :admin/page-access-denied-title]
   [:div.error-page.admin-forbidden
    [:h1 "403 - " [:t :admin/page-access-denied-title]]
    [:p.error-message [:t :admin/page-access-denied-message]]
    (when reason
      [:p.error-reason reason])
    [:div.error-actions
     (if user
       [:a.button {:href "/"} [:t :admin/button-go-dashboard]]
       [:a.button {:href "/web/login"} [:t :admin/button-login]])]]
   {:user user}))

(defn admin-not-found-page
  "404 Not Found page for admin entities.

   Args:
     entity-name: Entity that was not found
     user: Current user

   Returns:
     Hiccup page structure"
  [entity-name user]
  (layout/admin-pilot-page-layout
   [:t :admin/page-not-found-title]
   [:div.error-page.admin-not-found
    [:h1 "404 - " [:t :admin/page-not-found-title]]
    [:p.error-message [:t :admin/page-not-found-message {:name (name entity-name)}]]
    [:div.error-actions
     [:a.button {:href "/web/admin"} [:t :admin/button-back-to-admin]]]]
   {:user user}))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn build-table-url
  "Build table URL with query parameters.

   Args:
     entity-name: Keyword entity name
     opts: Map with :page, :page-size, :sort, :dir, :search, etc.

   Returns:
     URL string with query parameters"
  [entity-name opts]
  (let [base-url (str "/web/admin/" (name entity-name) "/table")
        params (table-ui/encode-query-params opts)]
    (if (seq params)
      (str base-url "?" params)
      base-url)))

(defn format-field-label
  "Format field name as human-readable label.

   Args:
     field-name: Keyword field name

   Returns:
     Capitalized string label"
  [field-name]
  (-> field-name
      name
      (str/replace #"[-_]" " ")
      str/capitalize))

(defn get-field-errors
  "Extract errors for a specific field from validation result.

   Args:
     errors: Validation errors map or vector
     field-name: Keyword field name

   Returns:
     Vector of error messages for the field"
  [errors field-name]
  (cond
    (map? errors)
    (get errors field-name [])

    (vector? errors)
    (filterv #(= field-name (:field %)) errors)

    :else
    []))
