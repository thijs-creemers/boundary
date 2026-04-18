(ns boundary.devtools.shell.repl
  "Shell REPL helpers that access the running Integrant system.
   These functions take system components as arguments — they do NOT directly
   access the running system. Wiring happens in dev/repl/user.clj."
  (:require [boundary.devtools.core.documentation :as docs]
            [boundary.platform.shell.adapters.database.common.core :as db]
            [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [reitit.core]
            [reitit.ring])
  (:import (java.io ByteArrayInputStream)))

;; =============================================================================
;; Route extraction
;; =============================================================================

(def ^:private boundary-module-pattern
  "Regex to extract module name from a fully-qualified handler string."
  #"boundary\.([^.]+)\.")

(defn extract-module
  "Extract module name from a handler string like 'boundary.user.shell.http/list-users'.
   Returns the module name string (e.g. 'user', 'admin', 'platform') or nil
   for non-boundary handlers."
  [handler-str]
  (when (string? handler-str)
    (when-let [m (re-find boundary-module-pattern handler-str)]
      (second m))))

(defn extract-routes-from-handler
  "Extract route info from a compiled Ring handler backed by Reitit.
   Returns a seq of {:method :path :handler :module} maps, or nil on error."
  [http-handler]
  (try
    (when-let [router (reitit.ring/get-router http-handler)]
      (let [all-routes (reitit.core/routes router)
            http-methods [:get :post :put :patch :delete :head :options]]
        (for [[path data] all-routes
              method http-methods
              :when (contains? data method)
              :let [handler-fn (get-in data [method :handler])
                    handler-str (str handler-fn)]]
          {:method  method
           :path    path
           :handler handler-str
           :module  (extract-module handler-str)})))
    (catch Exception _
      nil)))

;; =============================================================================
;; Request simulation
;; =============================================================================

(defn- encode-query-string
  "Encode a map of query params into a URL query string.
   Values are URL-encoded. Keys are converted to strings via `name` if keywords."
  [params]
  (str/join "&"
            (map (fn [[k v]]
                   (str (java.net.URLEncoder/encode (name k) "UTF-8")
                        "="
                        (java.net.URLEncoder/encode (str v) "UTF-8")))
                 params)))

(defn build-simulate-request
  "Build a Ring request map for simulating an HTTP call.

   opts keys:
     :body         — EDN/map body, will be JSON-encoded
     :headers      — extra headers map
     :query-params — map of query params (encoded into :query-string for wrap-params)"
  [method path opts]
  (let [{:keys [body headers query-params]} opts
        base-headers {"content-type" "application/json"
                      "accept"       "application/json"}
        all-headers (merge base-headers headers)
        request (cond-> {:request-method (keyword (str/lower-case (name method)))
                         :uri            path
                         :headers        all-headers
                         :server-name    "localhost"
                         :server-port    3000
                         :scheme         :http}
                  query-params (assoc :query-string (encode-query-string query-params))
                  body
                  (assoc :body
                         (ByteArrayInputStream.
                          (.getBytes (json/generate-string body) "UTF-8"))))]
    request))

(defn simulate-request
  "Send a simulated HTTP request to the given Ring handler.
   Returns {:status :headers :body} with body JSON-decoded when possible."
  [http-handler method path opts]
  (let [request (build-simulate-request method path opts)
        response (http-handler request)
        raw-body (:body response)
        body-str (cond
                   (string? raw-body)
                   raw-body
                   (instance? java.io.InputStream raw-body)
                   (slurp raw-body)
                   :else
                   (str raw-body))
        parsed-body (try
                      (json/parse-string body-str true)
                      (catch Exception _
                        body-str))]
    {:status  (:status response)
     :headers (:headers response)
     :body    parsed-body}))

;; =============================================================================
;; Data exploration
;; =============================================================================

(defn build-query-map
  "Build a HoneySQL query map for the given table.

   opts keys:
     :where    — HoneySQL where clause
     :order-by — HoneySQL order-by clause
     :limit    — row limit (default 20)"
  [table opts]
  (let [{:keys [where order-by limit]} opts
        base {:select [:*]
              :from   [table]
              :limit  (or limit 20)}]
    (cond-> base
      where    (assoc :where where)
      order-by (assoc :order-by order-by))))

(defn run-query
  "Execute a SELECT query against the given table via db/execute-query!.
   Returns a seq of row maps."
  [db-context table opts]
  (let [query (build-query-map table opts)]
    (db/execute-query! db-context query)))

(defn count-rows
  "Count total rows in the given table. Returns a single integer."
  [db-context table]
  (let [query {:select [[[:count :*] :count]]
               :from   [table]}
        result (db/execute-one! db-context query)]
    (:count result)))

;; =============================================================================
;; Handler tracing
;; =============================================================================

(defonce ^:private active-traces
  (atom {}))

(defn set-trace!
  "Register a trace for the given handler name.
   Returns a confirmation message string."
  [handler-name]
  (swap! active-traces assoc handler-name true)
  (str "Tracing enabled for: " handler-name))

(defn remove-trace!
  "Remove a trace for the given handler name.
   Returns a confirmation message string."
  [handler-name]
  (swap! active-traces dissoc handler-name)
  (str "Tracing disabled for: " handler-name))

(defn active-traces-list
  "Return the set of handler names currently being traced."
  []
  (keys @active-traces))

;; =============================================================================
;; Quality tools
;; =============================================================================

(defn run-tests
  "Run Kaocha tests for the given module keyword and optional meta filter keyword.
   Shells out to clojure -M:test so it works from any REPL session.
   Returns {:exit <int> :output <string>}."
  [module meta-filter]
  (let [cmd (str "clojure -M:test " (name module)
                 (when meta-filter
                   (str " --focus-meta " (name meta-filter))))
        result (shell/sh "bash" "-c" cmd)]
    {:exit   (:exit result)
     :output (str (:out result) (:err result))}))

(defn run-lint
  "Run clj-kondo linting across src, test, and all library sources.
   Uses a shell wrapper so glob patterns are expanded correctly.
   Returns {:exit <int> :output <string>}."
  []
  (let [result (shell/sh "bash" "-c"
                         "clojure -M:clj-kondo --lint src test libs/*/src libs/*/test")]
    {:exit   (:exit result)
     :output (str (:out result) (:err result))}))

(defn run-checks
  "Run all quality checks via 'bb check'.
   Returns {:exit <int> :output <string>}."
  []
  (let [result (shell/sh "bb" "check")]
    {:exit   (:exit result)
     :output (str (:out result) (:err result))}))

;; =============================================================================
;; Documentation
;; =============================================================================

(defn show-doc
  "Look up and print documentation for the given topic keyword.
   Shows available topics when the topic is unknown."
  [topic]
  (if-let [entry (docs/lookup topic)]
    (docs/format-doc entry)
    (str "Unknown topic: " topic
         "\n\nAvailable topics: "
         (str/join ", " (map str (docs/list-topics))))))
