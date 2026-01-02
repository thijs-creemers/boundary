(ns boundary.platform.migrations.shell.cli
  "CLI commands for database migrations.
   
   This is the SHELL layer in Functional Core / Imperative Shell architecture.
   Responsibilities:
   - Parse command-line arguments using tools.cli
   - Orchestrate migration operations (repository, executor, discovery, locking)
   - Format output (table or JSON)
   - Handle errors and exit codes
   
   All business logic lives in boundary.platform.migrations.core.*
   Commands:
   - status: Show migration status
   - up: Apply pending migrations
   - down: Rollback migrations
   - to VERSION: Migrate to specific version
   - redo: Rollback and reapply last migration
   - verify: Verify migration integrity"
  (:require [boundary.platform.migrations.core.checksums :as checksums]
            [boundary.platform.migrations.core.planning :as planning]
            [boundary.platform.migrations.ports :as ports]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Global CLI Options
;; =============================================================================

(def global-options
  [["-f" "--format FORMAT" "Output format: table (default) or json"
    :default "table"
    :validate [#(contains? #{"table" "json"} %) "Must be 'table' or 'json'"]]
   ["-h" "--help" "Show help"]])

;; =============================================================================
;; Command Options
;; =============================================================================

(def status-options
  [[nil "--module MODULE" "Filter by module name"]])

(def up-options
  [[nil "--steps N" "Number of migrations to apply (default: all)"
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a positive integer"]]
   [nil "--module MODULE" "Only apply migrations for specific module"]
   [nil "--timeout MS" "Lock acquisition timeout in milliseconds (default: 30000)"
    :default 30000
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a positive integer"]]
   [nil "--dry-run" "Show what would be applied without executing"
    :default false]])

(def down-options
  [[nil "--steps N" "Number of migrations to rollback (default: 1)"
    :default 1
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a positive integer"]]
   [nil "--timeout MS" "Lock acquisition timeout in milliseconds (default: 30000)"
    :default 30000
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a positive integer"]]
   [nil "--dry-run" "Show what would be rolled back without executing"
    :default false]])

(def to-options
  [[nil "--version VERSION" "Target version (YYYYMMDDhhmmss) (required)"
    :validate [planning/valid-version? "Must be 14-digit timestamp format"]]
   [nil "--timeout MS" "Lock acquisition timeout in milliseconds (default: 30000)"
    :default 30000
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a positive integer"]]
   [nil "--dry-run" "Show what would be applied without executing"
    :default false]])

(def redo-options
  [[nil "--timeout MS" "Lock acquisition timeout in milliseconds (default: 30000)"
    :default 30000
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a positive integer"]]
   [nil "--dry-run" "Show what would be done without executing"
    :default false]])

(def verify-options
  [[nil "--module MODULE" "Verify specific module only"]
   [nil "--fix" "Attempt to fix checksum mismatches"
    :default false]])

;; =============================================================================
;; Output Formatting
;; =============================================================================

(defn- format-timestamp
  "Format Instant as human-readable string."
  [instant]
  (if instant
    (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
             (.atZone instant (java.time.ZoneId/systemDefault)))
    "N/A"))

(defn- format-table
  "Format data as ASCII table."
  [headers rows]
  (let [widths (map (fn [i]
                     (apply max (count (nth headers i))
                            (map #(count (str (nth % i))) rows)))
                   (range (count headers)))
        format-row (fn [row]
                    (str/join " | "
                             (map-indexed (fn [i val]
                                           (let [width (nth widths i)]
                                             (format (str "%-" width "s") (str val))))
                                         row)))
        separator (str/join "-+-" (map #(apply str (repeat % "-")) widths))]
    (str/join "\n"
             (concat [(format-row headers)
                     separator]
                    (map format-row rows)))))

(defn- format-status-table
  "Format migration status as table."
  [status-data]
  (let [headers ["Version" "Name" "Module" "Status" "Applied At"]
        rows (map (fn [mig]
                   [(:version mig)
                    (:name mig)
                    (:module mig)
                    (name (:status mig))
                    (format-timestamp (:applied-at mig))])
                 status-data)]
    (format-table headers rows)))

(defn- format-verification-table
  "Format verification results as table."
  [verification-results]
  (let [headers ["Version" "Name" "Status" "Issue"]
        rows (map (fn [result]
                   [(:version result)
                    (:name result)
                    (if (:valid? result) "OK" "INVALID")
                    (or (:error result) "")])
                 verification-results)]
    (format-table headers rows)))

;; =============================================================================
;; Command: status
;; =============================================================================

(defn execute-status
  "Show migration status.
   
   Args:
     repository - IMigrationRepository implementation
     discovery - IMigrationDiscovery implementation
     opts - Command options map
     
   Returns:
     Map with :exit-code and :output"
  [repository discovery opts]
  (try
    (let [applied-migrations (ports/find-all-applied repository)
          discovered-files (ports/discover-migrations discovery "migrations" {})
          module-filter (:module opts)
          ;; Filter by module if specified
          applied (if module-filter
                   (filter #(= module-filter (:module %)) applied-migrations)
                   applied-migrations)
          discovered (if module-filter
                      (filter #(= module-filter (:module %)) discovered-files)
                      discovered-files)
          ;; Merge applied and discovered
          all-versions (distinct (concat (map :version applied)
                                        (map :version discovered)))
          status-data (map (fn [version]
                            (let [applied-mig (first (filter #(= version (:version %)) applied))
                                  discovered-mig (first (filter #(= version (:version %)) discovered))]
                              (merge
                                {:version version
                                 :name (or (:name applied-mig) (:name discovered-mig))
                                 :module (or (:module applied-mig) (:module discovered-mig))
                                 :status (if applied-mig :applied :pending)
                                 :applied-at (:applied-at applied-mig)})))
                          (sort all-versions))
          output (if (= "json" (:format opts))
                  (json/generate-string status-data {:pretty true})
                  (format-status-table status-data))]
      {:exit-code 0
       :output output})
    (catch Exception e
      {:exit-code 1
       :output (str "Error: " (.getMessage e))})))

;; =============================================================================
;; Command: up
;; =============================================================================

(defn execute-up
  "Apply pending migrations.
   
   Args:
     repository - IMigrationRepository implementation
     executor - IMigrationExecutor implementation
     discovery - IMigrationDiscovery implementation
     lock - IMigrationLock implementation
     opts - Command options map
     
   Returns:
     Map with :exit-code and :output"
  [repository executor discovery lock opts]
  (let [timeout (:timeout opts 30000)
        holder-id "migration-cli-up"
        dry-run? (:dry-run opts)]
    (try
      ;; Acquire lock
      (if-not (ports/acquire-lock lock holder-id timeout)
        {:exit-code 1
         :output "Failed to acquire migration lock (another process may be running)"}
        
        (try
          ;; Get pending migrations
          (let [applied (ports/find-all-applied repository)
                discovered (ports/discover-migrations discovery "migrations" {})
                pending (planning/pending-migrations applied discovered)
                module-filter (:module opts)
                steps (:steps opts)
                ;; Filter by module if specified
                filtered-pending (if module-filter
                                  (filter #(= module-filter (:module %)) pending)
                                  pending)
                ;; Limit by steps if specified
                to-apply (if steps
                          (take steps filtered-pending)
                          filtered-pending)]
            
            (if (empty? to-apply)
              {:exit-code 0
               :output "No pending migrations to apply."}
              
              (if dry-run?
                ;; Dry run - just show what would be done
                {:exit-code 0
                 :output (str "Would apply " (count to-apply) " migration(s):\n"
                            (str/join "\n" (map #(str "  - " (:version %) " " (:name %)) to-apply)))}
                
                ;; Execute migrations
                (let [results (map (fn [mig]
                                    (let [result (ports/execute-sql executor
                                                                   (:up-sql mig)
                                                                   {:migration mig
                                                                    :direction :up})]
                                      (when (:success? result)
                                        (ports/record-migration repository result))
                                      result))
                                  to-apply)
                      successful (filter :success? results)
                      failed (filter (complement :success?) results)]
                  {:exit-code (if (empty? failed) 0 1)
                   :output (str "Applied " (count successful) " of " (count to-apply) " migration(s)."
                              (when-not (empty? failed)
                                (str "\nFailed:\n"
                                    (str/join "\n" (map #(str "  - " (:version %) ": " (:error %))
                                                       failed)))))}))))
          (finally
            (ports/release-lock lock holder-id))))
      (catch Exception e
        {:exit-code 1
         :output (str "Error: " (.getMessage e))}))))

;; =============================================================================
;; Command: down
;; =============================================================================

(defn execute-down
  "Rollback migrations.
   
   Args:
     repository - IMigrationRepository implementation
     executor - IMigrationExecutor implementation
     discovery - IMigrationDiscovery implementation
     lock - IMigrationLock implementation
     opts - Command options map
     
   Returns:
     Map with :exit-code and :output"
  [repository executor discovery lock opts]
  (let [timeout (:timeout opts 30000)
        holder-id "migration-cli-down"
        steps (:steps opts 1)
        dry-run? (:dry-run opts)]
    (try
      ;; Acquire lock
      (if-not (ports/acquire-lock lock holder-id timeout)
        {:exit-code 1
         :output "Failed to acquire migration lock (another process may be running)"}
        
        (try
          ;; Get migrations to rollback
          (let [applied (ports/find-all-applied repository)
                discovered (ports/discover-migrations discovery "migrations" {})
                to-rollback (take steps (reverse (planning/sort-migrations applied)))]
            
            (if (empty? to-rollback)
              {:exit-code 0
               :output "No migrations to rollback."}
              
              (if dry-run?
                ;; Dry run - just show what would be done
                {:exit-code 0
                 :output (str "Would rollback " (count to-rollback) " migration(s):\n"
                            (str/join "\n" (map #(str "  - " (:version %) " " (:name %)) to-rollback)))}
                
                ;; Execute rollbacks
                (let [results (map (fn [mig]
                                    ;; Find down migration file
                                    (let [down-file (first (filter #(and (= (:version %) (:version mig))
                                                                         (= :down (:direction %)))
                                                                  discovered))]
                                      (if-not down-file
                                        {:success? false
                                         :version (:version mig)
                                         :error "No down migration file found"}
                                        
                                        (let [result (ports/execute-sql executor
                                                                       (:down-sql down-file)
                                                                       {:migration down-file
                                                                        :direction :down})]
                                          (when (:success? result)
                                            (ports/delete-migration repository (:version mig)))
                                          result))))
                                  to-rollback)
                      successful (filter :success? results)
                      failed (filter (complement :success?) results)]
                  {:exit-code (if (empty? failed) 0 1)
                   :output (str "Rolled back " (count successful) " of " (count to-rollback) " migration(s)."
                              (when-not (empty? failed)
                                (str "\nFailed:\n"
                                    (str/join "\n" (map #(str "  - " (:version %) ": " (:error %))
                                                       failed)))))}))))
          (finally
            (ports/release-lock lock holder-id))))
      (catch Exception e
        {:exit-code 1
         :output (str "Error: " (.getMessage e))}))))

;; =============================================================================
;; Command: to
;; =============================================================================

(defn execute-to
  "Migrate to specific version.
   
   Args:
     repository - IMigrationRepository implementation
     executor - IMigrationExecutor implementation
     discovery - IMigrationDiscovery implementation
     lock - IMigrationLock implementation
     opts - Command options map
     
   Returns:
     Map with :exit-code and :output"
  [repository _executor discovery lock opts]
  (let [target-version (:version opts)
        timeout (:timeout opts 30000)
        holder-id "migration-cli-to"
        dry-run? (:dry-run opts)]
    
    (if-not target-version
      {:exit-code 1
       :output "Error: --version is required"}
      
      (try
        ;; Acquire lock
        (if-not (ports/acquire-lock lock holder-id timeout)
          {:exit-code 1
           :output "Failed to acquire migration lock (another process may be running)"}
          
          (try
            (let [applied (ports/find-all-applied repository)
                  discovered (ports/discover-migrations discovery "migrations" {})
                  plan (planning/migrations-to-version applied discovered target-version)]
              
              (if (empty? (:migrations plan))
                {:exit-code 0
                 :output "Already at target version."}
                
                (if dry-run?
                  {:exit-code 0
                   :output (str "Would " (name (:direction plan)) " "
                              (count (:migrations plan)) " migration(s) to reach version "
                              target-version ":\n"
                              (str/join "\n" (map #(str "  - " (:version %) " " (:name %))
                                                 (:migrations plan))))}
                  
                  {:exit-code 0
                   :output (str "Migration to version " target-version " not yet implemented.")})))
            (finally
              (ports/release-lock lock holder-id))))
        (catch Exception e
          {:exit-code 1
           :output (str "Error: " (.getMessage e))})))))

;; =============================================================================
;; Command: redo
;; =============================================================================

(defn execute-redo
  "Rollback and reapply last migration.
   
   Args:
     repository - IMigrationRepository implementation
     executor - IMigrationExecutor implementation
     discovery - IMigrationDiscovery implementation
     lock - IMigrationLock implementation
     opts - Command options map
     
   Returns:
     Map with :exit-code and :output"
  [repository executor discovery lock opts]
  (let [timeout (:timeout opts 30000)
        holder-id "migration-cli-redo"
        dry-run? (:dry-run opts)]
    (try
      ;; Acquire lock
      (if-not (ports/acquire-lock lock holder-id timeout)
        {:exit-code 1
         :output "Failed to acquire migration lock (another process may be running)"}
        
        (try
          (let [applied (ports/find-all-applied repository)]
            (if (empty? applied)
              {:exit-code 0
               :output "No migrations to redo."}
              
              (let [last-migration (last (planning/sort-migrations applied))]
                (if dry-run?
                  {:exit-code 0
                   :output (str "Would redo migration: " (:version last-migration) " " (:name last-migration))}
                  
                  ;; First rollback
                  (let [down-result (execute-down repository executor discovery lock
                                                 (assoc opts :steps 1 :dry-run false))]
                    (if (not= 0 (:exit-code down-result))
                      down-result
                      
                      ;; Then reapply
                      (let [up-result (execute-up repository executor discovery lock
                                                 (assoc opts :steps 1 :dry-run false))]
                        (if (= 0 (:exit-code up-result))
                          {:exit-code 0
                           :output (str "Successfully redid migration: " (:version last-migration))}
                          up-result))))))))
          (finally
            (ports/release-lock lock holder-id))))
      (catch Exception e
        {:exit-code 1
         :output (str "Error: " (.getMessage e))}))))

;; =============================================================================
;; Command: verify
;; =============================================================================

(defn execute-verify
  "Verify migration integrity.
   
   Args:
     repository - IMigrationRepository implementation
     discovery - IMigrationDiscovery implementation
     opts - Command options map
     
   Returns:
     Map with :exit-code and :output"
  [repository discovery opts]
  (try
    (let [applied (ports/find-all-applied repository)
          discovered (ports/discover-migrations discovery "migrations" {})
          module-filter (:module opts)
          ;; Filter by module if specified
          filtered-applied (if module-filter
                            (filter #(= module-filter (:module %)) applied)
                            applied)
          ;; Verify checksums
          verification-results (map (fn [mig]
                                     (let [discovered-mig (first (filter #(= (:version %) (:version mig))
                                                                        discovered))
                                           expected-checksum (when discovered-mig
                                                              (checksums/calculate-checksum (:up-sql discovered-mig)))
                                           valid? (ports/verify-checksum repository
                                                                        (:version mig)
                                                                        expected-checksum)]
                                       {:version (:version mig)
                                        :name (:name mig)
                                        :valid? valid?
                                        :error (when-not valid? "Checksum mismatch")}))
                                   filtered-applied)
          invalid (filter (complement :valid?) verification-results)
          output (if (= "json" (:format opts))
                  (json/generate-string verification-results {:pretty true})
                  (str (format-verification-table verification-results)
                      "\n\n"
                      (if (empty? invalid)
                        "All migrations verified successfully."
                        (str (count invalid) " migration(s) failed verification."))))]
      {:exit-code (if (empty? invalid) 0 1)
       :output output})
    (catch Exception e
      {:exit-code 1
       :output (str "Error: " (.getMessage e))})))

;; =============================================================================
;; Help Text
;; =============================================================================

(def help-text
  "Database Migration Commands

Usage: migrate [options] <command> [command-options]

Commands:
  status   Show migration status
  up       Apply pending migrations
  down     Rollback migrations
  to       Migrate to specific version
  redo     Rollback and reapply last migration
  verify   Verify migration integrity

Global Options:
  -f, --format FORMAT   Output format: table (default) or json
  -h, --help            Show help

Examples:
  migrate status
  migrate up --steps 1
  migrate down --steps 2
  migrate to --version 20240101120000
  migrate redo
  migrate verify --module user")

(defn print-help
  "Print help text."
  []
  (println help-text))
