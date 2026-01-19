(ns boundary.platform.shell.database.cli-migrations
  "CLI commands for database migration management.

   Usage:
     clojure -M -m boundary.platform.shell.database.cli-migrations [command] [options]

   Commands:
     migrate         - Run all pending migrations
     rollback        - Roll back the last migration
     status          - Show migration status
     create <name>   - Create a new migration file
     reset           - Reset database (rollback all and reapply)
     init            - Initialize migration system"
  (:require [boundary.platform.shell.database.migrations :as migrations]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:gen-class))

;; =============================================================================
;; CLI Specification
;; =============================================================================

(def cli-options
  "CLI options specification for migration commands."
  [["-h" "--help" "Show help"]
   ["-v" "--verbose" "Verbose output"]])

;; =============================================================================
;; Command Implementations
;; =============================================================================

(defn cmd-migrate
  "Runs all pending migrations."
  [opts]
  (try
    (println "\n🔄 Running database migrations...")
    (migrations/migrate)
    (println "✅ Migrations completed successfully\n")
    (migrations/print-status)
    0
    (catch Exception e
      (println "❌ Migration failed:" (.getMessage e))
      (when (:verbose opts)
        (.printStackTrace e))
      1)))

(defn cmd-rollback
  "Rolls back the last migration."
  [opts]
  (try
    (println "\n🔙 Rolling back last migration...")
    (migrations/rollback)
    (println "✅ Rollback completed successfully\n")
    (migrations/print-status)
    0
    (catch Exception e
      (println "❌ Rollback failed:" (.getMessage e))
      (when (:verbose opts)
        (.printStackTrace e))
      1)))

(defn cmd-status
  "Shows migration status."
  [opts]
  (try
    (migrations/print-status)
    0
    (catch Exception e
      (println "❌ Failed to get status:" (.getMessage e))
      (when (:verbose opts)
        (.printStackTrace e))
      1)))

(defn cmd-create
  "Creates a new migration file."
  [migration-name opts]
  (if (str/blank? migration-name)
    (do
      (println "❌ Error: Migration name required")
      (println "\nUsage: clojure -M -m boundary.platform.shell.database.cli-migrations create <name>")
      (println "\nExample: clojure -M -m boundary.platform.shell.database.cli-migrations create add-email-verification")
      1)
    (try
      (println (format "\n📝 Creating migration: %s..." migration-name))
      (let [result (migrations/create-migration migration-name)]
        (println "✅" (:message result))
        (println (format "\nMigration files created in: %s" (:directory result)))
        (println "\nNext steps:")
        (println "1. Edit the generated SQL files in migrations/")
        (println "2. Run: clojure -M -m boundary.platform.shell.database.cli-migrations migrate")
        0)
      (catch Exception e
        (println "❌ Migration creation failed:" (.getMessage e))
        (when (:verbose opts)
          (.printStackTrace e))
        1))))

(defn cmd-reset
  "Resets the database (WARNING: destructive operation)."
  [opts]
  (println "\n⚠️  WARNING: This will rollback ALL migrations and reapply them!")
  (println "This is a DESTRUCTIVE operation and will delete all data.")
  (print "\nAre you sure? Type 'yes' to continue: ")
  (flush)
  (let [confirmation (read-line)]
    (if (= "yes" confirmation)
      (try
        (println "\n🔄 Resetting database...")
        (migrations/reset)
        (println "✅ Database reset completed\n")
        (migrations/print-status)
        0
        (catch Exception e
          (println "❌ Reset failed:" (.getMessage e))
          (when (:verbose opts)
            (.printStackTrace e))
          1))
      (do
        (println "\n❌ Reset cancelled")
        0))))

(defn cmd-init
  "Initializes the migration system."
  [opts]
  (try
    (println "\n🔧 Initializing migration system...")
    (migrations/init)
    (println "✅ Migration system initialized successfully")
    (println "\nNext steps:")
    (println "1. Run 'status' to see migration state")
    (println "2. Run 'migrate' to apply pending migrations")
    0
    (catch Exception e
      (println "❌ Initialization failed:" (.getMessage e))
      (when (:verbose opts)
        (.printStackTrace e))
      1)))

;; =============================================================================
;; Help and Usage
;; =============================================================================

(defn print-help
  "Prints CLI help message."
  []
  (println "\nBoundary Database Migration CLI")
  (println "================================\n")
  (println "Usage:")
  (println "  clojure -M -m boundary.platform.shell.database.cli-migrations [command] [options]\n")
  (println "Commands:")
  (println "  migrate              Run all pending migrations")
  (println "  rollback             Roll back the last migration")
  (println "  status               Show current migration status")
  (println "  create <name>        Create a new migration file")
  (println "  init                 Initialize migration system (first time setup)")
  (println "  reset                Reset database (rollback all and reapply) [DESTRUCTIVE]\n")
  (println "Options:")
  (println "  -h, --help           Show this help message")
  (println "  -v, --verbose        Verbose output\n")
  (println "Examples:")
  (println "  # Check migration status")
  (println "  clojure -M -m boundary.platform.shell.database.cli-migrations status\n")
  (println "  # Run pending migrations")
  (println "  clojure -M -m boundary.platform.shell.database.cli-migrations migrate\n")
  (println "  # Create a new migration")
  (println "  clojure -M -m boundary.platform.shell.database.cli-migrations create add-user-email-verification\n")
  (println "  # Roll back last migration")
  (println "  clojure -M -m boundary.platform.shell.database.cli-migrations rollback\n"))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Main CLI entry point for migration commands."
  [& args]
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args cli-options :in-order true)
        command (first arguments)
        command-args (rest arguments)]

    (cond
      ;; Show help
      (:help options)
      (do
        (print-help)
        (System/exit 0))

      ;; No command provided
      (nil? command)
      (do
        (println "❌ Error: No command specified\n")
        (print-help)
        (System/exit 1))

      ;; Parse errors
      errors
      (do
        (println "❌ Errors:")
        (doseq [error errors]
          (println "  " error))
        (println)
        (print-help)
        (System/exit 1))

      ;; Execute command
      :else
      (let [status (case command
                     "migrate"  (cmd-migrate options)
                     "rollback" (cmd-rollback options)
                     "status"   (cmd-status options)
                     "create"   (cmd-create (first command-args) options)
                     "reset"    (cmd-reset options)
                     "init"     (cmd-init options)

                     ;; Unknown command
                     (do
                       (println (format "❌ Unknown command: %s\n" command))
                       (print-help)
                       1))]
        (System/exit status)))))
