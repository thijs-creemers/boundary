(ns boundary.realtime.shell.delivery
  "Node-local delivery: resolve a routing envelope to the local node's ws
   adapters and send. Built as a closure over the local registry + pubsub
   manager; registered with a message bus via start-subscriber!. Never calls
   service send methods (no re-publish recursion)."
  (:require [boundary.realtime.ports :as ports]))

(defn- adapters-for
  [registry pubsub-manager {:keys [route target]}]
  (case route
    :user       (ports/find-by-user registry target)
    :role       (ports/find-by-role registry target)
    :broadcast  (ports/all-connections registry)
    :connection (ports/find-adapters-by-ids registry [target])
    :topic      (if pubsub-manager
                  (ports/find-adapters-by-ids
                   registry
                   (ports/get-topic-subscribers pubsub-manager target))
                  [])
    []))

(defn make-delivery-fn
  "Return (fn [envelope] -> int): send the envelope's :message to every open
   local adapter the envelope resolves to; return how many were sent to."
  [registry pubsub-manager]
  (fn [{:keys [message] :as envelope}]
    (let [adapters (adapters-for registry pubsub-manager envelope)]
      (reduce
       (fn [n a]
         (if (ports/open? a)
           (do (ports/send-message a message) (inc n))
           n))
       0
       adapters))))
