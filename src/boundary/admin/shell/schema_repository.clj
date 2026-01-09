(ns boundary.admin.shell.schema-repository
  "Schema repository implementation for database metadata retrieval.

   This namespace provides the concrete implementation of ISchemaProvider,
   connecting to the database through existing adapter protocols to fetch
   table and column metadata.

   Responsibilities:
   - Fetch raw database metadata using adapter protocols
   - Coordinate with pure core functions for parsing and merging
   - Cache entity configurations for performance
   - Provide entity discovery based on configuration

   Week 1: Direct database queries, no caching
   Week 2+: Add TTL-based caching for schema metadata"
  (:require
   [boundary.admin.ports :as ports]
   [boundary.admin.schema :as admin-schema]
   [boundary.admin.core.schema-introspection :as introspection]
   [boundary.platform.shell.adapters.database.protocols :as db-protocols]))

;; =============================================================================
;; Schema Repository Implementation
;; =============================================================================

(defrecord SchemaRepository [db-ctx config]
  ports/ISchemaProvider

  (fetch-table-metadata [_ table-name]
    "Fetch raw table metadata from database using adapter protocol.

     Uses the existing db-protocols/get-table-info which works across
     all database adapters (PostgreSQL, SQLite, MySQL, H2)."
    (let [adapter (:adapter db-ctx)
          datasource (:datasource db-ctx)
          table-name-normalized (if (keyword? table-name)
                                  (name table-name)
                                  table-name)]
      (try
        (let [columns (db-protocols/get-table-info adapter datasource table-name-normalized)]
          (when (empty? columns)
            (throw (ex-info (str "Table not found: " table-name-normalized)
                            {:type :table-not-found
                             :table-name table-name})))
          columns)
        (catch Exception e
          (throw (ex-info (str "Failed to fetch table metadata: " (.getMessage e))
                          {:type :schema-fetch-error
                           :table-name table-name
                           :cause e}))))))

  (get-entity-config [this entity-name]
    "Get complete entity configuration by merging auto-detected with manual config.

     Process:
     1. Fetch table metadata from database
     2. Parse metadata into auto-detected configuration
     3. Get manual config overrides from admin config (if any)
     4. Merge auto-detected with manual overrides
     5. Return complete entity configuration"
    (let [table-metadata (ports/fetch-table-metadata this entity-name)
          auto-config (introspection/parse-table-metadata entity-name table-metadata)
          manual-config (get-in config [:entities entity-name])
          merged-config (introspection/build-entity-config auto-config manual-config)]
      ; Add relationship detection (Week 1 stub)
      (introspection/detect-relationships merged-config)))

  (list-available-entities [_]
    "List all entities available based on discovery configuration.

     Discovery modes:
     - :allowlist - Only return entities in :allowlist
     - :denylist - Return all tables except those in :denylist
     - :all - Return all accessible database tables (Week 2+)"
    (let [discovery-config (:entity-discovery config)
          mode (:mode discovery-config)]
      (case mode
        :allowlist
        (vec (:allowlist discovery-config []))

        :denylist
        ; Week 2+: Query all tables, filter by denylist
        (throw (ex-info "Denylist mode not yet implemented"
                        {:type :not-implemented
                         :mode :denylist
                         :available-modes [:allowlist]}))

        :all
        ; Week 2+: Query all tables from database
        (throw (ex-info "All mode not yet implemented"
                        {:type :not-implemented
                         :mode :all
                         :available-modes [:allowlist]}))

        ; Default fallback
        (throw (ex-info (str "Invalid entity discovery mode: " mode)
                        {:type :invalid-config
                         :mode mode
                         :valid-modes [:allowlist :denylist :all]})))))

  (get-entity-label [_ entity-name]
    "Get display label for entity.

     Tries manual config label first, falls back to humanized entity name."
    (or (get-in config [:entities entity-name :label])
        (introspection/humanize-entity-name entity-name)))

  (validate-entity-exists [this entity-name]
    "Check if entity is valid and accessible.

     Returns true if entity is in the list of available entities."
    (let [available (set (ports/list-available-entities this))]
      (contains? available entity-name))))

;; =============================================================================
;; Factory Function
;; =============================================================================

(defn create-schema-repository
  "Create new SchemaRepository instance.

   Args:
     db-ctx: Database context map with :adapter and :datasource
     config: Admin configuration map with :entity-discovery and :entities

   Returns:
     SchemaRepository instance implementing ISchemaProvider

   Example:
     (create-schema-repository db-ctx
       {:entity-discovery {:mode :allowlist
                           :allowlist #{:users}}
        :entities {:users {:label \"System Users\"}}})"
  [db-ctx config]
  (->SchemaRepository db-ctx config))

;; =============================================================================
;; Helper Functions for Testing and Development
;; =============================================================================

(defn list-all-database-tables
  "Development helper: List all tables in the database.

   Useful for debugging and understanding what tables are available.

   Week 2+: This will be used for :all and :denylist discovery modes.

   Args:
     db-ctx: Database context map

   Returns:
     Vector of table names as keywords

   Note: Implementation depends on database type - will need to query
         information_schema or similar."
  [db-ctx]
  ; Week 2+: Implement database-specific table listing
  ; PostgreSQL: SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
  ; SQLite: SELECT name FROM sqlite_master WHERE type='table'
  ; MySQL: SHOW TABLES
  (throw (ex-info "list-all-database-tables not yet implemented"
                  {:type :not-implemented})))

(defn validate-entity-config
  "Validate entity configuration against schema.

   Args:
     entity-config: Entity configuration map

   Returns:
     {:valid? true} or {:valid? false :errors ...}

   Example:
     (validate-entity-config {:label \"Users\" :table-name :users ...})"
  [entity-config]
  (admin-schema/validate-entity-config entity-config))

(defn get-entity-field-names
  "Get list of all field names for an entity.

   Args:
     schema-provider: ISchemaProvider implementation
     entity-name: Keyword entity name

   Returns:
     Vector of field name keywords

   Example:
     (get-entity-field-names provider :users)
     ;=> [:id :email :name :role :active :created-at ...]"
  [schema-provider entity-name]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (vec (keys (:fields entity-config)))))

(defn get-entity-primary-key
  "Get primary key field name for an entity.

   Args:
     schema-provider: ISchemaProvider implementation
     entity-name: Keyword entity name

   Returns:
     Keyword primary key field name (defaults to :id)

   Example:
     (get-entity-primary-key provider :users) ;=> :id"
  [schema-provider entity-name]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (:primary-key entity-config :id)))

(defn get-searchable-fields
  "Get list of searchable field names for an entity.

   Args:
     schema-provider: ISchemaProvider implementation
     entity-name: Keyword entity name

   Returns:
     Vector of searchable field name keywords

   Example:
     (get-searchable-fields provider :users)
     ;=> [:email :name]"
  [schema-provider entity-name]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (:search-fields entity-config [])))

(defn get-list-fields
  "Get list of fields to display in list view.

   Args:
     schema-provider: ISchemaProvider implementation
     entity-name: Keyword entity name

   Returns:
     Vector of field name keywords for list view

   Example:
     (get-list-fields provider :users)
     ;=> [:email :name :role :active :created-at]"
  [schema-provider entity-name]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (:list-fields entity-config [])))

(defn get-editable-fields
  "Get list of editable field names for an entity.

   Args:
     schema-provider: ISchemaProvider implementation
     entity-name: Keyword entity name

   Returns:
     Vector of editable field name keywords

   Example:
     (get-editable-fields provider :users)
     ;=> [:name :email :role :active]"
  [schema-provider entity-name]
  (let [entity-config (ports/get-entity-config schema-provider entity-name)]
    (:editable-fields entity-config [])))

;; =============================================================================
;; Entity Configuration Summary (for debugging)
;; =============================================================================

(defn summarize-entity-config
  "Create human-readable summary of entity configuration.

   Useful for debugging, logging, and understanding what was auto-detected
   vs manually configured.

   Args:
     entity-config: Entity configuration map

   Returns:
     Summary map with key statistics

   Example:
     (summarize-entity-config entity-config)
     ;=> {:entity-name :users
     ;    :label \"Users\"
     ;    :total-fields 10
     ;    :visible-fields 8
     ;    :editable-fields 5
     ;    :searchable-fields 2
     ;    :readonly-fields #{:id :created-at :updated-at}
     ;    :hidden-fields #{:password-hash :deleted-at}}"
  [entity-config]
  {:entity-name (:table-name entity-config)
   :label (:label entity-config)
   :primary-key (:primary-key entity-config :id)
   :total-fields (count (:fields entity-config))
   :list-fields-count (count (:list-fields entity-config))
   :detail-fields-count (count (:detail-fields entity-config))
   :editable-fields-count (count (:editable-fields entity-config))
   :searchable-fields-count (count (:search-fields entity-config))
   :readonly-fields (:readonly-fields entity-config #{})
   :hidden-fields (:hide-fields entity-config #{})
   :soft-delete (:soft-delete entity-config false)
   :default-sort [(:default-sort entity-config) (:default-sort-dir entity-config)]})
