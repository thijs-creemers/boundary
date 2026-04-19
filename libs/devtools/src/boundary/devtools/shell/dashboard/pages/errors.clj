(ns boundary.devtools.shell.dashboard.pages.errors
  (:require [boundary.devtools.shell.dashboard.layout :as layout]))

(defn render [opts]
  (layout/dashboard-page opts
                         [:div [:h2 "Error Dashboard"] [:p "Coming soon"]]))

(defn render-fragment []
  "<div>fragment</div>")
