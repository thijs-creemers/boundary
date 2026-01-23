(ns user
  "REPL utilities and system management for development.
   
   This namespace is automatically loaded when starting a REPL,
   providing convenient functions for system lifecycle management.
   
   Usage:
     (go)      ; Start the system
     (reset)   ; Reload code and restart
     (halt)    ; Stop the system"
  (:require [boundary.config :as config]
            [boundary.platform.shell.system.wiring]  ;; Load Integrant init/halt methods
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
;; Quick Start Message
;; =============================================================================

(println "\n========================================")
(println "Boundary Development REPL")
(println "========================================")
(println "Available commands:")
(println "  (go)     - Start the system")
(println "  (reset)  - Reload and restart")
(println "  (halt)   - Stop the system")
(println "  (system) - View running system")
(println "\nSystem components:")
(println "  (db-context)        - Database context")
(println "  (user-service)      - User service")
(println "  (user-repository)   - User repository")
(println "  (session-repository) - Session repository")
(println "========================================\n")

(comment
  (go)
  (reset)
  ...)



