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
            [boundary.devtools.shell.repl :as devtools-repl]
            [boundary.platform.shell.adapters.database.common.core :as db]
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

(defn halt
  "Stop the system."
  []
  (ig-repl/halt))

(defn reset
  "Reload code and restart the system."
  []
  (ig-repl/reset))

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
   (when-let [handler (get (system) :boundary/http-handler)]
     (devtools-repl/simulate-request handler method path opts))))

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
   (when-let [ctx (db-context)]
     (devtools-repl/run-query ctx table opts))))

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
    (status)))

(defn go
  "Start the system with guidance dashboard."
  []
  (let [result (ig-repl/go)]
    (print-startup-dashboard)
    (maybe-show-tip :start)
    result))

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
  ...)



