(ns boundary.platform.migrations.schema
  "Malli schemas for database migrations."
  (:require [malli.core :as m]))

;; =============================================================================
;; Migration Metadata
;; =============================================================================

(def Version
  "Migration version in YYYYMMDDhhmmss format."
  [:string {:min 14 :max 14
            :pattern #"^\d{14}$"
            :description "Timestamp-based version: YYYYMMDDhhmmss"}])

(def ModuleName
  "Module name (user, inventory, billing, etc)."
  [:string {:min 1 :max 100
            :pattern #"^[a-z][a-z0-9_]*$"
            :description "Module name in lowercase with underscores"}])

(def MigrationName
  "Descriptive migration name."
  [:string {:min 1 :max 255
            :pattern #"^[a-z0-9_]+$"
            :description "Migration description in lowercase with underscores"}])

(def MigrationStatus
  "Status of a migration."
  [:enum :pending :applied :failed :rolled-back])

(def DatabaseType
  "Supported database types."
  [:enum :sqlite :h2 :postgresql :mysql])

;; =============================================================================
;; Migration File
;; =============================================================================

(def MigrationFile
  "Metadata about a migration file.
   
   Note: The :content field contains the SQL for this specific migration file.
   For up migrations, this is the forward SQL. For down migrations, this is
   the rollback SQL. The :direction field indicates which type this is.
   
   The :down? field from discovery is used to determine if a paired .down.sql
   file exists, which then gets transformed into :reversible."
  [:map {:closed true}
   [:version Version]
   [:module ModuleName]
   [:name MigrationName]
   [:file-path :string]
   [:content :string]                       ; SQL content of this migration file
   [:checksum [:string {:min 64 :max 64}]]  ; SHA-256 of content
   [:down? :boolean]                        ; True if this is a .down.sql file
   ;; Optional fields added during processing:
   [:direction {:optional true} [:enum :up :down]]  ; Direction for execution
   [:reversible {:optional true} :boolean]          ; Has paired down migration
   [:has-down? {:optional true} :boolean]])         ; Alias for reversible

;; =============================================================================
;; Schema Migrations Table Record
;; =============================================================================

(def SchemaMigration
  "Record in schema_migrations table."
  [:map {:closed true}
   [:version Version]
   [:name MigrationName]
   [:module ModuleName]
   [:applied-at inst?]
   [:checksum [:string {:min 64 :max 64}]]
   [:execution-time-ms [:int {:min 0}]]
   [:status MigrationStatus]
   [:db-type DatabaseType]
   [:error-message [:maybe :string]]])

;; =============================================================================
;; Migration Plan
;; =============================================================================

(def MigrationAction
  "Action to perform on a migration."
  [:enum :apply :rollback :skip])

(def PlannedMigration
  "A migration with its planned action."
  [:map {:closed true}
   [:migration MigrationFile]
   [:action MigrationAction]
   [:reason [:maybe :string]]])

(def MigrationPlan
  "Complete plan for migration execution."
  [:map {:closed true}
   [:migrations [:vector PlannedMigration]]
   [:total-count [:int {:min 0}]]
   [:apply-count [:int {:min 0}]]
   [:rollback-count [:int {:min 0}]]
   [:skip-count [:int {:min 0}]]])

;; =============================================================================
;; Migration Result
;; =============================================================================

(def MigrationResult
  "Result of executing a single migration."
  [:map {:closed true}
   [:version Version]
   [:module ModuleName]
   [:name MigrationName]
   [:action MigrationAction]
   [:status [:enum :success :failure]]
   [:execution-time-ms [:int {:min 0}]]
   [:error [:maybe :string]]])

(def MigrationExecutionResult
  "Result of executing a migration plan."
  [:map {:closed true}
   [:results [:vector MigrationResult]]
   [:total-count [:int {:min 0}]]
   [:success-count [:int {:min 0}]]
   [:failure-count [:int {:min 0}]]
   [:total-execution-time-ms [:int {:min 0}]]])

;; =============================================================================
;; CLI Options
;; =============================================================================

(def MigrationCommand
  "Available migration commands."
  [:enum :status :up :down :to :redo :verify])

(def MigrationOptions
  "CLI options for migration commands."
  [:map
   [:command MigrationCommand]
   [:module [:maybe ModuleName]]
   [:version [:maybe Version]]
   [:count [:maybe [:int {:min 1}]]]
   [:dry-run {:optional true} :boolean]
   [:verbose {:optional true} :boolean]])

;; =============================================================================
;; Validators
;; =============================================================================

(defn valid-version?
  "Check if version string is valid timestamp format."
  [version]
  (m/validate Version version))

(defn valid-module-name?
  "Check if module name is valid."
  [module-name]
  (m/validate ModuleName module-name))

(defn valid-migration-file?
  "Check if migration file metadata is valid."
  [migration]
  (m/validate MigrationFile migration))

(defn explain-migration-file
  "Explain validation errors for migration file."
  [migration]
  (m/explain MigrationFile migration))
