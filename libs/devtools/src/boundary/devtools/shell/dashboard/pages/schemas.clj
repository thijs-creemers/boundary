(ns boundary.devtools.shell.dashboard.pages.schemas
  (:require [boundary.devtools.shell.dashboard.layout :as layout]))

(defn render [opts _req]
  (layout/dashboard-page opts
                         [:div [:h2 "Schema Browser"] [:p "Coming soon"]]))
