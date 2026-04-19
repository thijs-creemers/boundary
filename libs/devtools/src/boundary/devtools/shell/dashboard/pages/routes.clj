(ns boundary.devtools.shell.dashboard.pages.routes
  (:require [boundary.devtools.shell.dashboard.layout :as layout]))

(defn render [opts]
  (layout/dashboard-page opts
                         [:div [:h2 "Route Explorer"] [:p "Coming soon"]]))

(defn render-try-result [_req]
  "<div>try result</div>")
