(ns boundary.devtools.shell.dashboard.pages.routes
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [boundary.devtools.shell.repl :as devtools-repl]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [integrant.repl.state :as state]
            [hiccup2.core :as h]))

;; =============================================================================
;; Data
;; =============================================================================

(defn- route-data
  "Get routes from state/system via extract-routes-from-handler."
  []
  (let [sys     state/system
        handler (get sys :boundary/http-handler)]
    (when handler
      (devtools-repl/extract-routes-from-handler handler))))

;; =============================================================================
;; Rendering helpers
;; =============================================================================

(defn- all-modules
  "Return a sorted distinct list of module names from the routes seq."
  [routes]
  (->> routes
       (keep :module)
       distinct
       sort))

(defn- all-methods
  "Return distinct HTTP method keywords from the routes seq, sorted by name."
  [routes]
  (->> routes
       (map :method)
       distinct
       (sort-by name)))

(defn- handler-short-name
  "Shorten a compiled handler string to the last two namespace segments + fn name."
  [handler-str]
  (when (string? handler-str)
    (let [clean (-> handler-str
                    (str/replace #"@[a-f0-9]+" "")
                    (str/replace #"\$" "/"))]
      (if-let [m (re-find #"boundary\.([^/]+\.[^/]+/[^\s]+)" clean)]
        (second m)
        (last (str/split clean #" "))))))

(defn- route-rows
  "Build data-table rows for the given routes seq."
  [routes]
  (for [{:keys [method path handler module]} routes]
    {:cells [(c/method-badge method)
             [:span.route-path path]
             [:span.route-handler (or (handler-short-name handler) handler)]
             (if module
               [:span.module-tag module]
               [:span.module-tag.module-tag-unknown "—"])
             [:a.inspect-link
              {:href     (str "/dashboard/routes/inspect?path=" (java.net.URLEncoder/encode path "UTF-8")
                              "&method=" (name method))
               :hx-get  (str "/dashboard/routes/inspect?path=" (java.net.URLEncoder/encode path "UTF-8")
                             "&method=" (name method))
               :hx-target "#route-detail"
               :hx-swap   "innerHTML"}
              "inspect →"]]}))

;; =============================================================================
;; Page
;; =============================================================================

(defn render
  "Render the Route Explorer full page."
  [opts]
  (let [routes      (or (route-data) [])
        all-routes  (vec routes)
        modules     (all-modules all-routes)
        methods     (all-methods all-routes)
        route-count (count all-routes)

        module-options (into [{:value "" :label "All modules"}]
                             (map (fn [m] {:value m :label m}) modules))
        method-options (into [{:value "" :label "All methods"}]
                             (map (fn [m] {:value (name m) :label (str/upper-case (name m))}) methods))]
    (layout/dashboard-page
     (merge opts {:active-path   "/dashboard/routes"
                  :title         "Route Explorer"})
     (c/card
      {:title "Routes"
       :right [:span.route-count (str route-count " routes")]}
      ;; Filter bar
      (c/filter-bar
       (c/filter-input {:name        "search"
                        :placeholder "Search path or handler..."
                        :id          "route-search"
                        :hx-get      "/dashboard/routes"
                        :hx-trigger  "keyup changed delay:300ms"
                        :hx-target   "#routes-table"
                        :hx-swap     "outerHTML"
                        :hx-include  "[name='module'],[name='method']"})
       (c/filter-select {:name       "module"
                         :id         "route-module"
                         :hx-get     "/dashboard/routes"
                         :hx-trigger "change"
                         :hx-target  "#routes-table"
                         :hx-swap    "outerHTML"
                         :hx-include "[name='search'],[name='method']"}
                        module-options)
       (c/filter-select {:name       "method"
                         :id         "route-method"
                         :hx-get     "/dashboard/routes"
                         :hx-trigger "change"
                         :hx-target  "#routes-table"
                         :hx-swap    "outerHTML"
                         :hx-include "[name='search'],[name='module']"}
                        method-options))
      ;; Route table
      [:div#routes-table
       (c/data-table
        {:columns      ["Method" "Path" "Handler" "Module" ""]
         :col-template "80px 1fr 1fr 100px 100px"
         :rows         (route-rows all-routes)})])
     ;; Detail panel placeholder
     [:div#route-detail])))

;; =============================================================================
;; Try-it fragment
;; =============================================================================

(defn render-try-result
  "Handle 'Try it' POST. Simulate a request and return an HTML fragment."
  [req]
  (let [params  (get req :params {})
        method  (or (get params "method") (get params :method) "get")
        path    (or (get params "path") (get params :path) "/")
        raw-body (or (get params "body") (get params :body) "")
        body    (when (seq raw-body)
                  (try
                    (edn/read-string raw-body)
                    (catch Exception _ nil)))
        sys     state/system
        handler (get sys :boundary/http-handler)]
    (if-not handler
      (str (h/html
            [:div.detail-panel.detail-panel-error
             [:p.error-title "System not running"]
             [:p "No HTTP handler found in the Integrant system."]]))
      (let [result (devtools-repl/simulate-request handler method path (cond-> {} body (assoc :body body)))
            status (:status result)
            body   (:body result)
            ok?    (and (integer? status) (< status 400))]
        (str (h/html
              [:div.detail-panel {:class (if ok? "" "detail-panel-error")}
               [:div.try-result-status
                [:span.try-result-label "Status: "]
                [:span.try-result-value {:class (if ok? "status-ok" "status-error")} status]]
               [:div.try-result-body
                [:span.try-result-label "Response: "]
                (c/code-block (if (string? body) body (pr-str body)))]]))))))
