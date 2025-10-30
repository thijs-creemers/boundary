(ns boundary.shell.adapters.database.utils.schema
  "Schema-to-DDL generation utilities for database adapters.
   
   This namespace provides utilities for generating database DDL statements
   from Malli schema definitions. It handles:
   - Column type mapping based on database dialect
   - Table creation with constraints
   - Index generation from schema analysis
   - Cross-database compatibility
   
   Key Features:
   - Uses Malli schemas as canonical source of truth
   - Database-specific column type mapping
   - Foreign key and unique constraint generation
   - Automatic index creation based on field patterns
   
   Usage:
     (require '[boundary.shell.adapters.database.utils.schema :as schema])

     (schema/generate-table-ddl ctx \"users\" some-malli-schema)
     (schema/initialize-tables-from-schemas! ctx schema-map)"
  (:require [boundary.shell.adapters.database.protocols :as protocols]
            [boundary.shell.adapters.database.common.core :as db-core]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Column Name Conversion
;; =============================================================================

(defn- col-name
  "Convert kebab-case field name to snake_case column name."
  [s]
  (str/replace s "-" "_"))

;; =============================================================================
;; Column Type Mapping
;; =============================================================================

(defn- malli-type->column-type
  "Map Malli schema types to database column types based on dialect.
   
   Args:
     ctx: Database context with adapter
     malli-type: Malli type keyword (e.g., :uuid, :string, :boolean)
     
   Returns:
     String column type definition"
  [ctx malli-type]
  (let [adapter (:adapter ctx)
        dialect (protocols/dialect adapter)]
    (case malli-type
      :uuid (case dialect
              (:sqlite :mysql) "CHAR(36)"
              :postgresql "UUID"
              :h2 "UUID"
              "VARCHAR(36)") ; fallback

      :string "VARCHAR(255)" ; Default string length

      :int (case dialect
             :sqlite "INTEGER"
             :mysql "INT"
             (:postgresql :h2) "INTEGER"
             "INTEGER") ; fallback

      :boolean (case dialect
                 :sqlite "INTEGER"
                 :mysql "TINYINT(1)"
                 (:postgresql :h2) "BOOLEAN"
                 "BOOLEAN") ; fallback

      ; Default for timestamps and unknown types
      (case dialect
        :sqlite "TEXT"
        :mysql "DATETIME"
        (:postgresql :h2) "TIMESTAMP"
        "TEXT")))) ; fallback

;; =============================================================================
;; Malli Schema Field Processing
;; =============================================================================

(defn- extract-field-info
  "Extract field information from Malli schema field definition.
   
   Args:
     field-def: Malli field definition - [field-name schema] or [field-name properties schema]
     
   Returns:
     Map with :name, :type, :optional? or nil for non-field entries"
  [field-def]
  (when (and (vector? field-def)
             (>= (count field-def) 2)
             (keyword? (first field-def)))
    (let [field-name (name (first field-def))
          ; Handle both [name type] and [name properties type] formats
          has-properties? (and (= 3 (count field-def)) (map? (second field-def)))
          field-type (if has-properties?
                       (nth field-def 2)  ; [name props type]
                       (second field-def)) ; [name type]
          properties (if has-properties? (second field-def) {})
          ; Extract type from vector schemas like [:enum :admin :user]
          actual-type (if (vector? field-type) (first field-type) field-type)
          optional? (boolean (:optional properties))]
      {:name field-name
       :type actual-type
       :optional? optional?
       :properties properties
       :schema field-type})))

;; =============================================================================
;; DDL Generation
;; =============================================================================

(defn- generate-column-definition
  "Generate SQL column definition from field info and context.
   
   Args:
     ctx: Database context
     field-info: Field info map from extract-field-info
     
   Returns:
     String column definition"
  [ctx {:keys [name type optional? schema]}]
  (let [column-name (col-name name)  ; Convert kebab-case to snake_case
        column-type (malli-type->column-type ctx type)
        not-null (if optional? "" " NOT NULL")
        primary-key (if (= name "id") " PRIMARY KEY" "")
        ; Handle enum constraints
        enum-constraint (if (and (vector? schema) (= :enum (first schema)))
                          (let [; Skip properties map if present: [:enum {...} :val1 :val2] or [:enum :val1 :val2]
                                schema-rest (rest schema)
                                enum-raw-values (if (and (seq schema-rest) (map? (first schema-rest)))
                                                  (rest schema-rest)  ; Skip properties map
                                                  schema-rest)        ; No properties, use as-is
                                ; Filter out non-value items (maps, vectors, functions)
                                enum-values (->> enum-raw-values
                                                 (filter #(or (keyword? %) (string? %) (number? %)))
                                                 (map (fn [v]
                                                        (cond
                                                          (keyword? v) (clojure.core/name v)  ; :admin -> "admin"
                                                          (string? v) v                       ; already a string
                                                          :else (str v))))                    ; fallback to str
                                                 (into []))
                                values-str (str/join ", " (map #(str "'" % "'") enum-values))]
                            (str " CHECK(" column-name " IN (" values-str "))"))
                          "")
        ; Handle boolean defaults for active fields
        boolean-default (if (and (= type :boolean) (= name "active") (not optional?))
                          (case (protocols/dialect (:adapter ctx))
                            :sqlite " DEFAULT 1"
                            " DEFAULT true")
                          "")]
    (str column-name " " column-type not-null primary-key enum-constraint boolean-default)))

(defn- generate-table-constraints
  "Generate table-level constraints (unique, foreign keys) based on table name and fields.
   
   Args:
     table-name: String name of the table
     field-infos: Vector of field info maps
     
   Returns:
     Vector of constraint definition strings"
  [table-name field-infos]
  (let [; Find foreign key fields (fields ending with -id but not id itself)
        _fk-fields (filter #(and (not= (:name %) "id")
                                 (str/ends-with? (:name %) "-id"))
                           field-infos)]
    (vec (concat
          ; Foreign key constraints - DISABLED FOR NOW
          ; (uncomment when tenant table is implemented)
          ; (map (fn [{:keys [name]}]
          ;        (let [ref-table (str/replace name #"-id$" "s") ; user-id -> users
          ;              constraint-name (str "fk_" table-name "_" (col-name name))]
          ;          (str "CONSTRAINT " constraint-name
          ;               " FOREIGN KEY (" (col-name name)
          ;               ") REFERENCES " ref-table "(id) ON DELETE CASCADE")))
          ;      fk-fields)
          []

          ; Table-specific unique constraints
          (case table-name
            "users" ["CONSTRAINT uk_users_email_tenant UNIQUE(email, tenant_id)"]
            "user_sessions" ["CONSTRAINT uk_user_sessions_token UNIQUE(session_token)"]
            [])))))

(defn generate-table-ddl
  "Generate CREATE TABLE DDL from Malli schema definition.
   
   Args:
     ctx: Database context
     table-name: String name for the table
     malli-schema: Malli schema definition ([:map ...] form)
     
   Returns:
     String DDL statement
     
   Example:
     (generate-table-ddl ctx \"users\" User-schema)"
  [ctx table-name malli-schema]
  (let [; Extract fields from Malli schema (skip :map and properties)
        fields (rest malli-schema)
        field-infos (->> fields
                         (map extract-field-info)
                         (filter some?))

        ; Generate column definitions
        column-defs (map #(generate-column-definition ctx %) field-infos)

        ; Generate constraints
        constraints (generate-table-constraints table-name field-infos)

        ; Combine all definitions
        all-definitions (concat column-defs constraints)
        definitions-str (str/join ",\n  " all-definitions)]

    (str "CREATE TABLE IF NOT EXISTS " table-name " (\n  "
         definitions-str
         "\n);")))

(defn- generate-table-indexes
  "Generate CREATE INDEX statements based on table name and field analysis.
   
   Args:
     table-name: String name of the table
     field-infos: Vector of field info maps
     
   Returns:
     Vector of DDL index statements"
  [table-name field-infos]
  (let [; Categorize fields for automatic index generation
        id-fields (filter #(str/ends-with? (:name %) "-id") field-infos)
        enum-fields (filter #(and (vector? (:schema %))
                                  (= :enum (first (:schema %)))) field-infos)
        timestamp-fields (filter #(str/ends-with? (:name %) "-at") field-infos)]

    (vec (concat
          ; Indexes on foreign key fields
          (map (fn [{:keys [name]}]
                 (str "CREATE INDEX IF NOT EXISTS idx_" table-name "_"
                      (col-name name) " ON " table-name " ("
                      (col-name name) ")"))
               id-fields)

          ; Indexes on enum fields (like role, active)
          (map (fn [{:keys [name]}]
                 (str "CREATE INDEX IF NOT EXISTS idx_" table-name "_"
                      (col-name name) " ON " table-name " ("
                      (col-name name) ")"))
               enum-fields)

          ; Indexes on timestamp fields
          (map (fn [{:keys [name]}]
                 (str "CREATE INDEX IF NOT EXISTS idx_" table-name "_"
                      (col-name name) " ON " table-name " ("
                      (col-name name) ")"))
               timestamp-fields)

          ; Table-specific compound indexes
          (case table-name
            "users"
            ["CREATE INDEX IF NOT EXISTS idx_users_email_tenant ON users (email, tenant_id)"
             "CREATE INDEX IF NOT EXISTS idx_users_role_tenant ON users (role, tenant_id)"
             "CREATE INDEX IF NOT EXISTS idx_users_active_tenant ON users (active, tenant_id)"]

            "user_sessions"
            ["CREATE INDEX IF NOT EXISTS idx_sessions_token ON user_sessions (session_token)"
             "CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON user_sessions (expires_at)"]

            [])))))

(defn generate-indexes-ddl
  "Generate CREATE INDEX statements from Malli schema analysis.
   
   Args:
     ctx: Database context
     table-name: String name of the table
     malli-schema: Malli schema definition
     
   Returns:
     Vector of DDL index statements"
  [_ctx table-name malli-schema]
  (let [fields (rest malli-schema)
        field-infos (->> fields
                         (map extract-field-info)
                         (filter some?))]
    (generate-table-indexes table-name field-infos)))

;; =============================================================================
;; Schema Initialization
;; =============================================================================

(defn initialize-tables-from-schemas!
  "Initialize database tables and indexes from a map of Malli schema definitions.
   
   Args:
     ctx: Database context
     schema-definitions: Map of table-name -> malli-schema
     
   Returns:
     nil
     
   Throws:
     Exception if schema initialization fails
     
   Example:
     (initialize-tables-from-schemas! ctx 
       {\"users\" User-schema
        \"user_sessions\" UserSession-schema})"
  [ctx schema-definitions]
  (log/info "Initializing database schema from Malli definitions"
            {:dialect (protocols/dialect (:adapter ctx))
             :tables (keys schema-definitions)})

  (try
    ; Create tables
    (doseq [[table-name malli-schema] schema-definitions]
      (let [ddl (generate-table-ddl ctx table-name malli-schema)]
        (log/debug "Creating table" {:table table-name :ddl ddl})
        (db-core/execute-ddl! ctx ddl)))

    ; Create indexes
    (doseq [[table-name malli-schema] schema-definitions]
      (let [index-ddls (generate-indexes-ddl ctx table-name malli-schema)]
        (doseq [index-ddl index-ddls]
          (log/debug "Creating index" {:table table-name :ddl index-ddl})
          (db-core/execute-ddl! ctx index-ddl))))

    (log/info "Database schema initialization completed successfully"
              {:dialect (protocols/dialect (:adapter ctx))
               :tables (keys schema-definitions)})

    (catch Exception e
      (log/error "Database schema initialization failed"
                 {:error (.getMessage e)
                  :dialect (protocols/dialect (:adapter ctx))})
      (throw e))))

