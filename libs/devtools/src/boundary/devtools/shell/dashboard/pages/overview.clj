(ns boundary.devtools.shell.dashboard.pages.overview
  (:require [boundary.devtools.shell.dashboard.layout :as layout]))

(defn render [opts]
  (layout/dashboard-page opts
                         [:div [:h2 "System Overview"] [:p "Coming soon"]]))
