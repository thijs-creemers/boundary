(ns boundary.devtools.shell.dashboard.pages.schemas
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [malli.core :as m]))

;; =============================================================================
;; Schema collection
;; =============================================================================

(def ^:private fallback-schema-keys
  "Hardcoded fallback list of common Boundary schema keys."
  [:user/create
   :user/update
   :user/login
   :user/auth-user
   :user/tenant-user
   :user/session
   :user/audit-log
   :user/create-request
   :user/update-request
   :user/login-request
   :user/response])

(def ^:private known-schemas
  "Map of schema key -> Malli schema value for schemas we can directly reference."
  (try
    (require 'boundary.user.schema)
    (let [s (find-ns 'boundary.user.schema)]
      (when s
        {:user/auth-user        (var-get (ns-resolve s 'AuthUser))
         :user/tenant-user      (var-get (ns-resolve s 'TenantUser))
         :user/user             (var-get (ns-resolve s 'User))
         :user/session          (var-get (ns-resolve s 'UserSession))
         :user/audit-log        (var-get (ns-resolve s 'UserAuditLog))
         :user/create-request   (var-get (ns-resolve s 'CreateUserRequest))
         :user/update-request   (var-get (ns-resolve s 'UpdateUserRequest))
         :user/login-request    (var-get (ns-resolve s 'LoginRequest))
         :user/response         (var-get (ns-resolve s 'UserResponse))
         :user/mfa-setup-request   (var-get (ns-resolve s 'MFASetupRequest))
         :user/mfa-enable-request  (var-get (ns-resolve s 'MFAEnableRequest))
         :user/mfa-verify-request  (var-get (ns-resolve s 'MFAVerifyRequest))
         :user/login-response      (var-get (ns-resolve s 'LoginResponse))
         :user/mfa-setup-response  (var-get (ns-resolve s 'MFASetupResponse))
         :user/mfa-status-response (var-get (ns-resolve s 'MFAStatusResponse))}))
    (catch Exception _ nil)))

(defn collect-schemas
  "Collect available Malli schemas. Returns a seq of schema keys."
  []
  (if (seq known-schemas)
    (sort-by name (keys known-schemas))
    fallback-schema-keys))

;; =============================================================================
;; Schema detail rendering
;; =============================================================================

(defn- schema-type-label
  "Return a readable string for a Malli schema type."
  [s]
  (try
    (let [t (m/type s)]
      (case t
        :enum  (str "[:enum " (clojure.string/join " " (m/children s)) "]")
        :maybe (str "[:maybe " (schema-type-label (first (m/children s))) "]")
        (str t)))
    (catch Exception _ "?")))

(defn- render-field-row
  "Render a single schema field as a Hiccup row."
  [child]
  (let [k       (first child)
        props   (when (= 3 (count child)) (second child))
        schema  (if (= 3 (count child)) (nth child 2) (second child))
        opt?    (true? (:optional props))
        type-str (try (schema-type-label (m/schema schema)) (catch Exception _ (pr-str schema)))]
    [:div.schema-field
     (if opt?
       [:span.schema-optional "○"]
       [:span.schema-required "*"])
     [:span.schema-field-name (pr-str k)]
     [:span.schema-field-type type-str]]))

(defn- generate-example-str
  "Try to generate a malli example. Returns nil if malli.generator is unavailable."
  [schema]
  (try
    (let [gen-fn (requiring-resolve 'malli.generator/generate)]
      (when gen-fn
        (let [example (gen-fn (m/schema schema) {:seed 42})]
          (with-out-str (pprint/pprint example)))))
    (catch Exception _ nil)))

(defn render-schema-detail
  "Render a schema's fields as a tree panel."
  [schema-key]
  (try
    (let [schema-val (get known-schemas schema-key)]
      (if-not schema-val
        [:div.detail-panel
         [:p.schema-no-detail (str "No schema loaded for " (pr-str schema-key))]
         [:p.schema-hint "Schema data is only available for modules on the classpath."]]
        (let [s        (m/schema schema-val)
              title    (or (get (m/properties s) :title) (pr-str schema-key))
              children (try (m/children s) (catch Exception _ nil))
              example  (generate-example-str schema-val)]
          [:div.detail-panel
           [:div.schema-detail-header
            [:h3.schema-detail-title title]
            [:span.schema-field-type (str (m/type s))]]
           (when (seq children)
             [:div.schema-fields
              (for [child children]
                (render-field-row child))])
           (when example
             [:div.schema-example
              [:div.schema-example-label "Example value"]
              (c/code-block example)])])))
    (catch Exception e
      [:div.detail-panel.detail-panel-error
       [:p.error-title "Error rendering schema"]
       [:p (ex-message e)]])))

;; =============================================================================
;; Page
;; =============================================================================

(defn render
  "Render the Schema Browser full page."
  [opts req]
  (let [params         (get req :params {})
        selected-raw   (or (get params "schema") (get params :schema))
        selected-key   (when selected-raw (keyword selected-raw))
        schema-keys    (collect-schemas)
        detail-content (if selected-key
                         (render-schema-detail selected-key)
                         [:div.schema-empty-state
                          [:p "Select a schema from the list to inspect its fields."]])]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/schemas"
                  :title       "Schema Browser"})
     [:div.schema-layout
      ;; Left column: schema list
      [:div.schema-list-panel
       (c/card
        {:title "Schemas"
         :right [:span.count-badge (str (count schema-keys))]}
        (c/filter-input {:placeholder "Filter schemas..."
                         :id          "schema-search"
                         :oninput     "let v=this.value.toLowerCase();document.querySelectorAll('#schema-list .schema-list-item').forEach(el=>{el.style.display=el.textContent.toLowerCase().includes(v)?'':'none'})"})
        [:div#schema-list
         (for [sk schema-keys]
           (let [active? (= sk selected-key)
                 href    (str "/dashboard/schemas?schema=" (namespace sk) "/" (name sk))]
             [:a.schema-list-item
              {:href  href
               :class (when active? "active")}
              [:span.schema-list-key (pr-str sk)]]))])]
      ;; Right column: schema detail
      [:div.schema-detail-panel
       detail-content]])))
