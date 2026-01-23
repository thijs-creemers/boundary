(ns boundary.inventory.shell.web-handlers
  "Web UI handlers for inventory module."
  (:require [boundary.inventory.core.ui :as ui]
            [boundary.inventory.ports :as ports]))

(defn item-list-handler [service _config]
  (fn [_request]
    (let [items (ports/list-items service {})]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (ui/item-list-page items {})})))
