(ns boundary.devtools.shell.router
  "Stateful router management for runtime route/tap modifications.
   Tracks dynamic routes and taps in atoms, rebuilds the handler via
   platform's swap-handler!."
  (:require [boundary.devtools.core.router :as core-router]
            [clojure.string]
            [reitit.core :as rc]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]))

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
   handler is called through standard Ring middleware (params, cookies)
   so it sees parsed query/form params and cookies like normal routes.
   Otherwise the request falls through to the base handler."
  [base-handler]
  (let [;; Build a handler that routes through dynamic-routes with standard
        ;; middleware applied, so dynamic handlers see parsed params/cookies.
        dynamic-handler (-> (fn [request]
                              (let [method (:request-method request)
                                    path   (:uri request)
                                    match  (get @dynamic-routes [method path])]
                                (if match
                                  ((:handler match) request)
                                  (base-handler request))))
                            wrap-cookies
                            wrap-params)]
    (fn [request]
      (let [method (:request-method request)
            path   (:uri request)]
        (if (get @dynamic-routes [method path])
          (dynamic-handler request)
          (base-handler request))))))

(defn- handler-name->pattern
  "Convert a tap handler keyword like :create-user to a regex pattern
   that matches the handler function's string representation.
   Matches both kebab-case (symbol form) and underscore (compiled fn)."
  [handler-kw]
  (let [s (name handler-kw)
        ;; Match either kebab-case or underscore variant in the fn string
        pattern (str "(?:" s "|" (clojure.string/replace s "-" "_") ")")]
    (re-pattern pattern)))

(defn- find-matching-tap
  "Find a registered tap whose keyword matches the handler function string.
   Returns the tap function or nil."
  [active-taps handler-str]
  (when handler-str
    (some (fn [[tap-kw tap-fn]]
            (when (re-find (handler-name->pattern tap-kw) handler-str)
              tap-fn))
          active-taps)))

(defn wrap-taps
  "Ring middleware that invokes registered tap callbacks.
   Uses the Reitit router (from handler metadata) to pre-match the request
   and find the handler function. Matches tap keywords against the handler's
   string representation (e.g. :create-user matches a handler whose str
   contains 'create_user' or 'create-user').
   The tap receives {:request request :match match-data} and its return
   value replaces the context (allowing request modification)."
  [base-handler]
  (let [router (:reitit/router (meta base-handler))]
    (fn [request]
      (let [active-taps @taps]
        (if (or (empty? active-taps) (nil? router))
          (base-handler request)
          (let [match      (rc/match-by-path router (:uri request))
                method     (:request-method request)
                match-data (when match (get-in match [:data method]))
                handler-fn (:handler match-data)
                handler-str (when handler-fn (str handler-fn))
                tap-fn     (find-matching-tap active-taps handler-str)]
            (if tap-fn
              (let [ctx     {:request request :match (:data match)}
                    result  (tap-fn ctx)
                    request (or (:request result) request)]
                (base-handler request))
              (base-handler request))))))))

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
