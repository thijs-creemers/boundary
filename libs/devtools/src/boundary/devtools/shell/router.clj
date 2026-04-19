(ns boundary.devtools.shell.router
  "Stateful router management for runtime route/tap modifications.
   Tracks dynamic routes and taps in atoms, rebuilds the handler via
   platform's swap-handler!."
  (:require [boundary.devtools.core.router :as core-router]))

(defonce ^:private dynamic-routes (atom {}))
(defonce ^:private taps (atom {}))
(defonce ^:private recording-active? (atom false))

(defn add-dynamic-route! [method path handler-fn]
  (swap! dynamic-routes assoc [method path] {:handler handler-fn})
  nil)

(defn remove-dynamic-route! [method path]
  (swap! dynamic-routes dissoc [method path])
  nil)

(defn list-dynamic-routes []
  (mapv (fn [[[method path] _]] {:method method :path path}) @dynamic-routes))

(defn add-tap! [handler-name tap-fn]
  (swap! taps assoc handler-name tap-fn)
  nil)

(defn remove-tap! [handler-name]
  (swap! taps dissoc handler-name)
  nil)

(defn list-taps []
  (vec (keys @taps)))

(defn set-recording! [active?]
  (reset! recording-active? active?))

(defn recording-active?* []
  @recording-active?)

(defn apply-dynamic-routes [base-routes]
  (reduce
   (fn [routes [[method path] {:keys [handler]}]]
     (core-router/add-route routes method path handler))
   base-routes
   @dynamic-routes))

(defn apply-taps [routes]
  (reduce
   (fn [routes [handler-name tap-fn]]
     (core-router/inject-tap-interceptor routes handler-name tap-fn))
   routes
   @taps))

(defn rebuild-router!
  "Rebuild the HTTP handler with current dynamic routes and taps applied."
  [base-routes compile-fn swap-fn]
  (let [modified-routes (-> base-routes apply-dynamic-routes apply-taps)
        new-handler (compile-fn modified-routes)]
    (swap-fn new-handler)))

(defn clear-dynamic-state! []
  (reset! dynamic-routes {})
  (reset! taps {})
  (reset! recording-active? false)
  nil)
