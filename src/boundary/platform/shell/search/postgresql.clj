(ns boundary.platform.shell.search.postgresql
  "PostgreSQL full-text search adapter.
   
   Implements ISearchProvider using PostgreSQL's built-in full-text search
   with tsvector, tsquery, and GIN indexes.
   
   Features:
   - Multi-field search with configurable weights (A/B/C/D)
   - Ranking and scoring using ts_rank
   - Highlighting with ts_headline
   - Efficient filtering and sorting
   
   Architecture: Shell Layer (Adapter)"
  (:require [boundary.platform.search.ports :as ports]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [honey.sql :as hsql]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ============================================================================
;; Query Translation
;; ============================================================================

(defn weight->postgres-label
  "Convert weight symbol to PostgreSQL label.
   
   Arguments
     weight - Weight symbol (:A, :B, :C, :D)
   
   Return value
     PostgreSQL weight label (\"A\", \"B\", \"C\", \"D\")"
  [weight]
  (case weight
    :A "A"
    :B "B"
    :C "C"
    :D "D"
    "D"))  ; Default

(defn build-tsquery
  "Build PostgreSQL tsquery from search query.
   
   Converts query DSL to PostgreSQL tsquery syntax.
   
   Arguments
     query-dsl - Query DSL map
   
   Return value
     PostgreSQL tsquery string
   
   Examples:
     {:type :match :text \"John\"} => \"John:*\"
     {:type :phrase :text \"John Doe\"} => \"John <-> Doe\"
     {:type :bool :clauses {:must [...] :should [...]}} => complex tsquery"
  [query-dsl]
  (case (:type query-dsl)
    :match
    (let [text (:text query-dsl)
          terms (str/split text #"\s+")]
      (str/join " & " (map #(str % ":*") terms)))
    
    :phrase
    (let [text (:text query-dsl)
          terms (str/split text #"\s+")]
      (str/join " <-> " terms))
    
    :term
    (:text query-dsl)
    
    :prefix
    (str (:text query-dsl) ":*")
    
    :bool
    (let [must (get-in query-dsl [:clauses :must])
          should (get-in query-dsl [:clauses :should])
          must-not (get-in query-dsl [:clauses :must-not])]
      (str/join " & "
        (concat
          (when (seq must)
            [(str "(" (str/join " & " (map build-tsquery must)) ")")])
          (when (seq should)
            [(str "(" (str/join " | " (map build-tsquery should)) ")")])
          (when (seq must-not)
            (map #(str "!(" (build-tsquery %) ")") must-not)))))
    
    ;; Default: treat as plain text
    (str (:text query-dsl ""))))

(defn build-search-condition
  "Build SQL search condition for full-text search.
   
   Arguments
     tsquery - PostgreSQL tsquery string (plain text for plainto_tsquery)
   
   Return value
     HoneySQL condition map using raw SQL for @@ operator"
  [tsquery]
  ;; Use plainto_tsquery instead of to_tsquery to automatically handle special characters
  ;; plainto_tsquery is more forgiving with user input and doesn't require special syntax
  ;; Column name is just "search_vector", not "tablename_search_vector"
  [:raw (str "search_vector @@ plainto_tsquery('english', '" (str/replace tsquery "'" "''") "')")])

(defn build-rank-expression
  "Build ts_rank expression for scoring.
   
   Arguments
     tsquery - PostgreSQL tsquery string (plain text for plainto_tsquery)
     weights - Vector of weights [D C B A] (default: [0.1 0.2 0.4 1.0])
   
   Return value
     HoneySQL expression using raw SQL for ts_rank"
  [tsquery weights]
  ;; Use plainto_tsquery for safer query parsing
  ;; Use curly braces for PostgreSQL array literal (not square brackets to avoid HoneySQL confusion)
  ;; Column name is just "search_vector", not "tablename_search_vector"
  (let [weights-str (str/join "," weights)]
    [:raw (format "ts_rank('{%s}', search_vector, plainto_tsquery('english', '%s'))"
                  weights-str
                  (str/replace tsquery "'" "''"))]))
(defn build-headline-expression
  "Build ts_headline expression for highlighting with given field and query.
   Returns HoneySQL expression using raw SQL for ts_headline"
  [field tsquery options]
  ;; Use plainto_tsquery for safer query parsing
  (let [max-words (or (:max-words options) 50)
        min-words (or (:min-words options) 20)
        short-word (or (:short-word options) 3)
        highlight-all (if (:highlight-all? options) "true" "false")
        opts-str (format "MaxWords=%d,MinWords=%d,ShortWord=%d,HighlightAll=%s" 
                        max-words min-words short-word highlight-all)]
    [:raw (format "ts_headline('english', %s, plainto_tsquery('english', '%s'), '%s')"
                  (name field) 
                  (str/replace tsquery "'" "''")
                  opts-str)]))

;; ============================================================================
;; Result Processing
;; ============================================================================

(defn process-search-result
  "Process a raw search result from PostgreSQL.
   
   Extracts highlights and standardizes score field.
   
   Arguments
     result - Raw result from database (unqualified keys from builder-fn)
     highlight-fields - Fields that have headline highlights
   
   Return value
     Processed result map"
  [result highlight-fields]
  (let [highlights (when (seq highlight-fields)
                    (into {}
                      (keep (fn [field]
                             (when-let [hl (get result (keyword (str (name field) "_highlight")))]
                               [field hl]))
                           highlight-fields)))]
    (-> result
        (assoc :score (or (:search_score result) (:rank result) 0.0))
        (cond-> (seq highlights)
          (assoc :_highlights highlights))
        (dissoc :search_score :rank)
        ;; Remove highlight columns from top level
        (as-> r (apply dissoc r 
                      (map #(keyword (str (name %) "_highlight")) highlight-fields))))))

;; ============================================================================
;; PostgreSQL Search Provider
;; ============================================================================

(defrecord PostgresSearchProvider [db-ctx config]
  ports/ISearchProvider
  
  (search [_this query-map]
    (try
      (log/debug "Executing PostgreSQL search" {:query-map query-map})
      (let [start-time (System/currentTimeMillis)
            index-name (:index query-map)
            query-dsl (:query query-map)
            filters (:filters query-map [])
            sort-spec (:sort query-map [{:field :_score :order :desc}])
            from (:from query-map 0)
            size (:size query-map 20)
            highlight? (:highlight? query-map false)
            highlight-fields (:highlight-fields query-map [:name :bio])
            
            ;; Extract raw text for plainto_tsquery (safer than build-tsquery which adds operators)
            ;; plainto_tsquery handles all special characters and doesn't require escape syntax
            raw-query-text (:text query-dsl "")
            
            ;; Build HoneySQL query
            ;; ts_rank weights should be numeric [D, C, B, A] not the field->label map
            ;; Default: {0.1, 0.2, 0.4, 1.0} for D, C, B, A respectively
            weights [0.1 0.2 0.4 1.0]
            
            select-fields (concat
                           [:*]
                           [[(build-rank-expression raw-query-text weights) :search_score]]
                           (when highlight?
                             (mapv (fn [field]
                                    [(build-headline-expression field raw-query-text {})
                                     (keyword (str (name field) "_highlight"))])
                                  highlight-fields)))
            
            where-conditions (into [(build-search-condition raw-query-text)]
                                (map (fn [{:keys [field value op]}]
                                      [(or op :=) field value])
                                    filters))
            
            order-by (mapv (fn [{:keys [field order]}]
                            (if (= field :_score)
                              [(keyword "search_score") (or order :desc)]
                              [(keyword (name field)) (or order :asc)]))
                          sort-spec)
            
            hsql-query {:select select-fields
                        :from [(keyword index-name)]
                        :where (if (> (count where-conditions) 1)
                                (into [:and] where-conditions)
                                (first where-conditions))
                        :order-by order-by
                        :limit size
                        :offset from}
            
            ;; Execute query
            [sql & params] (hsql/format hsql-query)
            
            _ (log/debug "Executing search" {:index index-name :raw-query raw-query-text :sql sql})
            
            ;; Execute query with proper options to return timestamps as Instant objects
            ;; and use unqualified keys
            results (jdbc/execute! db-ctx (into [sql] params) 
                                  {:builder-fn rs/as-unqualified-lower-maps})
            
            ;; Count total results (without pagination)
            count-query {:select [[:%count.* :total]]
                        :from [(keyword index-name)]
                        :where (if (> (count where-conditions) 1)
                                (into [:and] where-conditions)
                                (first where-conditions))}
            
            [count-sql & count-params] (hsql/format count-query)
            total-result (jdbc/execute-one! db-ctx (into [count-sql] count-params)
                                           {:builder-fn rs/as-unqualified-lower-maps})
            total (:total total-result 0)
            
            ;; Process results
            processed-results (mapv #(process-search-result % highlight-fields) results)
            max-score (when (seq processed-results)
                       (apply max (map :score processed-results)))
            
            took-ms (- (System/currentTimeMillis) start-time)]
        
        {:results processed-results
         :total total
         :max-score (or max-score 0.0)
         :took-ms took-ms})
      (catch Exception e
        (log/error e "Search failed" {:query query-map})
        (throw (ex-info "Search execution failed"
                       {:type :search-error
                        :query query-map}
                       e)))))
  
  (index-document [_this index-name document]
    (try
      (sql/insert! db-ctx (keyword index-name) document)
      (log/info "Indexed document" {:index index-name :id (:id document)})
      {:indexed true
       :id (:id document)
       :index index-name}
      (catch Exception e
        (log/error e "Failed to index document" {:index index-name :id (:id document)})
        (throw (ex-info "Document indexing failed"
                       {:type :indexing-error
                        :index index-name
                        :document-id (:id document)}
                       e)))))
  
  (delete-document [_this index-name document-id]
    (try
      (sql/delete! db-ctx (keyword index-name) {:id document-id})
      (log/info "Deleted document" {:index index-name :id document-id})
      {:deleted true
       :id document-id
       :index index-name}
      (catch Exception e
        (log/error e "Failed to delete document" {:index index-name :id document-id})
        (throw (ex-info "Document deletion failed"
                       {:type :deletion-error
                        :index index-name
                        :document-id document-id}
                       e)))))
  
  (update-document [_this index-name document-id updates]
    (try
      (sql/update! db-ctx (keyword index-name) updates {:id document-id})
      (log/info "Updated document" {:index index-name :id document-id})
      {:updated true
       :id document-id
       :index index-name}
      (catch Exception e
        (log/error e "Failed to update document" {:index index-name :id document-id})
        (throw (ex-info "Document update failed"
                       {:type :update-error
                        :index index-name
                        :document-id document-id}
                       e)))))
  
  (bulk-index [_this index-name documents]
    (let [start-time (System/currentTimeMillis)]
      (try
        (jdbc/with-transaction [tx db-ctx]
          (doseq [doc documents]
            (sql/insert! tx (keyword index-name) doc)))
        (let [took-ms (- (System/currentTimeMillis) start-time)]
          (log/info "Bulk indexed documents" 
                   {:index index-name :count (count documents) :took-ms took-ms})
          {:indexed-count (count documents)
           :failed-count 0
           :errors []
           :took-ms took-ms})
        (catch Exception e
          (log/error e "Bulk indexing failed" {:index index-name :count (count documents)})
          (throw (ex-info "Bulk indexing failed"
                         {:type :bulk-indexing-error
                          :index index-name
                          :document-count (count documents)}
                         e))))))
  
  (create-index [_this index-name _config]
    ;; Note: In PostgreSQL, "index" is the table with search columns
    ;; This would typically be handled by migrations
    (log/info "Index creation should be handled by migrations" 
             {:index index-name})
    {:created false
     :index index-name
     :message "Use database migrations to create search indexes"})
  
  (delete-index [_this index-name]
    (log/warn "Index deletion not implemented" {:index index-name})
    {:deleted false
     :index index-name
     :message "Index deletion must be done via migrations"})
  
  (get-index-stats [_this index-name]
    (try
      (let [count-query {:select [[:%count.* :total]]
                        :from [(keyword index-name)]}
            [sql & params] (hsql/format count-query)
            result (jdbc/execute-one! db-ctx (into [sql] params))
            
            ;; Get table size
            size-query "SELECT pg_total_relation_size(?) AS size_bytes"
            size-result (jdbc/execute-one! db-ctx [size-query (name index-name)])]
        
        {:index index-name
         :document-count (:total result 0)
         :size-bytes (:size_bytes size-result 0)
         :last-updated (java.time.Instant/now)})
      (catch Exception e
        (log/error e "Failed to get index stats" {:index index-name})
        {:index index-name
         :error "Failed to retrieve stats"}))))

;; ============================================================================
;; Factory Function
;; ============================================================================

(defn create-postgresql-search-provider
  "Create a PostgreSQL search provider.
   
   Arguments
     db-ctx - Database context (next.jdbc connection)
     config - Provider configuration map with weights per index
   
   Return value
     Implementation of ISearchProvider"
  [db-ctx config]
  (->PostgresSearchProvider db-ctx config))
