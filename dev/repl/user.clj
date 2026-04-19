(ns user
  "REPL utilities and system management for development.

   This namespace is automatically loaded when starting a REPL,
   providing convenient functions for system lifecycle management.

   Usage:
     (go)        ; Start the system
     (reset)     ; Reload code and restart
     (halt)      ; Stop the system
     (status)    ; System health overview
     (routes)    ; Show all HTTP routes
     (commands)  ; Show all available commands"
  (:require [boundary.config :as config]
            [boundary.platform.shell.system.wiring]  ;; Load Integrant init/halt methods
            [boundary.devtools.core.guidance :as guidance]
            [boundary.devtools.core.introspection :as introspection]
            [boundary.devtools.core.schema-tools :as schema-tools]
            [boundary.devtools.core.documentation :as docs]
            [boundary.devtools.core.state-analyzer :as state-analyzer]
            [boundary.devtools.core.error-classifier :as classifier]
            [boundary.devtools.core.auto-fix :as auto-fix]
            [boundary.devtools.shell.dashboard.server]  ;; Load dashboard Integrant init/halt methods
            [boundary.devtools.shell.repl :as devtools-repl]
            [boundary.devtools.shell.repl-error-handler :as repl-errors]
            [boundary.devtools.shell.fcis-checker :as fcis]
            [boundary.devtools.shell.auto-fix :as auto-fix-shell]
            [boundary.devtools.shell.recording :as rec]
            [boundary.devtools.shell.router :as dev-router]
            [boundary.devtools.shell.prototype :as prototype]
            [boundary.platform.shell.adapters.database.common.core :as db]
            [boundary.platform.shell.system.wiring :as wiring]
            [integrant.repl :as ig-repl]
            [integrant.repl.state :as state]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Guidance state — managed here, not via Integrant (avoids non-REPL breakage)
;; =============================================================================

(defonce guidance-state* (atom {:guidance-level :full
                                :shown-tips     #{}}))

;; =============================================================================
;; Integrant REPL Configuration
;; =============================================================================

(ig-repl/set-prep!
 (fn []
   (log/info "Preparing system configuration")
   (let [cfg (config/load-config)]
     (log/info "Configuration loaded" {:adapter (config/db-adapter cfg)})
     (config/ig-config cfg))))

;; =============================================================================
;; REPL Convenience Functions
;; =============================================================================

(declare guidance)

(defn halt
  "Stop the system."
  []
  (ig-repl/halt))

(defn reset
  "Reload code and restart the system."
  []
  ;; No startup dashboard on reset — dashboard prints once on go, not every reload.
  (try
    (dev-router/clear-dynamic-state!)
    (let [result (ig-repl/reset)]
      (fcis/check-fcis-violations!)
      result)
    (catch Exception e
      (repl-errors/handle-repl-error! e {:guidance-level (guidance)})
      (fcis/check-fcis-violations!)
      (throw e))))

(defn system
  "Get the current running system."
  []
  state/system)

(defn config
  "View running config. Optional section drills into a subsection.
   (config)            ; raw config map
   (config :database)  ; database section (printed, secrets redacted)
   (config :http)      ; HTTP section"
  ([] state/config)
  ([section]
   (println (introspection/format-config-tree state/config section))))

;; =============================================================================
;; Development Utilities
;; =============================================================================

(defn db-context
  "Get the database context from the running system."
  []
  (get (system) :boundary/db-context))

(defn user-service
  "Get the user service from the running system."
  []
  (get (system) :boundary/user-service))

(defn user-repository
  "Get the user repository from the running system."
  []
  (get (system) :boundary/user-repository))

(defn session-repository
  "Get the session repository from the running system."
  []
  (get (system) :boundary/session-repository))

;; =============================================================================
;; Devtools REPL Helpers
;; =============================================================================

(defn guidance
  "Get or set the guidance level.
   (guidance)          ; returns current level
   (guidance :minimal) ; set to :minimal"
  ([]
   (:guidance-level @guidance-state*))
  ([level]
   (if (guidance/valid-level? level)
     (do (swap! guidance-state* assoc :guidance-level level)
         (println (str "Guidance level set to :" (name level)))
         level)
     (println (str "Invalid level. Use one of: " (pr-str guidance/levels))))))

(def ^:private infra-keys
  "Integrant keys that are infrastructure, not application modules."
  #{"settings" "postgresql" "sqlite" "mysql" "h2" "http" "router"
    "api-versioning" "pagination" "logging" "metrics" "error-reporting"
    "http-server" "db-context"})

(defn- actual-http-port
  "Get the actual port the HTTP server is listening on.
   Falls back to configured port if server object isn't accessible."
  []
  (let [sys (system)
        server (get sys :boundary/http-server)]
    (if server
      (try
        (let [connector (first (.getConnectors server))]
          (.getLocalPort connector))
        (catch Exception _
          (or (get-in (config) [:boundary/http :port]) 3000)))
      (or (get-in (config) [:boundary/http :port]) 3000))))

(defn status
  "Show system health overview."
  []
  (let [sys   (system)
        level (guidance)]
    (if (nil? sys)
      (println "System not running. Start with (go)")
      (let [cfg        (config)
            http-port  (actual-http-port)
            http-host  (or (get-in cfg [:boundary/http :host]) "localhost")
            admin-cfg  (get cfg :boundary/admin)
            admin-path (or (:base-path admin-cfg) "/admin")
            base-url   (str "http://" (if (= http-host "0.0.0.0") "localhost" http-host)
                            ":" http-port)
            components (count sys)
            modules    (->> (keys sys)
                            (filter #(and (keyword? %)
                                          (= "boundary" (namespace %))
                                          (not (contains? infra-keys (name %)))))
                            (map #(name %))
                            sort)]
        (println (guidance/format-startup-dashboard
                  {:components     components
                   :errors         0
                   :web-url        base-url
                   :admin-url      (str base-url admin-path)
                   :nrepl-port     7888
                   :modules        modules
                   :guidance-level level}))))))

(defn modules
  "List active modules."
  []
  (when-let [sys (system)]
    (->> (keys sys)
         (filter #(and (keyword? %)
                       (= "boundary" (namespace %))
                       (not (contains? infra-keys (name %)))))
         (map #(name %))
         sort
         vec)))

(defn commands
  "Show all available REPL commands."
  []
  (println (guidance/format-commands)))

;; =============================================================================
;; Contextual Tips
;; =============================================================================

(defn- maybe-show-tip
  "Show a contextual tip if guidance is :full and a tip is available."
  [context]
  (when (= :full (guidance))
    (let [shown (:shown-tips @guidance-state*)]
      (when-let [[tip new-shown] (guidance/pick-tip context shown)]
        (swap! guidance-state* assoc :shown-tips new-shown)
        (println (guidance/format-tip tip :full))))))

;; =============================================================================
;; Phase 3: Error Experience — Error Pipeline Wiring
;; =============================================================================

(defmacro ^:private with-error-handling
  "Wrap body in try/catch that runs the error pipeline on exceptions.
   Re-throws after formatting so *e is updated in the REPL."
  [& body]
  `(try ~@body
        (catch Exception e#
          (repl-errors/handle-repl-error! e# {:guidance-level (guidance)})
          (throw e#))))

(defn fix!
  "Auto-fix the last error if a fix is available.
   (fix!)     ; fix last error (recommended — nREPL-safe)
   (fix! ex)  ; fix a specific exception directly"
  ([] (fix! @repl-errors/last-exception*))
  ([exception]
   (if (nil? exception)
     (println "No recent error. Trigger an error first, then call (fix!)")
     (let [classified (classifier/classify exception)
           fix-desc   (auto-fix/match-fix classified)]
       (if fix-desc
         (auto-fix-shell/execute-fix! fix-desc
                                      {:guidance-level (guidance)
                                       :confirm-fn     #(do (print (str % " [y/N] "))
                                                            (flush)
                                                            (= "y" (read-line)))})
         (println "No auto-fix available for this error. Try (explain *e) for AI analysis."))))))

;; =============================================================================
;; Phase 2: REPL Power — Route Introspection
;; =============================================================================

(defn routes
  "Show HTTP routes. Optional filter: module keyword or path string.
   (routes)              ; all routes
   (routes :user)        ; filter by module
   (routes \"/api/users\") ; filter by path"
  ([]
   (routes nil))
  ([filter-key]
   (when-let [handler (get (system) :boundary/http-handler)]
     (let [all-routes (devtools-repl/extract-routes-from-handler handler)
           filtered (if filter-key
                      (introspection/filter-routes all-routes filter-key)
                      all-routes)]
       (println (introspection/format-route-table filtered))))))

;; =============================================================================
;; Phase 2: REPL Power — Request Simulation
;; =============================================================================

(defn simulate
  "Simulate an HTTP request against the running system.
   (simulate :get \"/api/v1/users\")
   (simulate :post \"/api/v1/users\" {:body {:email \"test@example.com\"}})
   (simulate :get \"/api/v1/users\" {:headers {\"authorization\" \"Bearer ...\"}})"
  ([method path]
   (simulate method path {}))
  ([method path opts]
   ;; when-let returns nil silently when system is not running.
   ;; This is intentional — (status) handles "not running" messaging.
   (with-error-handling
     (when-let [handler (get (system) :boundary/http-handler)]
       (devtools-repl/simulate-request handler method path opts)))))

;; =============================================================================
;; Phase 2: REPL Power — Data Exploration
;; =============================================================================

(defn query
  "Quick query a database table.
   (query :users)
   (query :users {:where [:= :active true] :limit 5})"
  ([table]
   (query table {}))
  ([table opts]
   (with-error-handling
     (when-let [ctx (db-context)]
       (devtools-repl/run-query ctx table opts)))))

(defn count-rows
  "Count rows in a table.
   (count-rows :users)"
  [table]
  (when-let [ctx (db-context)]
    (devtools-repl/count-rows ctx table)))

(defn schema
  "Pretty-print a Malli schema.
   (schema [:map [:id :uuid] [:name :string]])"
  [schema-def]
  (println (schema-tools/format-schema-tree schema-def)))

(defn schema-diff
  "Compare two Malli schemas and show differences.
   (schema-diff schema-a schema-b)"
  [a b]
  (println (schema-tools/format-schema-diff (schema-tools/schema-diff a b))))

(defn validate
  "Validate data against a Malli schema.
   (validate [:map [:email :string]] {:email \"test@example.com\"})"
  [schema-def data]
  (require '[malli.core :as m])
  (let [explain-fn (resolve 'malli.core/explain)
        result (explain-fn schema-def data)]
    (if result
      (do (println "Validation FAILED:")
          (doseq [error (:errors result)]
            (println (str "  " (:in error) " - " (or (:message error) (pr-str (:schema error))))))
          result)
      (println "Valid."))))

(defn generate
  "Generate an example value from a Malli schema.
   (generate [:map [:id :uuid] [:name :string]])"
  [schema-def]
  (schema-tools/generate-example schema-def))

;; =============================================================================
;; Phase 2: REPL Power — Quality Tools
;; =============================================================================

(defn test-module
  "Run tests for a module from the REPL.
   Shells out to the test runner so it works from any REPL session.
   (test-module :user)
   (test-module :user :unit)"
  ([module]
   (test-module module nil))
  ([module tier]
   (let [{:keys [exit output]} (devtools-repl/run-tests module tier)]
     (println output)
     (if (zero? exit)
       (println "Tests passed.")
       (println "Tests failed.")))))

(defn lint
  "Run clj-kondo from the REPL."
  []
  (let [{:keys [exit output]} (devtools-repl/run-lint)]
    (println output)
    (if (zero? exit)
      (println "Linting passed.")
      (println "Linting found issues."))))

(defn check-all
  "Run all quality checks from the REPL."
  []
  (let [{:keys [exit output]} (devtools-repl/run-checks)]
    (println output)
    (if (zero? exit)
      (println "All checks passed.")
      (println "Some checks failed."))))

;; =============================================================================
;; Phase 2: REPL Power — Documentation & Guidance
;; =============================================================================

(defn guide
  "Look up in-REPL documentation for a Boundary topic.
   (guide :scaffold)       ; scaffolding guide
   (guide :fcis)           ; FC/IS architecture
   (guide :topics)         ; list all topics"
  [topic]
  (if (= topic :topics)
    (do (println "Available topics:")
        (doseq [t (docs/list-topics)]
          (println (str "  (guide :" (name t) ")"))))
    (println (devtools-repl/show-doc topic))))

(defn next-steps
  "State-aware guidance: what should you do next?"
  []
  (let [mods (or (modules) [])
        module-count (count mods)
        findings (remove nil?
                         [(when-let [ctx (db-context)]
                            (try
                              (let [tables (db/list-tables ctx)]
                                (when (empty? tables)
                                  {:type :no-tables :level :warn
                                   :msg "No database tables found"
                                   :fix "  bb migrate up"}))
                              (catch Exception _ nil)))])]
    (println (state-analyzer/format-findings findings module-count))))

;; =============================================================================
;; Enhanced System Lifecycle with Guidance
;; =============================================================================

(defn- print-startup-dashboard []
  (when (= (guidance) :full)
    (status)
    (when-let [dashboard (get state/system :boundary/dashboard)]
      (let [port (or (:port dashboard) 9999)]
        (println (str "  Dashboard: http://localhost:" port "/dashboard"))))))

(defn go
  "Start the system with guidance dashboard."
  []
  (try
    (let [result (ig-repl/go)]
      (print-startup-dashboard)
      (fcis/check-fcis-violations!)
      (maybe-show-tip :start)
      result)
    (catch Exception e
      (repl-errors/handle-repl-error! e {:guidance-level (guidance)})
      (fcis/check-fcis-violations!)
      (throw e))))

;; =============================================================================
;; Phase 5: Recording, Dynamic Routes, Taps, Prototyping
;; =============================================================================

(defn recording
  "Time-travel debugging: capture, replay, and diff HTTP requests.
   (recording :start)              — start capturing
   (recording :stop)               — stop capturing
   (recording :list)               — show captured requests
   (recording :replay N)           — replay entry N
   (recording :replay N overrides) — replay with modified body
   (recording :diff M N)           — diff two entries
   (recording :save \"name\")      — save to disk
   (recording :load \"name\")      — load from disk"
  [command & args]
  (case command
    :start  (do
              (rec/start-recording!)
              ;; Install capture middleware into the live HTTP handler so that
              ;; every request flowing through Jetty is recorded.
              (when-let [live-handler (wiring/current-handler)]
                (rec/store-pre-recording-handler! live-handler)
                (let [wrapped ((rec/capture-middleware) live-handler)]
                  (wiring/swap-handler! wrapped))))
    :stop   (do
              (rec/stop-recording!)
              ;; Restore the original (unwrapped) handler, removing the capture
              ;; middleware from the live request path.
              (when-let [original (rec/restore-pre-recording-handler!)]
                (wiring/swap-handler! original)))
    :list   (rec/list-entries)
    :replay (let [idx (first args)
                  overrides (second args)
                  simulate-fn (fn [method path opts] (simulate method path opts))]
              (rec/replay-entry! idx simulate-fn overrides))
    :diff   (rec/diff-entries (first args) (second args))
    :save   (rec/save-session! (first args))
    :load   (rec/load-session! (first args))
    (println (str "Unknown recording command: " command
                  ". Use :start, :stop, :list, :replay, :diff, :save, :load"))))

(defn- ensure-dynamic-dispatch!
  "Ensure the live handler is wrapped with dynamic-dispatch middleware.
   Idempotent — if already wrapped (marker metadata), this is a no-op."
  []
  (when-let [live-handler (wiring/current-handler)]
    (when-not (:devtools/dynamic-dispatch (meta live-handler))
      (let [wrapped (dev-router/wrap-dynamic-dispatch live-handler)]
        (wiring/swap-handler!
         (with-meta wrapped {:devtools/dynamic-dispatch true}))))))

(defn defroute!
  "Add a route at runtime for rapid prototyping.
   (defroute! :get \"/api/test\" (fn [req] {:status 200 :body {:hello \"world\"}}))"
  [method path handler-fn]
  (dev-router/add-dynamic-route! method path handler-fn)
  (ensure-dynamic-dispatch!)
  (println (format "✓ Route added: %s %s (live)" (name method) path)))

(defn remove-route!
  "Remove a dynamically added route."
  [method path]
  (dev-router/remove-dynamic-route! method path)
  (println (format "✓ Route removed: %s %s" (name method) path)))

(defn dynamic-routes
  "List all dynamically added routes."
  []
  (let [routes (dev-router/list-dynamic-routes)]
    (if (empty? routes)
      (println "No dynamic routes.")
      (doseq [{:keys [method path]} routes]
        (println (format "  %s %s" (name method) path))))))

(defn tap-handler!
  "Intercept requests to a handler with a callback function.
   Taps modify Reitit interceptor chains and take effect on the next (reset).
   (tap-handler! :create-user (fn [ctx] (println (:request ctx)) ctx))"
  [handler-kw callback-fn]
  (dev-router/add-tap! handler-kw callback-fn)
  (println (format "✓ Tap registered on %s — call (reset) to activate" handler-kw)))

(defn untap-handler!
  "Remove a tap from a handler. Takes effect on the next (reset)."
  [handler-kw]
  (dev-router/remove-tap! handler-kw)
  (println (format "✓ Tap removed from %s — call (reset) to apply" handler-kw)))

(defn taps
  "List active handler taps."
  []
  (let [tap-list (dev-router/list-taps)]
    (if (empty? tap-list)
      (println "No active taps.")
      (doseq [t tap-list]
        (println (str "  " t))))))

(defn restart-component
  "Hot-swap a single Integrant component without full system reset.
   (restart-component :boundary/http-server)"
  [component-key]
  (require 'boundary.devtools.shell.repl)
  (let [restart-fn (resolve 'boundary.devtools.shell.repl/restart-component)]
    (restart-fn #'integrant.repl.state/system
                state/config
                component-key)))

(defn scaffold!
  "Generate a module from the REPL.
   (scaffold! \"invoice\" {:fields {:customer [:string {:min 1}]
                                    :amount [:decimal {:min 0}]}})"
  [module-name opts]
  (prototype/scaffold! module-name opts))

(defn prototype!
  "Generate a complete working module: scaffold + migrate + reset.
   (prototype! :invoice
     {:fields {:customer [:string {:min 1}]
               :amount [:decimal {:min 0}]
               :status [:enum [:draft :sent :paid]]}
      :endpoints [:crud :list]})"
  [module-name spec]
  (let [name-str (if (keyword? module-name) (name module-name) module-name)]
    (prototype/prototype! name-str spec)))

;; =============================================================================
;; Quick Start Message
;; =============================================================================

(println "\n\u250C\u2500 Boundary Development REPL \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510")
(println "\u2502 (go)         Start the system                \u2502")
(println "\u2502 (reset)      Reload and restart               \u2502")
(println "\u2502 (halt)       Stop the system                  \u2502")
(println "\u2502 (status)     System health overview           \u2502")
(println "\u2502 (routes)     Show HTTP routes                 \u2502")
(println "\u2502 (commands)   All available commands            \u2502")
(println "\u2502 (fix!)       Auto-fix last error              \u2502")
(println "\u2502 (guide :topics) Browse documentation          \u2502")
(println "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518\n")

(comment
  (go)
  (reset)
  (status)
  (routes)
  (routes :user)
  (config :database)
  (simulate :get "/api/v1/users")
  (query :users)
  (schema [:map [:id :uuid] [:name :string]])
  (guide :topics)
  (commands)
  (modules)
  (guidance)
  (guidance :minimal)
  (fix!)
  ...)



