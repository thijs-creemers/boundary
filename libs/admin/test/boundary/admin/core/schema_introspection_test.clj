(ns boundary.admin.core.schema-introspection-test
  "Unit tests for schema introspection pure functions.

   Tests cover:
   - SQL type normalization and inference
   - Field type detection and widget selection
   - Table metadata parsing and entity config generation
   - Relationship detection (Week 1 stub, Week 2+ full implementation)
   - Entity config merging (auto-detected + manual overrides)"
  (:require [boundary.admin.core.schema-introspection :as introspection]
            [clojure.test :refer [deftest is testing]]))

^{:kaocha.testable/meta {:unit true :admin true}}

;; =============================================================================
;; SQL Type Normalization and Inference Tests
;; =============================================================================

(deftest normalize-sql-type-test
  (testing "SQL type normalization handles various database type formats"
    (testing "PostgreSQL types"
      (is (= "varchar" (introspection/normalize-sql-type "VARCHAR")))
      (is (= "character varying" (introspection/normalize-sql-type "CHARACTER VARYING")))
      (is (= "text" (introspection/normalize-sql-type "TEXT")))
      (is (= "uuid" (introspection/normalize-sql-type "UUID")))
      (is (= "timestamp without time zone" (introspection/normalize-sql-type "TIMESTAMP WITHOUT TIME ZONE")))
      (is (= "timestamp with time zone" (introspection/normalize-sql-type "TIMESTAMP WITH TIME ZONE"))))

    (testing "SQLite types"
      (is (= "text" (introspection/normalize-sql-type "TEXT")))
      (is (= "integer" (introspection/normalize-sql-type "INTEGER")))
      (is (= "real" (introspection/normalize-sql-type "REAL")))
      (is (= "blob" (introspection/normalize-sql-type "BLOB"))))

    (testing "H2 types"
      (is (= "uuid" (introspection/normalize-sql-type "UUID()")))
      (is (= "varchar" (introspection/normalize-sql-type "VARCHAR(255)"))))

    (testing "Case insensitivity"
      (is (= "varchar" (introspection/normalize-sql-type "varchar")))
      (is (= "varchar" (introspection/normalize-sql-type "VarChar")))
      (is (= "text" (introspection/normalize-sql-type "text"))))))

(deftest infer-field-type-test
  (testing "Field type inference from SQL types"
    (testing "String types"
      (is (= :string (introspection/infer-field-type "VARCHAR")))
      (is (= :text (introspection/infer-field-type "TEXT")))  ; TEXT maps to :text
      (is (= :string (introspection/infer-field-type "CHAR"))))

    (testing "Numeric types"
      (is (= :int (introspection/infer-field-type "INTEGER")))
      (is (= :int (introspection/infer-field-type "BIGINT")))
      (is (= :int (introspection/infer-field-type "SMALLINT")))
      (is (= :decimal (introspection/infer-field-type "DECIMAL")))
      (is (= :decimal (introspection/infer-field-type "NUMERIC")))
      (is (= :decimal (introspection/infer-field-type "REAL"))))

    (testing "Boolean types"
      (is (= :boolean (introspection/infer-field-type "BOOLEAN")))
      (is (= :boolean (introspection/infer-field-type "BOOL"))))

    (testing "Temporal types"
      (is (= :instant (introspection/infer-field-type "TIMESTAMP")))
      (is (= :instant (introspection/infer-field-type "TIMESTAMPTZ")))
      (is (= :instant (introspection/infer-field-type "DATETIME")))
      (is (= :date (introspection/infer-field-type "DATE")))
      (is (= :time (introspection/infer-field-type "TIME"))))

    (testing "UUID types"
      (is (= :uuid (introspection/infer-field-type "UUID")))
      (is (= :uuid (introspection/infer-field-type "UUID()"))))

    (testing "Binary types"
      (is (= :binary (introspection/infer-field-type "BYTEA")))
      (is (= :binary (introspection/infer-field-type "BLOB"))))

    (testing "Unknown types default to string"
      (is (= :string (introspection/infer-field-type "UNKNOWN_TYPE")))
      (is (= :string (introspection/infer-field-type "CUSTOM_ENUM"))))))

;; =============================================================================
;; Widget Inference Tests
;; =============================================================================

(deftest infer-widget-for-field-test
  (testing "Widget inference based on field names and types"
    (testing "Special field name patterns"
      (is (= :email-input (introspection/infer-widget-for-field :email :string "VARCHAR")))
      (is (= :email-input (introspection/infer-widget-for-field :user-email :string "VARCHAR")))
      (is (= :password-input (introspection/infer-widget-for-field :password :string "VARCHAR")))
      (is (= :password-input (introspection/infer-widget-for-field :password-hash :string "VARCHAR")))
      (is (= :url-input (introspection/infer-widget-for-field :website :string "VARCHAR")))
      (is (= :url-input (introspection/infer-widget-for-field :profile-url :string "VARCHAR"))))

    (testing "Type-based widget selection"
      (is (= :checkbox (introspection/infer-widget-for-field :active :boolean "BOOLEAN")))
      (is (= :checkbox (introspection/infer-widget-for-field :enabled :boolean "BOOLEAN")))
      (is (= :datetime-input (introspection/infer-widget-for-field :created-at :instant "TIMESTAMP")))
      (is (= :datetime-input (introspection/infer-widget-for-field :updated-at :instant "TIMESTAMP")))
      (is (= :date-input (introspection/infer-widget-for-field :birth-date :date "DATE")))
      (is (= :number-input (introspection/infer-widget-for-field :quantity :int "INTEGER")))
      (is (= :number-input (introspection/infer-widget-for-field :price :decimal "DECIMAL"))))

    (testing "Textarea for long text fields"
      (is (= :textarea (introspection/infer-widget-for-field :description :string "TEXT")))
      (is (= :textarea (introspection/infer-widget-for-field :bio :string "TEXT")))
      (is (= :textarea (introspection/infer-widget-for-field :notes :string "TEXT")))
      (is (= :textarea (introspection/infer-widget-for-field :content :string "TEXT"))))

    (testing "Default to text input for other string fields"
      (is (= :text-input (introspection/infer-widget-for-field :name :string "VARCHAR")))
      (is (= :text-input (introspection/infer-widget-for-field :title :string "VARCHAR"))))))

;; =============================================================================
;; Table Metadata Parsing Tests
;; =============================================================================

(def sample-users-table-metadata
  "Sample users table metadata from database adapter.
   
   This format matches what get-table-info actually returns from database adapters:
   {:name, :type, :not-null, :default, :primary-key}"
  [{:name "id"
    :type "UUID"
    :not-null true
    :default "gen_random_uuid()"
    :primary-key true}
   {:name "email"
    :type "VARCHAR"
    :not-null true
    :default nil
    :primary-key false}
   {:name "name"
    :type "VARCHAR"
    :not-null false
    :default nil
    :primary-key false}
   {:name "password_hash"
    :type "VARCHAR"
    :not-null true
    :default nil
    :primary-key false}
   {:name "role"
    :type "VARCHAR"
    :not-null true
    :default "'user'"
    :primary-key false}
   {:name "active"
    :type "BOOLEAN"
    :not-null true
    :default "true"
    :primary-key false}
   {:name "created_at"
    :type "TIMESTAMP"
    :not-null true
    :default "now()"
    :primary-key false}
   {:name "updated_at"
    :type "TIMESTAMP"
    :not-null false
    :default nil
    :primary-key false}
   {:name "deleted_at"
    :type "TIMESTAMP"
    :not-null false
    :default nil
    :primary-key false}])

(deftest parse-table-metadata-test
  (testing "Parse users table metadata into entity config"
    (let [config (introspection/parse-table-metadata :users sample-users-table-metadata)]

      (testing "Basic entity info"
        (is (= :users (:table-name config)))
        (is (= "Users" (:label config)))
        (is (= :id (:primary-key config))))

      (testing "Fields parsed correctly"
        (let [fields (:fields config)]
          (is (= 9 (count fields)))

          ;; ID field
          (let [id-field (:id fields)]
            (is (= :uuid (:type id-field)))
            (is (true? (:readonly id-field)))
            (is (false? (:required id-field)))  ; ID is primary key, so required=false
            (is (= :text-input (:widget id-field))))

          ;; Email field
          (let [email-field (:email fields)]
            (is (= :string (:type email-field)))
            (is (true? (:required email-field)))  ; not-null and not PK
            (is (= :email-input (:widget email-field))))

          ;; Password hash field
          (let [password-field (:password-hash fields)]
            (is (= :string (:type password-field)))
            (is (= :password-input (:widget password-field))))

          ;; Active boolean field
          (let [active-field (:active fields)]
            (is (= :boolean (:type active-field)))
            (is (= :checkbox (:widget active-field))))

          ;; Timestamp fields
          (let [created-field (:created-at fields)]
            (is (= :instant (:type created-field)))
            (is (true? (:readonly created-field)))
            (is (= :datetime-input (:widget created-field))))))

      (testing "List fields exclude hidden and readonly"
        (let [list-fields (:list-fields config)]
          (is (vector? list-fields))
          (is (contains? (set list-fields) :email))
          (is (contains? (set list-fields) :name))
          (is (contains? (set list-fields) :role))
          ;; active is boolean - excluded from list view per should-be-in-list-view?
          (is (not (contains? (set list-fields) :active)))
          ;; Should not include hidden fields
          (is (not (contains? (set list-fields) :password-hash)))
          ;; deleted-at is readonly timestamp - may or may not be in list
          ))

      (testing "Search fields include text fields only"
        (let [search-fields (:search-fields config)]
          (is (contains? (set search-fields) :email))
          (is (contains? (set search-fields) :name))
          ;; Should not include non-text fields
          (is (not (contains? (set search-fields) :active)))
          (is (not (contains? (set search-fields) :created-at)))))

      (testing "Hidden fields identified correctly"
        (let [hidden-fields (:hide-fields config)]
          (is (contains? hidden-fields :password-hash))
          ;; deleted-at is readonly, not hidden (soft delete timestamp should be visible)
          (is (not (contains? hidden-fields :deleted-at)))))

      (testing "Readonly fields identified correctly"
        (let [readonly-fields (:readonly-fields config)]
          (is (contains? readonly-fields :id))
          (is (contains? readonly-fields :created-at))
          ;; updated-at is readonly but nullable
          (is (contains? readonly-fields :updated-at))))

      (testing "Soft delete detected"
        (is (true? (:soft-delete config))))

      (testing "Default sort by primary key descending"
        (is (= :id (:default-sort config)))
        (is (= :desc (:default-sort-dir config)))))))

;; =============================================================================
;; Entity Config Merging Tests
;; =============================================================================

(deftest build-entity-config-test
  (testing "Merge auto-detected config with manual overrides"
    (let [auto-config (introspection/parse-table-metadata :users sample-users-table-metadata)
          manual-config {:label "System Users"
                         :list-fields [:email :name :role]
                         :search-fields [:email]
                         :hide-fields #{:password-hash}}
          merged (introspection/build-entity-config auto-config manual-config)]

      (testing "Manual config overrides auto-detected values"
        (is (= "System Users" (:label merged)))
        (is (= [:email :name :role] (:list-fields merged)))
        (is (= [:email] (:search-fields merged))))

      (testing "Auto-detected values preserved when not overridden"
        (is (= :users (:table-name merged)))
        (is (= :id (:primary-key merged)))
        (is (true? (:soft-delete merged))))

      (testing "Fields merged correctly"
        (is (= 9 (count (:fields merged)))))

      (testing "Hide fields merged (union of auto + manual)"
        (let [hidden-fields (:hide-fields merged)]
          (is (contains? hidden-fields :password-hash))
          ;; deleted-at is readonly, not hidden
          (is (not (contains? hidden-fields :deleted-at)))))))

  (testing "Build config with no manual overrides"
    (let [auto-config (introspection/parse-table-metadata :users sample-users-table-metadata)
          merged (introspection/build-entity-config auto-config nil)]

      (is (= auto-config merged))))

  (testing "Build config with empty manual overrides"
    (let [auto-config (introspection/parse-table-metadata :users sample-users-table-metadata)
          merged (introspection/build-entity-config auto-config {})]

      (is (= auto-config merged)))))

;; =============================================================================
;; Relationship Detection Tests (Week 1 Stub)
;; =============================================================================

(deftest detect-relationships-test
  (testing "Week 1: Relationship detection is a stub"
    (let [config (introspection/parse-table-metadata :users sample-users-table-metadata)
          with-relationships (introspection/detect-relationships config)]

      (testing "Adds relationship keys with empty vectors"
        (is (= [] (:belongs-to (:relationships with-relationships))))
        (is (= [] (:has-many (:relationships with-relationships))))
        (is (= [] (:has-one (:relationships with-relationships)))))

      (testing "Week 2+: Will detect foreign keys and relationships"
        ;; Placeholder for future relationship detection tests
        ;; Should detect belongs-to, has-many based on foreign keys
        ;; Should infer relationship names from column names
        (is (map? (:relationships with-relationships)))))))

;; =============================================================================
;; Entity Name Humanization Tests
;; =============================================================================

(deftest humanize-entity-name-test
  (testing "Humanize entity names for display"
    (is (= "Users" (introspection/humanize-entity-name :users)))
    (is (= "User profiles" (introspection/humanize-entity-name :user-profiles)))
    (is (= "Order items" (introspection/humanize-entity-name :order-items)))
    (is (= "Products" (introspection/humanize-entity-name :products)))))

(deftest humanize-field-name-test
  (testing "Humanize field names for display"
    (is (= "Email" (introspection/humanize-field-name :email)))
    (is (= "Password hash" (introspection/humanize-field-name :password-hash)))
    (is (= "Created at" (introspection/humanize-field-name :created-at)))
    (is (= "User id" (introspection/humanize-field-name :user-id)))))

;; =============================================================================
;; Edge Cases and Error Handling
;; =============================================================================

(deftest parse-table-metadata-edge-cases-test
  (testing "Empty table metadata"
    (let [config (introspection/parse-table-metadata :empty [])]
      (is (= :empty (:table-name config)))
      (is (= {} (:fields config)))
      (is (= [] (:list-fields config)))))

  (testing "Table with only ID column"
    (let [metadata [{:name "id"
                     :type "UUID"
                     :not-null true
                     :primary-key true}]
          config (introspection/parse-table-metadata :minimal metadata)]
      (is (= :id (:primary-key config)))
      (is (= 1 (count (:fields config))))))

  (testing "Table without primary key"
    (let [metadata [{:name "value"
                     :type "VARCHAR"
                     :not-null true
                     :primary-key false}]
          config (introspection/parse-table-metadata :no-pk metadata)]
      ;; Should default to :id or first column
      (is (keyword? (:primary-key config)))))

  (testing "Table with composite primary key (Week 2+ support)"
    ;; Week 1: Only single-column primary keys supported
    ;; Week 2+: Add composite key support
    (testing "Week 1 limitation: composite keys not fully supported"
      (is true)))) ; Placeholder
