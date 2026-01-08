(ns boundary.admin.shell.service
  "Admin service implementation for CRUD operations on entities.

   This service provides database-agnostic CRUD operations on any entity
   managed by the admin interface. It coordinates between schema providers,
   permission checks, and database operations.

   Responsibilities:
   - Execute CRUD operations with observability
   - Apply pagination, filtering, sorting
   - Validate permissions and entity access
   - Transform data between DB and application formats
   - Handle soft/hard deletes based on schema"
  (:require
   [boundary.admin.ports :as ports]
   [boundary.admin.core.permissions :as permissions]
   [boundary.platform.shell.adapters.database.common.execution :as db]
   [boundary.platform.shell.persistence-interceptors :as persist-interceptors]
   [boundary.shared.core.utils.type-conversion :as type-conversion]
   [boundary.shared.core.utils.case-conversion :as case-conversion]
   [honey.sql :as sql]
   [clojure.string :as str])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; Query Building Helpers
;; =============================================================================

(defn build-pagination
  "Build pagination clause with safe bounds checking.

   Args:
     options: Map with :limit, :offset, or :page and :page-size

   Returns:
     Map with :limit and :offset

   Examples:
     (build-pagination {:limit 50 :offset 10})
     (build-pagination {:page 2 :page-size 25})"
  [options]
  (let [limit (or (:limit options) (:page-size options) 50)
        offset (or (:offset options)
                   (when (:page options)
                     (* (dec (:page options 1))
                        (or (:page-size options) 50)))
                   0)
        ; Clamp limits to reasonable ranges
        safe-limit (min (max limit 1) 200)
        safe-offset (max offset 0)]
    {:limit safe-limit
     :offset safe-offset}))

(defn build-ordering
  "Build ORDER BY clause.

   Args:
     sort-field: Keyword field to sort by
     sort-dir: :asc or :desc
     default-field: Fallback field if sort-field not provided

   Returns:
     Vector for HoneySQL :order-by clause

   Example:
     (build-ordering :email :asc :id) ;=> [[:email :asc]]"
  [sort-field sort-dir default-field]
  (let [field (or sort-field default-field)
        direction (or sort-dir :asc)]
    [[field direction]]))

(defn build-search-where
  "Build WHERE clause for text search across multiple fields.

   Args:
     search-term: String to search for
     search-fields: Vector of field keywords to search in

   Returns:
     HoneySQL WHERE clause (nil if no search term)

   Example:
     (build-search-where john [:email :name])
     => [:or [:like :email percent-john-percent]
             [:like :name percent-john-percent]]"
  [search-term search-fields]
  (when (and search-term (seq search-fields))
    (let [search-pattern (str "%" search-term "%")]
      (vec (cons :or
                 (mapv (fn [field]
                         [:like field search-pattern])
                       search-fields))))))

(defn build-filter-where
  "Build WHERE clause for field-specific filters.

   Args:
     filters: Map of field -> value

   Returns:
     HoneySQL WHERE clause (nil if no filters)

   Example:
     (build-filter-where {:role :admin :active true})
     ;=> [:and [:= :role :admin] [:= :active true]]"
  [filters]
  (when (seq filters)
    (vec (cons :and
               (mapv (fn [[field value]]
                       [:= field value])
                     filters)))))

(defn combine-where-clauses
  "Combine multiple WHERE clauses with AND.

   Args:
     clauses: Vector of WHERE clause expressions (nils are filtered)

   Returns:
     Combined WHERE clause or nil

   Example:
     (combine-where-clauses [[:= :active true] nil [:like :email \"%@example.com%\"]])
     ;=> [:and [:= :active true] [:like :email \"%@example.com%\"]]"
  [clauses]
  (let [non-nil-clauses (remove nil? clauses)]
    (when (seq non-nil-clauses)
      (if (= 1 (count non-nil-clauses))
        (first non-nil-clauses)
        (vec (cons :and non-nil-clauses))))))

;; =============================================================================
;; Admin Service Implementation
;; =============================================================================

(defrecord AdminService [db-ctx schema-provider logger error-reporter]
  ports/IAdminService

  (list-entities [_ entity-name options]
    (persist-interceptors/execute-persistence-operation
     :admin-list-entities
     {:entity (name entity-name)
      :limit (:limit options)
      :offset (:offset options)}
     (fn [{:keys [params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             search-term (:search options)
             search-fields (:search-fields entity-config)
             filters (:filters options)
             sort-field (:sort options)
             sort-dir (:sort-dir options)
             default-sort (:default-sort entity-config :id)

              ; Build query components
             search-where (build-search-where search-term search-fields)
             filter-where (build-filter-where filters)
             where-clause (combine-where-clauses [search-where filter-where])
             ordering (build-ordering sort-field sort-dir default-sort)
             pagination (build-pagination options)

              ; Build list query
             list-query (cond-> {:select [:*]
                                 :from [table-name]
                                 :order-by ordering
                                 :limit (:limit pagination)
                                 :offset (:offset pagination)}
                          where-clause (assoc :where where-clause))

              ; Build count query
             count-query (cond-> {:select [[:%count.* :total]]
                                  :from [table-name]}
                           where-clause (assoc :where where-clause))

              ; Execute queries
              records (db/execute-query! db-ctx list-query)
              count-result (db/execute-one! db-ctx count-query)
              total-count (:total count-result 0)

              ; Convert snake_case keys from database to kebab-case for internal use
              kebab-records (mapv case-conversion/snake-case->kebab-case-map records)

              ; Calculate pagination metadata
              page-size (:limit pagination)
              page-number (inc (quot (:offset pagination) page-size))
              total-pages (int (Math/ceil (/ total-count (double page-size))))]

          {:records kebab-records
          :total-count total-count
          :page-size page-size
          :page-number page-number
          :total-pages total-pages}))
     db-ctx))

  (get-entity [_ entity-name id]
    (persist-interceptors/execute-persistence-operation
     :admin-get-entity
     {:entity (name entity-name) :id id}
     (fn [{:keys [params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             primary-key (:primary-key entity-config :id)
             ;; Convert UUID to string for PostgreSQL compatibility
             id-str (type-conversion/uuid->string id)
              query {:select [:*]
                     :from [table-name]
                     :where [:= primary-key id-str]}
              db-result (db/execute-one! db-ctx query)]
          ; Convert snake_case keys from database to kebab-case for internal use
          (case-conversion/snake-case->kebab-case-map db-result)))
     db-ctx))

  (create-entity [_ entity-name data]
    (persist-interceptors/execute-persistence-operation
     :admin-create-entity
     {:entity (name entity-name)}
     (fn [{:keys [params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             primary-key (:primary-key entity-config :id)
             readonly-fields (:readonly-fields entity-config #{})

              ; Remove only readonly fields from input (not hide-fields!)
              ; hide-fields are for display only, data can still be provided
               sanitized-data (apply dissoc data readonly-fields)

               ; Add generated ID and timestamps
              now-str (type-conversion/instant->string (Instant/now))
              generated-id (UUID/randomUUID)
              prepared-data (assoc sanitized-data
                                   :id generated-id
                                   :created-at now-str)

              ; Convert kebab-case keys to snake_case for database
              db-data (case-conversion/kebab-case->snake-case-map prepared-data)

              ; Insert without RETURNING (H2 compatibility)
              insert-query {:insert-into table-name
                            :values [db-data]}
              _ (db/execute-one! db-ctx insert-query)

              ; Fetch the created record
              ;; Convert UUID to string for PostgreSQL compatibility
              id-str (type-conversion/uuid->string generated-id)
              select-query {:select [:*]
                            :from [table-name]
                            :where [:= primary-key id-str]}
              db-result (db/execute-one! db-ctx select-query)]

          ; Convert snake_case keys from database to kebab-case for internal use
          (case-conversion/snake-case->kebab-case-map db-result)))
     db-ctx))

  (update-entity [_ entity-name id data]
    (persist-interceptors/execute-persistence-operation
     :admin-update-entity
     {:entity (name entity-name) :id id}
     (fn [{:keys [params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             primary-key (:primary-key entity-config :id)
             readonly-fields (:readonly-fields entity-config #{})

              ; Remove only readonly and ID fields from update (not hide-fields!)
              ; hide-fields are for display only, data can still be provided
             sanitized-data (apply dissoc data (conj readonly-fields primary-key))

               ; Add updated timestamp - convert to string for PostgreSQL
              now-str (type-conversion/instant->string (Instant/now))
              prepared-data (assoc sanitized-data :updated-at now-str)

              ; Convert kebab-case keys to snake_case for database
              db-data (case-conversion/kebab-case->snake-case-map prepared-data)

              ;; Convert UUID to string for PostgreSQL compatibility
              id-str (type-conversion/uuid->string id)

              ; Update without RETURNING (H2 compatibility)
              update-query {:update table-name
                            :set db-data
                            :where [:= primary-key id-str]}
             _ (db/execute-one! db-ctx update-query)

              ; Fetch the updated record
              select-query {:select [:*]
                            :from [table-name]
                            :where [:= primary-key id-str]}
              db-result (db/execute-one! db-ctx select-query)]

          ; Convert snake_case keys from database to kebab-case for internal use
          (case-conversion/snake-case->kebab-case-map db-result)))
     db-ctx))

  (delete-entity [_ entity-name id]
    (persist-interceptors/execute-persistence-operation
     :admin-delete-entity
     {:entity (name entity-name) :id id}
     (fn [{:keys [params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             primary-key (:primary-key entity-config :id)
             soft-delete? (:soft-delete entity-config false)
             ;; Convert UUID to string for PostgreSQL compatibility
             id-str (type-conversion/uuid->string id)]

          (if soft-delete?
             ; Soft delete: Set deleted-at timestamp
            (let [now-str (type-conversion/instant->string (Instant/now))
                  query {:update table-name
                         :set {:deleted-at now-str}
                         :where [:= primary-key id-str]}]
              (pos? (db/execute-update! db-ctx query)))

            ; Hard delete: Permanent removal
           (let [query {:delete-from table-name
                        :where [:= primary-key id-str]}]
             (pos? (db/execute-update! db-ctx query))))))
     db-ctx))

  (count-entities [_ entity-name filters]
    (persist-interceptors/execute-persistence-operation
     :admin-count-entities
     {:entity (name entity-name)}
     (fn [{:keys [params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             filter-where (build-filter-where filters)
             query (cond-> {:select [[:%count.* :total]]
                            :from [table-name]}
                     filter-where (assoc :where filter-where))
             result (db/execute-one! db-ctx query)]
         (:total result 0)))
     db-ctx))

  (validate-entity-data [_ entity-name data]
    ; Week 1: Simple validation - check required fields present
    ; Week 2+: Full Malli schema validation
    (let [entity-config (ports/get-entity-config schema-provider entity-name)
          fields (:fields entity-config)
          readonly-fields (set (:readonly-fields entity-config))
          errors (reduce-kv
                  (fn [errs field-name field-config]
                    ; Skip validation for readonly fields (auto-generated by database)
                    (if (and (:required field-config)
                             (not (contains? readonly-fields field-name))
                             (nil? (get data field-name)))
                      (assoc errs field-name [(str "Field is required")])
                      errs))
                  {}
                  fields)]
      (if (empty? errors)
        {:valid? true :data data}
        {:valid? false :errors errors})))

  (bulk-delete-entities [_ entity-name ids]
    (persist-interceptors/execute-persistence-operation
     :admin-bulk-delete-entities
     {:entity (name entity-name) :count (count ids)}
     (fn [{:keys [params]}]
       (let [entity-config (ports/get-entity-config schema-provider entity-name)
             table-name (:table-name entity-config)
             primary-key (:primary-key entity-config :id)
             soft-delete? (:soft-delete entity-config false)

             query (if soft-delete?
                     {:update table-name
                      :set {:deleted-at (Instant/now)}
                      :where [:in primary-key ids]}
                     {:delete-from table-name
                      :where [:in primary-key ids]})

             affected-count (db/execute-update! db-ctx query)]

         {:success-count affected-count
          :failed-count (- (count ids) affected-count)
          :errors []}))
     db-ctx)))  ; Week 2+: Track individual failures


;; =============================================================================
;; Factory Function
;; =============================================================================

(defn create-admin-service
  "Create new AdminService instance.

   Args:
     db-ctx: Database context map with :adapter and :datasource
     schema-provider: ISchemaProvider implementation
     logger: Logger instance for operation logging
     error-reporter: Error reporter for exception tracking

   Returns:
     AdminService instance implementing IAdminService"
  [db-ctx schema-provider logger error-reporter]
  (->AdminService db-ctx schema-provider logger error-reporter))
