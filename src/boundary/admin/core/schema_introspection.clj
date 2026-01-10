(ns boundary.admin.core.schema-introspection
  "Pure functions for database schema introspection and entity configuration.

   This namespace contains pure business logic for transforming raw database
   metadata into UI-friendly entity configurations. All functions are pure
   (no side effects) and testable without database connections.

   Key responsibilities:
   - Parse database column metadata into field configurations
   - Infer appropriate UI widgets from database types
   - Detect relationships and foreign keys
   - Merge auto-detected configuration with manual overrides
   - Generate sensible defaults for labels and field ordering"
  (:require
   [clojure.string :as str]
   [boundary.admin.schema :as schema]
   [boundary.shared.core.utils.case-conversion :as case-conversion]))

;; =============================================================================
;; Type Mapping - SQL Types to Logical Field Types
;; =============================================================================

(def sql-type->field-type
  "Mapping from SQL/database types to logical field types.

   Keys are lowercase type names as strings (normalized from database).
   Values are field type keywords used in entity configuration."
  {"uuid" :uuid
   "char(36)" :uuid           ; UUID as string in some databases
   "varchar" :string
   "character varying" :string
   "text" :text
   "longtext" :text
   "mediumtext" :text
   "integer" :int
   "int" :int
   "bigint" :int
   "smallint" :int
   "tinyint" :int
   "serial" :int
   "bigserial" :int
   "decimal" :decimal
   "numeric" :decimal
   "real" :decimal
   "double" :decimal
   "double precision" :decimal
   "float" :decimal
   "money" :decimal
   "boolean" :boolean
   "bool" :boolean
   "bit" :boolean
   "timestamp" :instant
   "timestamp without time zone" :instant
   "timestamp with time zone" :instant
   "timestamptz" :instant
   "datetime" :instant
   "date" :date
   "json" :json
   "jsonb" :json
   "blob" :binary
   "bytea" :binary
   "binary" :binary})

(defn normalize-sql-type
  "Normalize SQL type string to lowercase without size/precision.

   Args:
     sql-type: String SQL type (e.g., 'VARCHAR(255)', 'DECIMAL(10,2)')

   Returns:
     Normalized lowercase type string without size

   Examples:
     (normalize-sql-type \"VARCHAR(255)\")      ;=> \"varchar\"
     (normalize-sql-type \"DECIMAL(10,2)\")    ;=> \"decimal\"
     (normalize-sql-type \"INTEGER\")          ;=> \"integer\"
     (normalize-sql-type \"TIMESTAMP\")        ;=> \"timestamp\""
  [sql-type]
  (-> sql-type
      str/lower-case
      (str/replace #"\(.*\)" "")  ; Remove size/precision
      str/trim))

(defn infer-field-type
  "Infer logical field type from SQL/database type.

   Args:
     sql-type: String SQL type from database metadata
     field-name: (Optional) Keyword field name for heuristics

   Returns:
     Keyword field type (:uuid, :string, :int, etc.)
     Defaults to :string if type is not recognized

   Examples:
     (infer-field-type \"VARCHAR(255)\")      ;=> :string
     (infer-field-type \"INTEGER\")          ;=> :int
     (infer-field-type \"BOOLEAN\")          ;=> :boolean
     (infer-field-type \"TIMESTAMP\")        ;=> :instant
     (infer-field-type \"TEXT\" :created-at) ;=> :instant (heuristic)
     (infer-field-type \"TEXT\" :email)      ;=> :string (heuristic)
     (infer-field-type \"UNKNOWN_TYPE\")     ;=> :string"
  ([sql-type] (infer-field-type sql-type nil))
  ([sql-type field-name]
   (let [normalized (normalize-sql-type sql-type)
         base-type (get sql-type->field-type normalized :string)]
     ;; Apply heuristics if field is text type
     (if (and (= base-type :text) field-name)
       (let [name-str (name field-name)]
         (cond
           ;; Timestamp fields stored as text (ISO 8601 strings)
           (or (str/ends-with? name-str "-at")
               (str/ends-with? name-str "-date")
               (str/ends-with? name-str "-time")
               (= name-str "created-at")
               (= name-str "updated-at")
               (= name-str "deleted-at"))
           :instant
           
           ;; Email fields
           (or (str/includes? name-str "email")
               (str/includes? name-str "mail"))
           :string
           
           ;; Role/status/enum-like fields (but keep as text for now, could be enum)
           (or (str/includes? name-str "role")
               (str/includes? name-str "status")
               (str/includes? name-str "type"))
           :string
           
           ;; Default: keep as text for very long content
           :else :text))
       base-type))))

;; =============================================================================
;; Widget Inference - Field Types to UI Widgets
;; =============================================================================

(defn infer-widget-for-field
  "Infer appropriate UI widget for a field based on its characteristics.

   Args:
     field-name: Keyword field name (used for heuristics)
     field-type: Keyword field type (:uuid, :string, :int, etc.)
     sql-type: Original SQL type string (for additional context)

   Returns:
     Keyword widget type (:text-input, :email-input, :checkbox, etc.)

   Examples:
     (infer-widget-for-field :email :string \"VARCHAR\")     ;=> :email-input
     (infer-widget-for-field :password :string \"VARCHAR\")  ;=> :password-input
     (infer-widget-for-field :active :boolean \"BOOLEAN\")   ;=> :checkbox
     (infer-widget-for-field :created-at :instant \"TIMESTAMP\") ;=> :datetime-input"
  [field-name field-type sql-type]
  (let [field-name-str (name field-name)
        field-name-lower (str/lower-case field-name-str)]
    (cond
      ; Special field name heuristics
      (str/includes? field-name-lower "email") :email-input
      (str/includes? field-name-lower "password") :password-input
      (str/includes? field-name-lower "url") :url-input
      (str/includes? field-name-lower "color") :color-input
      (str/includes? field-name-lower "description") :textarea
      (str/includes? field-name-lower "bio") :textarea
      (str/includes? field-name-lower "notes") :textarea
      (str/includes? field-name-lower "content") :textarea
      (and (str/includes? field-name-lower "date")
           (not (str/includes? field-name-lower "at"))) :date-input

      ; Type-based widget selection
      (= field-type :uuid) :text-input
      (= field-type :string) :text-input
      (= field-type :text) :textarea
      (= field-type :int) :number-input
      (= field-type :decimal) :number-input
      (= field-type :boolean) :checkbox
      (= field-type :instant) :datetime-input
      (= field-type :date) :date-input
      (= field-type :enum) :select
      (= field-type :json) :textarea
      (= field-type :binary) :file-input

      ; Default fallback
      :else :text-input)))

;; =============================================================================
;; Field Classification - Readonly, Hidden, Required
;; =============================================================================

(def common-readonly-fields
  "Set of field names that are typically readonly (in kebab-case)."
  #{:id :created-at :updated-at :created-by :updated-by
    :deleted-at :deleted-by :version :revision})

(def common-hidden-fields
  "Set of field names that are typically hidden in admin UI (in kebab-case)."
  #{:password-hash :password-encrypted :secret :token
    :api-key :private-key :salt :hash})

(defn should-be-readonly?
  "Determine if field should be readonly based on characteristics.

   Args:
     field-name: Keyword field name
     is-primary-key?: Boolean indicating if field is primary key

   Returns:
     Boolean true if field should be readonly

   Examples:
     (should-be-readonly? :id true)          ;=> true
     (should-be-readonly? :created-at false) ;=> true
     (should-be-readonly? :name false)       ;=> false"
  [field-name is-primary-key?]
  (or is-primary-key?
      (contains? common-readonly-fields field-name)))

(defn should-be-hidden?
  "Determine if field should be hidden in admin UI.

   Args:
     field-name: Keyword field name

   Returns:
     Boolean true if field should be hidden

   Examples:
     (should-be-hidden? :password-hash) ;=> true
     (should-be-hidden? :email)         ;=> false"
  [field-name]
  (contains? common-hidden-fields field-name))

(defn should-be-searchable?
  "Determine if field should be searchable.

   Args:
     field-type: Keyword field type
     field-name: Keyword field name

   Returns:
     Boolean true if field should be searchable

   Examples:
     (should-be-searchable? :string :email) ;=> true
     (should-be-searchable? :text :description) ;=> true
     (should-be-searchable? :binary :avatar) ;=> false"
  [field-type field-name]
  (and (contains? #{:string :text} field-type)
       (not (should-be-hidden? field-name))))

(defn should-be-sortable?
  "Determine if field should be sortable.

   Args:
     field-type: Keyword field type

   Returns:
     Boolean true if field should be sortable

   Examples:
     (should-be-sortable? :string) ;=> true
     (should-be-sortable? :int)    ;=> true
     (should-be-sortable? :json)   ;=> false"
  [field-type]
  (contains? #{:uuid :string :text :int :decimal :boolean :instant :date :enum} field-type))

(defn should-be-in-list-view?
  "Determine if field should be shown in table list view.
   
   Some fields are better shown only in detail/edit views, not in the
   compact table list view.
   
   Args:
     field-name: Keyword field name
     field-type: Keyword field type
     
   Returns:
     Boolean true if field should be in list view
     
   Examples:
     (should-be-in-list-view? :email :string)   ;=> true
     (should-be-in-list-view? :active :boolean) ;=> false
     (should-be-in-list-view? :notes :text)     ;=> false"
  [field-name field-type]
  (let [name-str (name field-name)]
    (not (or
          ;; Boolean flags that are better as badges or in detail view
          (and (= field-type :boolean) 
               (or (= field-name :active)
                   (str/starts-with? name-str "is-")
                   (str/starts-with? name-str "has-")
                   (str/starts-with? name-str "send-")))
          
          ;; Very long text fields
          (and (= field-type :text)
               (or (str/includes? name-str "description")
                   (str/includes? name-str "content")
                   (str/includes? name-str "notes")
                   (str/includes? name-str "body")))
          
          ;; Technical fields
          (or (str/includes? name-str "hash")
              (str/includes? name-str "secret")
              (str/includes? name-str "token")
              (str/includes? name-str "backup-codes"))))))

;; =============================================================================
;; Label Generation - Field Names to Display Labels
;; =============================================================================

(defn humanize-field-name
  "Convert field name to human-readable label.

   Converts kebab-case or snake_case to Title Case.

   Args:
     field-name: Keyword field name

   Returns:
     String display label

   Examples:
     (humanize-field-name :email)        ;=> \"Email\"
     (humanize-field-name :first-name)   ;=> \"First Name\"
     (humanize-field-name :created_at)   ;=> \"Created At\"
     (humanize-field-name :mfa-enabled)  ;=> \"Mfa Enabled\""
  [field-name]
  (-> (name field-name)
      (str/replace #"[-_]" " ")
      str/capitalize))

(defn humanize-entity-name
  "Convert entity name to human-readable label (pluralized).

   Args:
     entity-name: Keyword entity name

   Returns:
     String display label

   Examples:
     (humanize-entity-name :user)    ;=> \"Users\"
     (humanize-entity-name :item)    ;=> \"Items\"
     (humanize-entity-name :category) ;=> \"Categories\""
  [entity-name]
  (let [base-name (name entity-name)
        humanized (-> base-name
                      (str/replace #"[-_]" " ")
                      str/capitalize)]
    ; Simple pluralization (can be improved with inflection library)
    (cond
      (str/ends-with? humanized "y") (str (subs humanized 0 (dec (count humanized))) "ies")
      (str/ends-with? humanized "s") humanized
      :else (str humanized "s"))))

;; =============================================================================
;; Core Parsing - Database Metadata to Field Configurations
;; =============================================================================

(defn parse-column-metadata
  "Parse single database column into field configuration.

   Args:
     column-meta: Map with column metadata from database:
                  {:name \"email\"
                   :type \"varchar\"
                   :not-null true
                   :default nil
                   :primary-key false}

   Returns:
     Field configuration map:
     {:name :email
      :label \"Email\"
      :type :string
      :widget :email-input
      :required true
      :readonly false
      :hidden false
      :searchable true
      :sortable true
      :filterable true}

   Example:
     (parse-column-metadata {:name \"email\"
                            :type \"VARCHAR(255)\"
                            :not-null true
                            :primary-key false})"
   [column-meta]
   (let [;; Convert database column name (snake_case) to internal field name (kebab-case)
         field-name (-> (:name column-meta)
                        case-conversion/snake-case->kebab-case-string
                        keyword)
         sql-type (:type column-meta)
         field-type (infer-field-type sql-type field-name)  ; Pass field-name for heuristics
         widget (infer-widget-for-field field-name field-type sql-type)
         is-primary-key? (:primary-key column-meta false)
         is-not-null? (:not-null column-meta false)]
    {:name field-name
     :label (humanize-field-name field-name)
     :type field-type
     :widget widget
     :required (and is-not-null? (not is-primary-key?))
     :readonly (should-be-readonly? field-name is-primary-key?)
     :hidden (should-be-hidden? field-name)
     :searchable (should-be-searchable? field-type field-name)
     :sortable (should-be-sortable? field-type)
     :filterable (should-be-sortable? field-type)
     :primary-key is-primary-key?
     :default-value (:default column-meta)}))

(defn parse-table-metadata
  "Parse database table metadata into entity configuration.

   Takes raw database column metadata and produces a complete entity
   configuration with sensible defaults for all fields.

   Args:
     table-name: Keyword table name
     columns-meta: Vector of column metadata maps from database

   Returns:
     Entity configuration map with:
     - Field configurations for all columns
     - Default field lists (list, detail, editable, etc.)
     - Primary key identification
     - Auto-generated entity label

   Example:
     (parse-table-metadata :users
       [{:name \"id\" :type \"UUID\" :primary-key true}
        {:name \"email\" :type \"VARCHAR(255)\" :not-null true}
        {:name \"created_at\" :type \"TIMESTAMP\" :not-null true}])"
  [table-name columns-meta]
  (let [fields (mapv parse-column-metadata columns-meta)
        fields-by-name (into {} (map (juxt :name identity) fields))
        primary-key (->> fields
                         (filter :primary-key)
                         first
                         :name
                         (or :id))
        visible-fields (->> fields
                            (remove :hidden)
                            (mapv :name))
        readonly-field-names (->> fields
                                  (filter :readonly)
                                  (map :name)
                                  set)
        hidden-field-names (->> fields
                                (filter :hidden)
                                (map :name)
                                set)
        editable-fields (->> visible-fields
                             (remove readonly-field-names)
                             vec)
        search-fields (->> fields
                           (filter :searchable)
                           (mapv :name))
        list-fields (->> visible-fields
                         (filter (fn [field-name]
                                   (let [field-config (get fields-by-name field-name)]
                                     (should-be-in-list-view? field-name (:type field-config)))))
                         (take 5)  ; Default to first 5 suitable fields
                         vec)]
    {:label (humanize-entity-name table-name)
     :table-name table-name
     :primary-key primary-key
     :fields fields-by-name
     :list-fields list-fields
     :detail-fields visible-fields
     :search-fields search-fields
     :editable-fields editable-fields
     :hide-fields hidden-field-names
     :readonly-fields readonly-field-names
     :default-sort primary-key
     :default-sort-dir :desc
     :soft-delete (contains? fields-by-name :deleted-at)}))

;; =============================================================================
;; Configuration Merging - Auto-detected + Manual Overrides
;; =============================================================================

(defn merge-field-config
  "Merge auto-detected field config with manual overrides.

   Args:
     auto-config: Field configuration from schema introspection
     manual-config: Manual overrides map (can be nil)

   Returns:
     Merged field configuration with manual overrides applied

   Example:
     (merge-field-config {:name :email :widget :text-input}
                        {:widget :email-input :required true})"
  [auto-config manual-config]
  (if manual-config
    (merge auto-config manual-config)
    auto-config))

(defn merge-fields-config
  "Merge all field configurations with manual overrides.

   Args:
     auto-fields: Map of field-name -> auto-detected field config
     manual-fields: Map of field-name -> manual field config (can be nil)

   Returns:
     Merged fields map

   Example:
     (merge-fields-config {:email {...} :name {...}}
                         {:email {:widget :email-input}})"
  [auto-fields manual-fields]
  (if manual-fields
    (into {}
          (for [[field-name auto-config] auto-fields]
            [field-name (merge-field-config auto-config (get manual-fields field-name))]))
    auto-fields))

(defn build-entity-config
  "Build complete entity configuration by merging auto-detected with manual.

   This is the main function that combines schema introspection results
   with user-provided configuration overrides.

   Args:
     auto-config: Entity configuration from parse-table-metadata
     manual-config: Manual configuration overrides (can be nil or partial)

   Returns:
     Complete merged entity configuration

   Example:
     (build-entity-config
       {:label \"Users\" :fields {...} :list-fields [...]}
       {:label \"System Users\" :list-fields [:email :name :role]})"
  [auto-config manual-config]
  (if manual-config
    (-> auto-config
        (merge (dissoc manual-config :fields))  ; Merge all except :fields
        (assoc :fields (merge-fields-config
                        (:fields auto-config)
                        (:fields manual-config))))
    auto-config))

;; =============================================================================
;; Relationship Detection (Week 1 Stub)
;; =============================================================================

(defn detect-foreign-keys
  "Detect foreign key relationships from field names.

   Week 1: Simple heuristic based on field naming conventions.
   Week 2+: Use actual database foreign key constraints.

   Args:
     fields-by-name: Map of field-name -> field-config

   Returns:
     Vector of relationship maps:
     [{:field :user-id
       :references-entity :users
       :references-field :id}]

   Example:
     (detect-foreign-keys {:user-id {...} :category-id {...}})"
  [fields-by-name]
  ; Week 1: Stub implementation returns empty vector
  ; Week 2+: Implement relationship detection based on naming conventions
  ; Look for fields ending in -id or _id and infer entity from prefix
  [])

(defn detect-relationships
  "Detect all relationships for an entity configuration.

   Week 1: Stub that returns empty maps.
   Week 2+: Full relationship detection implementation.

   Args:
     entity-config: Entity configuration map

   Returns:
     Entity configuration with :relationships key added

   Example:
     (detect-relationships {:label \"Orders\" :fields {...}})"
  [entity-config]
  (assoc entity-config
         :relationships {:belongs-to []
                         :has-many []
                         :has-one []}))
