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
            [boundary.devtools.shell.module-wiring]   ;; Load devtools Integrant methods
            [boundary.devtools.core.guidance :as guidance]
            [boundary.devtools.core.error-codes :as error-codes]
            [integrant.repl :as ig-repl]
            [integrant.repl.state :as state]
            [clojure.tools.logging :as log]))

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

(defn go
  "Start the system."
  []
  (ig-repl/go))

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
  "Get the current system configuration."
  []
  state/config)

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

(defn guidance-state
  "Get the guidance engine state atom."
  []
  (get (system) :boundary/guidance))

(defn guidance
  "Get or set the guidance level.
   (guidance)          ; returns current level
   (guidance :minimal) ; set to :minimal"
  ([]
   (when-let [state (guidance-state)]
     (:guidance-level @state)))
  ([level]
   (when-let [state (guidance-state)]
     (if (guidance/valid-level? level)
       (do (swap! state assoc :guidance-level level)
           (println (str "Guidance level set to :" (name level)))
           level)
       (println (str "Invalid level. Use one of: " (pr-str guidance/levels)))))))

(defn status
  "Show system health overview."
  []
  (let [sys   (system)
        level (or (guidance) :full)]
    (if (nil? sys)
      (println "System not running. Start with (go)")
      (let [components (count sys)
            modules    (->> (keys sys)
                            (filter #(and (keyword? %)
                                          (= "boundary" (namespace %))
                                          (not (contains? #{"settings" "postgresql" "sqlite"
                                                            "mysql" "h2" "http" "router"
                                                            "api-versioning" "pagination"
                                                            "logging" "metrics" "error-reporting"
                                                            "http-server" "db-context" "guidance"}
                                                          (name %)))))
                            (map #(name %))
                            sort)]
        (println (guidance/format-startup-dashboard
                  {:components     components
                   :errors         0
                   :web-url        "http://localhost:3000"
                   :admin-url      "http://localhost:3000/admin"
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
                       (not (contains? #{"settings" "postgresql" "sqlite" "mysql" "h2"
                                         "http" "router" "api-versioning" "pagination"
                                         "logging" "metrics" "error-reporting"
                                         "http-server" "db-context" "guidance"}
                                       (name %)))))
         (map #(name %))
         sort
         vec)))

(defn commands
  "Show all available REPL commands."
  []
  (println (guidance/format-commands)))

;; =============================================================================
;; Enhanced System Lifecycle with Guidance
;; =============================================================================

(defn- print-startup-dashboard []
  (when-let [state (guidance-state)]
    (let [level (:guidance-level @state)]
      (when (= level :full)
        (status)))))

(def original-go go)
(defn go
  "Start the system with guidance dashboard."
  []
  (let [result (original-go)]
    (print-startup-dashboard)
    result))

;; =============================================================================
;; Quick Start Message
;; =============================================================================

(println "\n\u250C\u2500 Boundary Development REPL \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510")
(println "\u2502 (go)       Start the system              \u2502")
(println "\u2502 (reset)    Reload and restart             \u2502")
(println "\u2502 (halt)     Stop the system                \u2502")
(println "\u2502 (status)   System health overview         \u2502")
(println "\u2502 (commands) All available commands         \u2502")
(println "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518\n")

(comment
  (go)
  (reset)
  (status)
  (commands)
  (modules)
  (guidance)
  (guidance :minimal)
  ...)



