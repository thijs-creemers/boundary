(ns boundary.devtools.shell.dashboard.pages.requests
  (:require [boundary.devtools.shell.dashboard.layout :as layout]))

(defn render [opts]
  (layout/dashboard-page opts
                         [:div [:h2 "Request Inspector"] [:p "Coming soon"]]))

(defn render-fragment []
  "<div>fragment</div>")
