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

(defn wrap-dynamic-dispatch
  "Ring middleware that checks the dynamic-routes atom on every request.
   When a request matches a registered [method path] pair, the dynamic
   handler is called directly (bypassing the compiled Reitit router).
   Otherwise the request falls through to the base handler."
  [base-handler]
  (fn [request]
    (let [method (:request-method request)
          path   (:uri request)
          match  (get @dynamic-routes [method path])]
      (if match
        ((:handler match) request)
        (base-handler request)))))

(defn wrap-taps
  "Ring middleware that invokes registered tap callbacks.
   Checks the Reitit match data for a :name that matches a registered tap.
   The tap receives {:request request :match match-data} and its return
   value replaces the context (allowing request modification)."
  [base-handler]
  (fn [request]
    (let [active-taps @taps]
      (if (empty? active-taps)
        (base-handler request)
        ;; Reitit injects :reitit.core/match into the request
        (let [match      (:reitit.core/match request)
              match-data (when match (:data match))
              handler-name (when match-data (:name match-data))
              tap-fn     (when handler-name (get active-taps handler-name))]
          (if tap-fn
            (let [ctx     {:request request :match match-data}
                  result  (tap-fn ctx)
                  request (or (:request result) request)]
              (base-handler request))
            (base-handler request)))))))

(defn has-taps? []
  (not (empty? @taps)))

(defn clear-dynamic-state!
  "Clear ephemeral dynamic state on reset.
   Taps are NOT cleared — they persist across resets because (reset) is the
   documented way to activate them."
  []
  (reset! dynamic-routes {})
  (reset! recording-active? false)
  nil)

(defn clear-all-state!
  "Clear ALL dynamic state including taps. Use for full cleanup."
  []
  (clear-dynamic-state!)
  (reset! taps {})
  nil)
