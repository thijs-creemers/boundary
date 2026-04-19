(ns boundary.devtools.shell.dashboard.pages.database
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [clojure.string :as str]
            [integrant.repl.state :as state]
            [hiccup2.core :as h])
  (:import (java.io File)))

;; =============================================================================
;; Data
;; =============================================================================

(defn- migration-data
  "Return a list of migration entries from resources/migrations/ if available,
   otherwise fall back to sample data.
   Each entry: {:name <string> :status :applied|:pending}"
  []
  (let [migrations-dir (File. "resources/migrations")]
    (if (.exists migrations-dir)
      (let [files (->> (.listFiles migrations-dir)
                       (filter #(str/ends-with? (.getName %) ".up.sql"))
                       (sort-by #(.getName %)))]
        (if (seq files)
          (map (fn [f]
                 {:name   (str/replace (.getName f) #"\.up\.sql$" "")
                  :status :applied})
               files)
          [{:name "001_initial_schema" :status :applied}
           {:name "002_add_users"      :status :applied}
           {:name "003_add_sessions"   :status :pending}]))
      [{:name "001_initial_schema" :status :applied}
       {:name "002_add_users"      :status :applied}
       {:name "003_add_sessions"   :status :pending}])))

(defn- pool-stats
  "Get HikariCP pool stats from state/system.
   Returns {:active :idle :waiting :max} or nil when unavailable."
  []
  (when-let [sys state/system]
    (when-let [db-ctx (get sys :boundary/db-context)]
      (when-let [ds (:datasource db-ctx)]
        (try
          (let [pool (.unwrap ds com.zaxxer.hikari.HikariPoolMXBean)]
            {:active  (.getActiveConnections pool)
             :idle    (.getIdleConnections pool)
             :waiting (.getThreadsAwaitingConnection pool)
             :max     (.getTotalConnections pool)})
          (catch Exception _ nil))))))

(defn- table-list
  "List database tables via JDBC DatabaseMetaData.
   Returns a seq of {:name <string>} maps, or [] when unavailable."
  []
  (when-let [sys state/system]
    (when-let [db-ctx (get sys :boundary/db-context)]
      (when-let [ds (:datasource db-ctx)]
        (try
          (with-open [conn (.getConnection ds)]
            (let [md (.getMetaData conn)
                  rs (.getTables md nil nil "%" (into-array String ["TABLE"]))]
              (loop [acc []]
                (if (.next rs)
                  (recur (conj acc {:name (.getString rs "TABLE_NAME")}))
                  acc))))
          (catch Exception _ []))))))

;; =============================================================================
;; Rendering helpers
;; =============================================================================

(defn- render-pool-stats
  "Render the pool stats grid hiccup. Accepts stats map or nil."
  [stats]
  (if stats
    [:div.pool-grid
     [:div.pool-stat
      [:div.pool-label "Active"]
      [:div.pool-value {:class (when (pos? (:active stats)) "pool-value-active")}
       (:active stats)]]
     [:div.pool-stat
      [:div.pool-label "Idle"]
      [:div.pool-value (:idle stats)]]
     [:div.pool-stat
      [:div.pool-label "Waiting"]
      [:div.pool-value {:class (when (pos? (:waiting stats)) "pool-value-warning")}
       (:waiting stats)]]
     [:div.pool-stat
      [:div.pool-label "Max"]
      [:div.pool-value (:max stats)]]]
    [:div.pool-grid
     [:p.no-data "Pool stats unavailable — system not running or HikariCP not accessible."]]))

(defn- migration-rows
  "Build data-table rows for the migrations list."
  [migrations]
  (for [{:keys [name status]} migrations]
    {:cells [[:span {:class (str "migration-status migration-" (clojure.core/name status))}
              (if (= status :applied) "✓" "○")]
             [:span.migration-name name]
             [:span {:class (str "migration-badge migration-" (clojure.core/name status))}
              (clojure.core/name status)]]}))

(defn- table-rows
  "Build data-table rows for the table browser."
  [tables]
  (if (seq tables)
    (for [{:keys [name]} tables]
      {:cells [[:span.table-name name]
               [:button.inspect-link
                {:type        "button"
                 :hx-post     "/dashboard/fragments/query-result"
                 :hx-vals     (str "{\"sql\": \"SELECT * FROM \\\"" name "\\\" LIMIT 20\"}")
                 :hx-target   "#query-result"
                 :hx-swap     "innerHTML"
                 :style       "background:none;border:none;color:var(--accent-blue);cursor:pointer;font-size:12px"}
                "browse →"]]})
    [{:cells [[:span.no-data {:style "grid-column: 1/-1"} "No tables found or database not connected."]
              ""]}]))

;; =============================================================================
;; Fragment handlers
;; =============================================================================

(defn render-pool-fragment
  "Return an HTML string of pool stats for HTMX polling."
  [_context]
  (str (h/html (render-pool-stats (pool-stats)))))

(defn render-query-result
  "Execute SQL from request params and return an HTML table fragment.
   Reads from :params key; falls back to raw SQL string.
   Limits results to 50 rows. Handles errors gracefully."
  [req]
  (let [params  (get req :params {})
        sql     (str/trim (or (get params "sql") (get params :sql) ""))
        sys     state/system
        db-ctx  (get sys :boundary/db-context)
        ds      (when db-ctx (:datasource db-ctx))]
    (cond
      (str/blank? sql)
      (str (h/html [:p.no-data "Enter a SQL query and click Execute."]))

      (nil? ds)
      (str (h/html
            [:div.detail-panel.detail-panel-error
             [:p.error-title "Database unavailable"]
             [:p "No database connection found in the Integrant system."]]))

      :else
      (try
        (with-open [conn (.getConnection ds)]
          (let [stmt (.createStatement conn)
                _    (.setMaxRows stmt 50)
                rs   (.executeQuery stmt sql)
                md   (.getMetaData rs)
                cols (mapv #(.getColumnName md %) (range 1 (inc (.getColumnCount md))))
                rows (loop [acc []]
                       (if (.next rs)
                         (recur (conj acc (mapv #(.getString rs %) cols)))
                         acc))]
            (let [col-width (if (> (count cols) 6) "minmax(140px, 1fr)" "1fr")
                  tpl       (str/join " " (repeat (count cols) col-width))]
              (str (h/html
                    [:div.query-result
                     [:div.query-result-meta
                      [:span.result-count (str (count rows) " row(s) returned")]
                      (when (= (count rows) 50)
                        [:span.result-limit " (limited to 50 rows)"])]
                     (if (seq rows)
                       [:div {:style "overflow-x:auto"}
                        (c/data-table
                         {:columns     cols
                          :col-template tpl
                          :rows        (map (fn [row]
                                              {:cells (map (fn [v]
                                                             (if v
                                                               [:span {:style "word-break:break-all"} v]
                                                               [:span.text-muted "NULL"]))
                                                           row)})
                                            rows)})]
                       [:p.no-data "Query returned no rows."])])))))
        (catch Exception e
          (str (h/html
                [:div.detail-panel.detail-panel-error
                 [:p.error-title "Query error"]
                 [:p.error-message (.getMessage e)]])))))))

;; =============================================================================
;; Page
;; =============================================================================

(defn render
  "Render the Database Explorer full page."
  [opts]
  (let [migrations (migration-data)
        tables     (or (table-list) [])
        stats      (pool-stats)
        applied    (count (filter #(= :applied (:status %)) migrations))
        pending    (count (filter #(= :pending (:status %)) migrations))]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/db"
                  :title       "Database Explorer"})

     ;; Top row: migrations + pool stats
     [:div.two-col

      ;; Migration status
      (c/card
       {:title "Migrations"
        :right [:span.migration-summary
                [:span.migration-applied (str applied " applied")]
                " / "
                (when (pos? pending)
                  [:span.migration-pending (str pending " pending")])
                (when (zero? pending)
                  [:span.migration-ok "all applied"])]}
       (c/data-table
        {:columns      ["" "Migration" "Status"]
         :col-template "32px 1fr 80px"
         :rows         (migration-rows migrations)}))

      ;; HikariCP pool stats (HTMX polling)
      (c/card
       {:title "Connection Pool"
        :right [:span.pool-badge (if stats "HikariCP" "unavailable")]}
       [:div#pool-status
        {:hx-get     "/dashboard/fragments/pool-status"
         :hx-trigger "every 2s"
         :hx-swap    "innerHTML"}
        (render-pool-stats stats)])]

     ;; Middle: table browser
     (c/card
      {:title "Table Browser"
       :right [:span.table-count (str (count tables) " tables")]}
      (c/data-table
       {:columns      ["Table" ""]
        :col-template "1fr 80px"
        :rows         (table-rows tables)}))

     ;; Bottom: query runner
     (c/card
      {:title "Query Runner"}
      [:div.query-runner
       [:form
        {:hx-post    "/dashboard/fragments/query-result"
         :hx-target  "#query-result"
         :hx-swap    "innerHTML"}
        [:textarea.query-input
         {:name        "sql"
          :placeholder "SELECT * FROM users LIMIT 10;"
          :rows        5
          :spellcheck  "false"
          :autocomplete "off"}]
        [:div.query-runner-actions
         [:button.btn.btn-primary {:type "submit"} "Execute"]
         [:span.query-hint "Results limited to 50 rows"]]]
       [:div#query-result
        [:p.no-data "Enter a SQL query and click Execute."]]]))))
