(ns boundary.devtools.shell.dashboard.pages.overview
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [boundary.devtools.shell.repl :as devtools-repl]
            [clojure.string :as str]
            [integrant.repl.state :as state]))

(def ^:private infra-keys
  "Infrastructure component names to filter out when listing modules."
  #{"db-context" "http-server" "http-handler" "router" "logging" "metrics"
    "error-reporting" "cache" "dashboard" "i18n"})

(defn- active-modules
  "Extract module names from Integrant system keys, filtering out infra-keys.
   Returns a sorted seq of name strings."
  [sys]
  (when sys
    (->> (keys sys)
         (map (fn [k] (name k)))
         (map (fn [k] (last (str/split k #"/"))))
         (remove infra-keys)
         sort)))

(defn- system-data
  "Gather all system info from state/system."
  []
  (let [sys     state/system
        routes  (devtools-repl/extract-routes-from-handler
                 (get sys :boundary/http-handler))
        modules (active-modules sys)
        db-ctx  (get sys :boundary/db-context)
        adapter (when db-ctx
                  (let [a (:adapter db-ctx)]
                    (if (keyword? a) (name a) (str (type a)))))
        host    (when db-ctx (or (get-in db-ctx [:options :host])
                                 (get-in db-ctx [:host])
                                 "localhost"))]
    {:component-count (count sys)
     :routes          (seq routes)
     :route-methods   (when routes (frequencies (map :method routes)))
     :route-count     (count routes)
     :modules         modules
     :module-count    (count modules)
     :profile         (or (System/getenv "BND_ENV") "dev")
     :db-adapter      (or adapter "unknown")
     :db-host         (or host "localhost")
     :http-port       3000
     :nrepl-port      7888
     :java-version    (System/getProperty "java.version")}))

(defn- component-rows
  "Build data-table rows for all Integrant system keys."
  [sys]
  (when sys
    (for [k (sort-by str (keys sys))]
      {:cells [(name k) (c/status-dot :healthy)]})))

(defn- env-info-rows
  "Build a simple key/value list for environment info."
  [{:keys [profile db-adapter db-host http-port nrepl-port java-version]}]
  [["Profile"      profile]
   ["Database"     (str db-adapter " @ " db-host)]
   ["Web"          (str "http://localhost:" http-port)]
   ["Admin"        (str "http://localhost:" http-port "/admin")]
   ["nREPL port"   (str nrepl-port)]
   ["Java version" java-version]])

(defn render [opts]
  (let [{:keys [component-count route-count module-count
                profile db-adapter db-host http-port nrepl-port java-version
                error-count]}
        (merge (system-data) opts)
        sys         state/system
        err-count   (or error-count 0)]
    (layout/dashboard-page
     (merge opts {:active-path     "/dashboard"
                  :title           "System Overview"
                  :component-count component-count
                  :error-count     err-count
                  :http-port       http-port
                  :system-status   (if (pos? err-count) :error :healthy)})
     ;; Top row: stat cards
     [:div.stats-row
      (c/stat-card {:label "Components" :value component-count})
      (c/stat-card {:label "Routes"     :value route-count})
      (c/stat-card {:label "Modules"    :value module-count})
      (c/stat-card {:label "Errors"
                    :value err-count
                    :value-class (when (pos? err-count) "stat-value-error")})]
     ;; Bottom: two-column layout
     [:div.overview-columns
      ;; Left: Integrant component list
      (c/card {:title "Integrant Components"}
              (c/data-table
               {:columns      ["Component" "Status"]
                :col-template "1fr auto"
                :rows         (component-rows sys)}))
      ;; Right: Environment info
      (c/card {:title "Environment"}
              [:dl.env-info
               (for [[label value] (env-info-rows
                                    {:profile      profile
                                     :db-adapter   db-adapter
                                     :db-host      db-host
                                     :http-port    http-port
                                     :nrepl-port   nrepl-port
                                     :java-version java-version})]
                 [:div.env-info-row
                  [:dt.env-info-label label]
                  [:dd.env-info-value value]])])])))
