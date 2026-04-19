(ns boundary.devtools.shell.dashboard.pages.database
  (:require [boundary.devtools.shell.dashboard.layout :as layout]))

(defn render [opts]
  (layout/dashboard-page opts
                         [:div [:h2 "Database Explorer"] [:p "Coming soon"]]))

(defn render-pool-fragment [_context]
  "<div>pool stats</div>")

(defn render-query-result [_req]
  "<div>query result</div>")
