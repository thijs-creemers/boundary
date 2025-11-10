(ns boundary.shell.interfaces.cli.middleware
  "CLI middleware for command execution (imperative shell).
   
   This namespace provides reusable CLI middleware that can be used across
   different modules and applications for consistent observability and
   error handling in command-line interfaces.
   
   Middleware functions handle CLI boundaries: command execution interception,
   context generation, logging, and exception handling.

   Features:
   - Command correlation ID generation
   - Tenant and user context extraction from config/environment
   - Structured command execution logging with observability context
   - Command timing and error reporting
   - Error reporting breadcrumb integration"
  (:require [boundary.error-reporting.core :as error-reporting]
            [boundary.logging.core :as logging]
            [clojure.tools.logging :as log])
  (:import (java.util UUID)))

;; =============================================================================
;; CLI Context Management
;; =============================================================================

;; =============================================================================
;; Error Reporting Helpers
;; =============================================================================

(defn- add-cli-breadcrumb
  "Add CLI operation breadcrumb with enriched context.
   
   Args:
     context: Error reporting context (from CLI execution context)
     operation: String describing the CLI operation
     status: :start, :success, or :error
     details: Map with operation details"
  [context operation status details]
  (error-reporting/add-breadcrumb
   (or context {}) ; Use provided context or empty map
   (str "CLI " operation " " (case status
                               :start "initiated"
                               :success "completed"
                               :error "failed"))
   "cli" ; category
   (case status
     :start :info
     :success :info
     :error :error) ; level
   (merge {:operation operation
           :status (name status)
           :source "cli-middleware"}
          details)))

(defn generate-correlation-id
  "Generates a new correlation ID for CLI command execution.
   
   Returns:
     UUID string for request correlation"
  []
  (str (UUID/randomUUID)))

(defn extract-tenant-context
  "Extracts tenant context from CLI options, config, or environment.
   
   Extraction priority:
   1. --tenant-id CLI option (explicit override)
   2. BOUNDARY_TENANT_ID environment variable
   3. :tenant-id from config map (if provided)
   
   Args:
     cli-opts: Parsed CLI options map
     config: Application config map (optional)
   
   Returns:
     Tenant ID string or nil"
  [cli-opts config]
  (or (:tenant-id cli-opts)
      (System/getenv "BOUNDARY_TENANT_ID")
      (:tenant-id config)))

(defn extract-user-context
  "Extracts user context from CLI options, config, or environment.
   
   For CLI commands, user context typically comes from:
   1. --user-id CLI option (for admin operations)
   2. BOUNDARY_USER_ID environment variable
   3. Current authenticated user from config
   
   Args:
     cli-opts: Parsed CLI options map
     config: Application config map (optional)
   
   Returns:
     User ID string or nil"
  [cli-opts config]
  (or (:user-id cli-opts)
      (System/getenv "BOUNDARY_USER_ID")
      (get-in config [:auth :user-id])))

(defn build-cli-context
  "Builds observability context for CLI command execution.
   
   Args:
     command: Command name string
     subcommand: Subcommand name string (optional)
     cli-opts: Parsed CLI options map
     config: Application config map (optional)
   
   Returns:
     Observability context map"
  [command subcommand cli-opts config]
  (let [correlation-id (generate-correlation-id)
        tenant-id (extract-tenant-context cli-opts config)
        user-id (extract-user-context cli-opts config)]
    (cond-> {:correlation-id correlation-id
             :command command
             :event :cli-command}
      subcommand (assoc :subcommand subcommand)
      tenant-id (logging/with-tenant-id tenant-id)
      user-id (logging/with-user-id user-id))))

;; =============================================================================
;; CLI Middleware Functions
;; =============================================================================

(defn with-cli-context
  "Higher-order function that wraps CLI command execution with observability context.
   
   This middleware:
   1. Generates correlation ID for command execution
   2. Extracts tenant/user context from CLI args and environment
   3. Builds observability context for logging and metrics
   4. Makes context available to the wrapped function
   
   Args:
     command: Command name string
     subcommand: Subcommand name string (optional)
     config: Application config map (optional)
     f: Function to execute with context (receives: service, opts, context)
   
   Returns:
     Function that takes (service, cli-opts) and executes with context"
  ([command f] (with-cli-context command nil nil f))
  ([command subcommand f] (with-cli-context command subcommand nil f))
  ([command subcommand config f]
   (fn [service cli-opts]
     (let [context (build-cli-context command subcommand cli-opts config)]
       (f service cli-opts context)))))

(defn with-cli-logging
  "Higher-order function that wraps CLI command execution with structured logging.
   
   Logs command start/completion with timing and context information, plus
   error reporting breadcrumbs for command lifecycle tracking.
   Should be used together with with-cli-context.
   
   Args:
     logger: ILogger instance (optional, uses tools.logging if not provided)
     f: Function to execute with logging (receives same args as passed)
   
   Returns:
     Function that logs around execution"
  ([f] (with-cli-logging nil f))
  ([logger f]
   (fn [service cli-opts context]
     (let [start-time (System/currentTimeMillis)
           {:keys [command subcommand correlation-id tenant-id user-id]} context
           command-name (str command (when subcommand (str " " subcommand)))
           command-details {:command command
                            :subcommand subcommand
                            :correlation-id correlation-id
                            :tenant-id tenant-id
                            :user-id user-id}]

       ;; Add breadcrumb for command start
       (add-cli-breadcrumb context "command" :start command-details)

       ;; Log command start
       (if logger
         (logging/log-function-entry logger command-name cli-opts context)
         (log/info "CLI command started"
                   (merge {:command command
                           :subcommand subcommand
                           :event :cli-command-start}
                          context)))

       (try
         (let [result (f service cli-opts context)
               duration (- (System/currentTimeMillis) start-time)
               final-context (assoc context
                                    :duration-ms duration
                                    :outcome :success
                                    :event :cli-command-complete)
               success-details (merge command-details
                                      {:duration-ms duration
                                       :outcome "success"})]

           ;; Add breadcrumb for successful completion
           (add-cli-breadcrumb context "command" :success success-details)

           ;; Log successful completion
           (if logger
             (logging/log-function-exit logger command-name result final-context)
             (log/info "CLI command completed successfully" final-context))

           result)

         (catch Exception ex
           (let [duration (- (System/currentTimeMillis) start-time)
                 error-context (assoc context
                                      :duration-ms duration
                                      :outcome :error
                                      :error (.getMessage ex)
                                      :event :cli-command-error)
                 error-details (merge command-details
                                      {:duration-ms duration
                                       :outcome "error"
                                       :error (.getMessage ex)
                                       :exception-type (.getSimpleName (class ex))})]

             ;; Add breadcrumb for command error
             (add-cli-breadcrumb context "command" :error error-details)

             ;; Log error
             (if logger
               (logging/log-exception logger :error "CLI command failed" ex error-context)
               (log/error "CLI command failed" error-context))

             ;; Re-throw to maintain CLI error handling behavior
             (throw ex))))))))

(defn with-cli-error-reporting
  "Higher-order function that wraps CLI command execution with error reporting.
   
   Captures unexpected exceptions and reports them to the error reporting system
   while preserving CLI exit code behavior. Provides comprehensive error
   context including command details and execution environment.
   
   Args:
     f: Function to execute with error reporting
   
   Returns:
     Function that reports errors around execution"
  [f]
  (fn [service cli-opts context]
    (try
      (f service cli-opts context)
      (catch Exception ex
        (let [{:keys [command subcommand correlation-id tenant-id user-id]} context
              error-details {:command command
                             :subcommand subcommand
                             :correlation-id correlation-id
                             :tenant-id tenant-id
                             :user-id user-id
                             :cli-opts cli-opts
                             :error (.getMessage ex)
                             :exception-type (.getSimpleName (class ex))}]

          ;; Add breadcrumb for uncaught exception
          (add-cli-breadcrumb context "exception" :error error-details)

          ;; Report error to error reporting system with full context
          (error-reporting/report-application-error
           context
           ex
           (str "CLI command failed: " command
                (when subcommand (str " " subcommand)))
           error-details)

          ;; Re-throw to maintain CLI behavior (exit codes, etc.)
          (throw ex))))))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn wrap-cli-command
  "Convenience function that applies all CLI middleware to a command function.
   
   Applies logging, context generation, and error reporting middleware.
   
   Args:
     command: Command name string
     subcommand: Subcommand name string (optional)
     config: Application config map (optional)
     logger: ILogger instance (optional)
     f: Command function to wrap
   
   Returns:
     Fully wrapped command function"
  ([command f]
   (wrap-cli-command command nil nil nil f))
  ([command subcommand f]
   (wrap-cli-command command subcommand nil nil f))
  ([command subcommand config logger f]
   (-> f
       (with-cli-error-reporting)
       (with-cli-logging logger)
       (with-cli-context command subcommand config))))