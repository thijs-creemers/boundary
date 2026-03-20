(ns boundary.workflow.core.ui
  "Pure Hiccup UI components for workflow admin visualization.

   All functions are pure — they receive data and return Hiccup structures.
   No side effects, no I/O, no logging.

   Components:
     state-badge          — colored state pill
     state-machine-vis    — read-only workflow state diagram
     audit-log-table      — chronological audit trail table
     instances-filter-form — search/filter form for instance list
     instances-table      — paginated instance list table
     instances-page       — full page: instance list
     instance-detail-page — full page: instance detail with state viz + audit trail"
  (:require [boundary.shared.ui.core.layout :as layout]
            [boundary.shared.ui.core.icons :as icons]
            [boundary.shared.ui.core.components :as ui]
            [clojure.string :as str]))

;; =============================================================================
;; State Badge
;; =============================================================================

(defn state-badge
  "Render a state keyword as a colored badge.

   Args:
     state-kw - keyword (e.g. :pending, :paid)
     variant  - :current | :reachable | :visited | :default

   CSS classes map:
     :current   → status-badge success  (green  — active state)
     :reachable → status-badge info     (blue   — reachable from current)
     :visited   → status-badge          (grey   — previously visited)
     :default   → status-badge          (grey   — all other states)

   Returns:
     Hiccup span element"
  ([state-kw]
   (state-badge state-kw :default))
  ([state-kw variant]
   (let [badge-variant (case variant
                         :current :success
                         :reachable :info
                         :visited :neutral
                         :neutral)]
     (ui/badge (name state-kw)
               {:variant badge-variant
                :class "status-badge"}))))

;; =============================================================================
;; State Machine Visualization
;; =============================================================================

(defn state-machine-vis
  "Render a read-only visualization of a workflow state machine.

   Displays all states as colored badges (current highlighted in green,
   reachable states in blue) and a collapsible transition reference table.

   Args:
     definition    - WorkflowDefinition map or nil if not registered
     current-state - keyword (e.g. :paid)
     visited-states - collection of state keywords already visited (from audit log)

   Returns:
     Hiccup structure"
  [definition current-state visited-states]
  (let [visited (set visited-states)]
    [:div
     (if (nil? definition)
       [:div
        [:p.text-muted "Workflow definition not loaded in registry."]
        [:div {:style "display: flex; gap: 0.5rem; flex-wrap: wrap;"}
         (state-badge current-state :current)]]
       (let [all-states  (:states definition)
             transitions (:transitions definition)
             initial     (:initial-state definition)
             reachable   (set (map :to
                                   (filter #(= current-state (:from %)) transitions)))
             ordered     (concat [initial]
                                 (sort-by name (disj (set all-states) initial)))
             state-var   (fn [s]
                           (cond
                             (= s current-state) :current
                             (contains? visited s) :visited
                             (contains? reachable s) :reachable
                             :else :default))]
         [:div
          [:div {:style "display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; margin-bottom: 1rem;"}
           (for [s ordered]
             (state-badge s (state-var s)))]
          [:details
           [:summary {:style "cursor: pointer; font-size: 0.9em; margin-bottom: 0.5rem;"}
            "View all transitions (" (count transitions) ")"]
           (ui/table-wrapper
            [:table {:class "data-table ui-table" :style "margin-top: 0.5rem;"}
             [:thead
              [:tr
               [:th "From"]
               [:th "→ To"]
               [:th "Required permissions"]]]
             [:tbody
              (for [{:keys [from to required-permissions]} transitions]
                [:tr
                 [:td (state-badge from (state-var from))]
                 [:td (state-badge to (state-var to))]
                 [:td
                  (if (seq required-permissions)
                    (str/join ", " (map name required-permissions))
                    [:span.text-muted "—"])]])]])]]))]))

;; =============================================================================
;; Audit Log Table
;; =============================================================================

(defn audit-log-table
  "Render a chronological audit log table for a workflow instance.

   Args:
     entries - vector of AuditEntry maps (oldest first)

   Returns:
     Hiccup table or empty-state structure"
  [entries]
  (if (empty? entries)
    [:div.empty-state
     (icons/icon :file-text {:size 32})
     [:h3 "No transitions recorded"]
     [:p "This instance has not had any state transitions yet."]]
    (ui/table-wrapper
     [:table {:class "data-table ui-table"}
      [:thead
       [:tr
        [:th "When"]
        [:th "Transition"]
        [:th "From state"]
        [:th "To state"]
        [:th "Actor roles"]
        [:th "Context"]]]
      [:tbody
       (for [entry entries]
         [:tr
          [:td {:style "white-space: nowrap; font-size: 0.85em;"}
           (str (:occurred-at entry))]
          [:td [:code (name (:transition entry))]]
          [:td (state-badge (:from-state entry) :visited)]
          [:td (state-badge (:to-state entry) :current)]
          [:td
           (if (seq (:actor-roles entry))
             (str/join ", " (map name (:actor-roles entry)))
             [:span.text-muted "—"])]
          [:td
           (if (:context entry)
             [:code {:style "font-size: 0.8em;"} (pr-str (:context entry))]
             [:span.text-muted "—"])]])]])))

;; =============================================================================
;; Instances Table
;; =============================================================================

(defn instances-table
  "Render a paginated table of workflow instances.

   Args:
     instances - vector of WorkflowInstance maps

   Returns:
     Hiccup table or empty-state structure"
  [instances]
  (if (empty? instances)
    [:div.empty-state
     (icons/icon :layout-list {:size 32})
     [:h3 "No workflow instances"]
     [:p "No workflow instances have been created yet."]]
    (ui/table-wrapper
     [:table {:class "data-table ui-table"}
      [:thead
       [:tr
        [:th "Instance ID"]
        [:th "Workflow"]
        [:th "Entity type"]
        [:th "Entity ID"]
        [:th "Current state"]
        [:th "Updated"]]]
      [:tbody
       (for [inst instances]
         (let [id-str (str (:id inst))
               short-id (subs id-str 0 (min 8 (count id-str)))]
           [:tr {:onclick (str "window.location.href='/web/admin/workflows/" id-str "'")
                 :style "cursor: pointer;"}
            [:td
             [:a {:href (str "/web/admin/workflows/" id-str)}
              [:code {:style "font-size: 0.85em;"} short-id "…"]]]
            [:td [:code (name (:workflow-id inst))]]
            [:td [:code (name (:entity-type inst))]]
            [:td
             (let [eid-str (str (:entity-id inst))
                   short-eid (subs eid-str 0 (min 8 (count eid-str)))]
               [:code {:style "font-size: 0.85em;"} short-eid "…"])]
            [:td (state-badge (:current-state inst) :current)]
            [:td {:style "font-size: 0.85em; white-space: nowrap;"}
             (str (:updated-at inst))]]))]])))

;; =============================================================================
;; Filter Form
;; =============================================================================

(defn instances-filter-form
  "Render the search/filter form for the workflow instances list.

   Args:
     params - current query-params map (string keys)

   Returns:
     Hiccup form structure"
  [params]
  [:form.workflow-filter-form {:method "GET"
                               :action "/web/admin/workflows"}
   [:label.form-field
    "Workflow ID"
    (ui/text-input :workflow-id (get params "workflow-id" "")
                   {:placeholder "e.g. order-workflow"})]
   [:label.form-field
    "Entity type"
    (ui/text-input :entity-type (get params "entity-type" "")
                   {:placeholder "e.g. order"})]
   [:label.form-field
    "Current state"
    (ui/text-input :state (get params "state" "")
                   {:placeholder "e.g. pending"})]
   [:button.button.secondary {:type "submit"}
    (icons/icon :search {:size 14})
    " Search"]])

;; =============================================================================
;; Full Pages
;; =============================================================================

(defn instances-page
  "Render the full workflow instances list page.

   Args:
     instances - vector of WorkflowInstance maps
     params    - query-params map (string keys from ring request)
     opts      - map with :user, :flash

   Returns:
     Complete HTML page structure (doctype + html)"
  [instances params opts]
  (let [{:keys [user flash]} opts]
    (layout/admin-pilot-page-layout
     "Workflow Instances — Admin"
     [:div.workflow-page
      [:div.page-header
       [:h1.page-title
        (icons/icon :layout-list {:size 24})
        "Workflow Instances"]
       [:a.button.secondary {:href "/web/dashboard"}
        "← Dashboard"]]
      (instances-filter-form params)
      (instances-table instances)]
     {:user  user
      :flash flash})))

(defn instance-detail-page
  "Render the workflow instance detail page.

   Displays instance metadata, a state machine visualization with the
   current state highlighted, and the full chronological audit trail.

   Args:
     instance   - WorkflowInstance map
     definition - WorkflowDefinition map or nil
     audit-log  - vector of AuditEntry maps (oldest first)
     opts       - map with :user, :flash

   Returns:
     Complete HTML page structure"
  [instance definition audit-entries opts]
  (let [{:keys [user flash]} opts
        visited-states (map :to-state audit-entries)
        wf-name (name (:workflow-id instance))]
    (layout/admin-pilot-page-layout
     (str "Workflow: " wf-name " — Admin")
     [:div.workflow-page
      ;; Breadcrumb
      [:nav {:aria-label "Breadcrumb"
             :style "margin-bottom: 1rem; font-size: 0.9em;"}
       [:a {:href "/web/admin/workflows"} "Workflow Instances"]
       " / "
       [:code {:style "font-size: inherit;"} (str (:id instance))]]

      ;; Instance summary card
      [:article
       [:header
        [:h2 {:style "margin: 0;"} "Instance Details"]]
       [:dl {:style "display: grid; grid-template-columns: 140px 1fr; gap: 0.4rem 1rem; margin: 0;"}
        [:dt "Workflow"]
        [:dd [:code (name (:workflow-id instance))]]
        [:dt "Entity type"]
        [:dd [:code (name (:entity-type instance))]]
        [:dt "Entity ID"]
        [:dd [:code (str (:entity-id instance))]]
        [:dt "Current state"]
        [:dd (state-badge (:current-state instance) :current)]
        [:dt "Created"]
        [:dd {:style "font-size: 0.9em;"} (str (:created-at instance))]
        [:dt "Updated"]
        [:dd {:style "font-size: 0.9em;"} (str (:updated-at instance))]]]

      ;; State machine visualization card
      [:article
       [:header
        [:h2 {:style "margin: 0 0 1rem;"} "State Machine"]]
       (state-machine-vis definition (:current-state instance) visited-states)]

      ;; Audit trail card
      [:article
       [:header {:style "margin-bottom: 1rem;"}
        [:h2 {:style "margin: 0; display: flex; align-items: center; gap: 0.5rem;"}
         (icons/icon :file-text {:size 20})
         "Audit Trail"]
        [:small {:style "color: var(--muted-color);"}
         (count audit-entries)
         " transition"
         (when (not= 1 (count audit-entries)) "s")
         " recorded"]]
       (audit-log-table audit-entries)]]
     {:user  user
      :flash flash})))
