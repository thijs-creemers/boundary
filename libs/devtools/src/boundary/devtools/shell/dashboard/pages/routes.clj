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

(defn- route-data []
  (let [sys     state/system
        handler (get sys :boundary/http-handler)]
    (when handler
      (devtools-repl/extract-routes-from-handler handler))))

(defn- filter-routes [routes {:keys [search module method]}]
  (cond->> routes
    (not (str/blank? search))
    (filter (fn [{:keys [path handler]}]
              (or (str/includes? (str path) search)
                  (str/includes? (str handler) search))))
    (not (str/blank? module))
    (filter (fn [{:keys [module] :as _r}] (= module module)))
    (not (str/blank? method))
    (filter (fn [r] (= (name (:method r)) method)))))

;; =============================================================================
;; Rendering helpers
;; =============================================================================

(defn- all-modules [routes]
  (->> routes (keep :module) distinct sort))

(defn- all-methods [routes]
  (->> routes (map :method) distinct (sort-by name)))

(defn- handler-short-name [handler-str]
  (when (string? handler-str)
    (let [clean (-> handler-str
                    (str/replace #"@[a-f0-9]+" "")
                    (str/replace #"\$" "/"))]
      (if-let [m (re-find #"boundary\.([^/]+\.[^/]+/[^\s]+)" clean)]
        (second m)
        (last (str/split clean #" "))))))

(defn- route-rows [routes]
  (for [{:keys [method path handler module]} routes]
    {:cells [(c/method-badge method)
             [:span.route-path path]
             [:span.route-handler (or (handler-short-name handler) handler)]
             (if module
               [:span.module-tag module]
               [:span.module-tag.module-tag-unknown "—"])
             [:button.inspect-link
              {:type     "button"
               :hx-get   (str "/dashboard/fragments/route-inspect?path="
                              (java.net.URLEncoder/encode path "UTF-8")
                              "&method=" (name method))
               :hx-target "#route-detail"
               :hx-swap   "innerHTML"
               :style     "background:none;border:none;color:var(--accent-blue);cursor:pointer;font-size:12px"}
              "inspect →"]]}))

(defn- render-routes-table [routes]
  [:div#routes-table
   (c/data-table
    {:columns      ["Method" "Path" "Handler" "Module" ""]
     :col-template "80px 1fr 1fr 100px 100px"
     :rows         (route-rows routes)})])

;; =============================================================================
;; Fragment endpoints
;; =============================================================================

(defn render-table-fragment
  "Return filtered route table as HTML fragment for HTMX."
  [req]
  (let [params (get req :params {})
        search (or (get params "search") "")
        module (or (get params "module") "")
        method (or (get params "method") "")
        routes (filter-routes (or (route-data) [])
                              {:search search :module module :method method})]
    (str (h/html (render-routes-table routes)))))

(defn render-inspect-fragment
  "Return route detail (interceptor chain + try-it form) as HTML fragment."
  [req]
  (let [params (get req :params {})
        path   (or (get params "path") "")
        method (or (get params "method") "get")]
    (str (h/html
          [:div.detail-panel
           [:div.detail-header
            [:span (c/method-badge (keyword method))]
            [:span {:style "margin-left:8px"} path]]
           [:div {:style "padding:16px"}
            [:div.detail-label "Try it"]
            [:div.try-it-panel
             [:form {:hx-post "/dashboard/fragments/try-route"
                     :hx-target "#try-result"
                     :hx-swap "innerHTML"}
              [:input {:type "hidden" :name "path" :value path}]
              [:input {:type "hidden" :name "method" :value method}]
              [:textarea {:name "body" :placeholder "{:key \"value\"}" :rows 3
                          :style "width:100%;margin-bottom:8px"}]
              [:button.btn.btn-primary {:type "submit"
                                        :style "background:var(--accent-blue);color:#0f172a;border:none;padding:6px 16px;border-radius:4px;cursor:pointer;font-weight:600"}
               "Send Request"]]]
            [:div#try-result]]]))))

;; =============================================================================
;; Page
;; =============================================================================

(defn render [opts]
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
     (merge opts {:active-path "/dashboard/routes"
                  :title       "Route Explorer"})
     (c/card
      {:title "Routes"
       :right [:span.route-count (str route-count " routes")]}
      (c/filter-bar
       (c/filter-input {:name        "search"
                        :placeholder "Search path or handler..."
                        :hx-get      "/dashboard/fragments/routes-table"
                        :hx-trigger  "keyup changed delay:300ms"
                        :hx-target   "#routes-table"
                        :hx-swap     "outerHTML"
                        :hx-include  "[name='module'],[name='method']"})
       (c/filter-select {:name       "module"
                         :hx-get     "/dashboard/fragments/routes-table"
                         :hx-trigger "change"
                         :hx-target  "#routes-table"
                         :hx-swap    "outerHTML"
                         :hx-include "[name='search'],[name='method']"}
                        module-options)
       (c/filter-select {:name       "method"
                         :hx-get     "/dashboard/fragments/routes-table"
                         :hx-trigger "change"
                         :hx-target  "#routes-table"
                         :hx-swap    "outerHTML"
                         :hx-include "[name='search'],[name='module']"}
                        method-options))
      (render-routes-table all-routes))
     [:div#route-detail])))

;; =============================================================================
;; Try-it fragment
;; =============================================================================

(defn render-try-result [req]
  (let [params  (get req :params {})
        method  (or (get params "method") (get params :method) "get")
        path    (or (get params "path") (get params :path) "/")
        raw-body (or (get params "body") (get params :body) "")
        body    (when (seq raw-body)
                  (try (edn/read-string raw-body) (catch Exception _ nil)))
        sys     state/system
        handler (get sys :boundary/http-handler)]
    (if-not handler
      (str (h/html [:div.detail-panel.detail-panel-error
                    [:p "System not running"]]))
      (let [result (devtools-repl/simulate-request handler method path
                                                   (cond-> {} body (assoc :body body)))
            status (:status result)
            ok?    (and (integer? status) (< status 400))]
        (str (h/html
              [:div {:style "margin-top:12px"}
               [:div {:style (str "font-weight:600;color:" (if ok? "var(--accent-green-light)" "var(--accent-red)"))}
                (str "Status: " status)]
               (c/code-block (if (string? (:body result))
                               (:body result)
                               (pr-str (:body result))))]))))))
