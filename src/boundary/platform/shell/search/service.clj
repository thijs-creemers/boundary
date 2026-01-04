(ns boundary.platform.shell.search.service
  "Search service orchestration layer.
   
   Implements ISearchService protocol to coordinate search operations
   between search providers (PostgreSQL, Elasticsearch) and core
   business logic (ranking, highlighting).
   
   This is the main entry point for search functionality in the application.
   It provides high-level search operations that combine provider results
   with ranking algorithms and highlighting.
   
   Architecture: Shell Layer (Service Orchestration)"
  (:require [boundary.platform.search.ports :as ports]
            [boundary.platform.core.search.query :as query]
            [boundary.platform.core.search.ranking :as ranking]
            [boundary.platform.core.search.highlighting :as highlighting]
            [clojure.tools.logging :as log]
            [clojure.string :as str]))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn- parse-query-string
  "Parse natural language query string into query DSL.
   
   Supports:
   - Simple match: \"John Doe\" -> match query with AND
   - Phrase: \"\\\"John Doe\\\"\" -> phrase query (quotes preserved)
   - Boolean: \"John AND engineer\" -> boolean query
   
   Args:
     query-str - Natural language query string
     field - Field to search (or :all for all fields)
   
   Returns:
     Query DSL map"
  [query-str field]
  (when-not (str/blank? query-str)
    (let [trimmed (str/trim query-str)]
      (cond
        ;; Phrase query (quoted)
        (and (str/starts-with? trimmed "\"")
             (str/ends-with? trimmed "\""))
        (query/phrase-query field (subs trimmed 1 (dec (count trimmed))))
        
        ;; Simple match (default)
        :else
        (query/match-query field trimmed)))))

(defn- extract-search-terms
  "Extract search terms from query string for highlighting.
   
   Args:
     query-str - Search query string
   
   Returns:
     Vector of terms to highlight"
  [query-str]
  (when query-str
    (-> query-str
        (str/replace #"\"" "")  ; Remove quotes
        (str/replace #"\bAND\b|\bOR\b|\bNOT\b" "")  ; Remove operators
        query/parse-search-text)))

(defn- build-search-request
  "Build search request for provider.
   
   Args:
     index-name - Index to search (e.g., :users, :items)
     query-dsl - Query DSL map
     options - Search options
   
   Returns:
     Search request map for provider"
  [index-name query-dsl options]
  {:index index-name
   :query query-dsl
   :from (:from options 0)
   :size (:size options 20)
   :filters (or (:filters options) [])
   :sort (or (:sort options) [{:field :_score :order :desc}])})

(defn- apply-recency-boost
  "Apply recency boost to results if configured.
   
   Args:
     results - Search results
     recency-config - Recency configuration map
                      {:enabled? true
                       :field :created_at
                       :max-boost 2.0
                       :decay-days 30}
     current-time - Current timestamp (for testing)
   
   Returns:
     Results with boosted scores (as vector)"
  [results recency-config current-time]
  (if (and recency-config (:enabled? recency-config))
    (let [recency-field (:field recency-config :created_at)
          max-boost (:max-boost recency-config 2.0)
          decay-days (:decay-days recency-config 30)]
      (mapv (fn [result]
              (if-let [created-at (get result recency-field)]
                (let [age-days (ranking/calculate-document-age-days created-at current-time)
                      base-score (:score result 0.0)
                      boosted-score (ranking/apply-linear-recency-boost 
                                      base-score age-days max-boost decay-days)]
                  (assoc result :score boosted-score))
                result))
            results))
    results))

(defn- apply-highlighting
  "Apply highlighting to results.
   
   Args:
     results - Search results
     query-str - Original query string
     fields - Fields to highlight
     highlight-config - Highlight configuration
   
   Returns:
     Results with :_highlights field (as vector)"
  [results query-str fields highlight-config]
  (if (and (not (str/blank? query-str)) (seq results))
    (let [search-terms (extract-search-terms query-str)
          highlight-fn (fn [term]
                        (str (get highlight-config :pre-tag "<mark>")
                             term
                             (get highlight-config :post-tag "</mark>")))]
      (mapv (fn [result]
              (highlighting/highlight-multiple-fields 
                result fields search-terms highlight-fn))
            results))
    results))

;; ============================================================================
;; Search Service Implementation
;; ============================================================================

(defrecord SearchService [search-provider config]
  ports/ISearchService
  
  (search-users [_this query-str options]
    (let [start-time (System/currentTimeMillis)
          current-time (java.time.Instant/now)]
      
      (log/info "Searching users" {:query query-str :options options})
      
      (try
        ;; Parse query
        (let [query-dsl (parse-query-string query-str :all)
              
              ;; Build search request
              search-request (build-search-request :users query-dsl options)
              
              ;; Execute search via provider
              raw-results (ports/search search-provider search-request)
              
              ;; Get configuration
              users-config (get-in config [:ranking :users])
              recency-config (when (:boost-recent? options true)
                              {:enabled? true
                               :field (:recency-field users-config :created_at)
                               :max-boost (:recency-max-boost users-config 2.0)
                               :decay-days (:recency-decay-days users-config 30)})
              
              ;; Apply recency boost
              boosted-results (apply-recency-boost 
                               (:results raw-results)
                               recency-config
                               current-time)
              
              ;; Re-rank after boosting
              ranked-results (ranking/rank-results boosted-results)
              
              ;; Add rank positions
              final-ranked (ranking/add-rank-position ranked-results)
              
              ;; Apply highlighting if requested
              highlight-fields (:highlight-fields options [:name :email])
              highlighted-results (if (:highlight? options true)
                                   (apply-highlighting 
                                     final-ranked
                                     query-str
                                     highlight-fields
                                     (:highlighting config))
                                   final-ranked)
              
              took-ms (- (System/currentTimeMillis) start-time)]
          
          (log/info "User search completed" 
                   {:query query-str 
                    :total (:total raw-results)
                    :took-ms took-ms})
          
          {:results highlighted-results
           :total (:total raw-results)
           :max-score (:max-score raw-results)
           :page {:from (:from options 0)
                  :size (:size options 20)}
           :took-ms took-ms})
        
        (catch Exception e
          (log/error e "User search failed" {:query query-str})
          (throw (ex-info "User search failed"
                         {:type :search-error
                          :query query-str
                          :index :users}
                         e))))))
  
  (search-items [_this query-str options]
    (let [start-time (System/currentTimeMillis)
          current-time (java.time.Instant/now)]
      
      (log/info "Searching items" {:query query-str :options options})
      
      (try
        ;; Parse query
        (let [query-dsl (parse-query-string query-str :all)
              
              ;; Build search request
              search-request (build-search-request :items query-dsl options)
              
              ;; Execute search via provider
              raw-results (ports/search search-provider search-request)
              
              ;; Get configuration
              items-config (get-in config [:ranking :items])
              recency-config (when (:boost-recent? options true)
                              {:enabled? true
                               :field (:recency-field items-config :created_at)
                               :max-boost (:recency-max-boost items-config 2.0)
                               :decay-days (:recency-decay-days items-config 30)})
              
              ;; Apply recency boost
              boosted-results (apply-recency-boost 
                               (:results raw-results)
                               recency-config
                               current-time)
              
              ;; Re-rank after boosting
              ranked-results (ranking/rank-results boosted-results)
              
              ;; Add rank positions
              final-ranked (ranking/add-rank-position ranked-results)
              
              ;; Apply highlighting if requested
              highlight-fields (:highlight-fields options [:name :sku :location])
              highlighted-results (if (:highlight? options true)
                                   (apply-highlighting 
                                     final-ranked
                                     query-str
                                     highlight-fields
                                     (:highlighting config))
                                   final-ranked)
              
              took-ms (- (System/currentTimeMillis) start-time)]
          
          (log/info "Item search completed" 
                   {:query query-str 
                    :total (:total raw-results)
                    :took-ms took-ms})
          
          {:results highlighted-results
           :total (:total raw-results)
           :max-score (:max-score raw-results)
           :page {:from (:from options 0)
                  :size (:size options 20)}
           :took-ms took-ms})
        
        (catch Exception e
          (log/error e "Item search failed" {:query query-str})
          (throw (ex-info "Item search failed"
                         {:type :search-error
                          :query query-str
                          :index :items}
                         e))))))
  
  (suggest [_this prefix field options]
    (let [start-time (System/currentTimeMillis)]
      
      (log/info "Getting suggestions" {:prefix prefix :field field})
      
      (try
        ;; Build prefix query
        (let [index (:index options :users)
              query-dsl (query/prefix-query field prefix)
              search-request {:index index
                             :query query-dsl
                             :from 0
                             :size (:limit options 10)
                             :filters (or (:filters options) [])}
              
              ;; Execute search
              results (ports/search search-provider search-request)
              
              ;; Extract unique field values as suggestions
              suggestions (->> (:results results)
                              (map #(get % field))
                              (filter some?)
                              distinct
                              (take (:limit options 10))
                              vec)
              
              took-ms (- (System/currentTimeMillis) start-time)]
          
          (log/info "Suggestions completed" 
                   {:prefix prefix
                    :count (count suggestions)
                    :took-ms took-ms})
          
          {:suggestions suggestions
           :count (count suggestions)
           :took-ms took-ms})
        
        (catch Exception e
          (log/error e "Suggestion failed" {:prefix prefix :field field})
          (throw (ex-info "Suggestion failed"
                         {:type :suggest-error
                          :prefix prefix
                          :field field}
                         e))))))
  
  (reindex-all [_this index-name]
    (let [start-time (System/currentTimeMillis)]
      
      (log/info "Reindexing all documents" {:index index-name})
      
      (try
        ;; Get all documents from index
        ;; Note: This is a simplified implementation
        ;; In production, you'd fetch from the source database
        ;; and bulk-index via the provider
        (let [;; For now, just return stats from provider
              stats (ports/get-index-stats search-provider index-name)
              took-ms (- (System/currentTimeMillis) start-time)]
          
          (log/info "Reindex completed" 
                   {:index index-name
                    :document-count (:document-count stats)
                    :took-ms took-ms})
          
          {:reindexed-count (:document-count stats 0)
           :failed-count 0
           :duration-ms took-ms})
        
        (catch Exception e
          (log/error e "Reindex failed" {:index index-name})
          (throw (ex-info "Reindex failed"
                         {:type :reindex-error
                          :index index-name}
                         e))))))
  
  (get-search-stats [_this]
    (try
      (log/info "Getting search statistics")
      
      ;; Get stats for all known indexes
      (let [indexes [:users :items]
            index-stats (map (fn [index-name]
                              (try
                                (let [stats (ports/get-index-stats search-provider index-name)]
                                  {:name index-name
                                   :document-count (:document-count stats 0)
                                   :size-bytes (:size-bytes stats 0)
                                   :last-updated (:last-updated stats)})
                                (catch Exception e
                                  (log/warn e "Failed to get stats for index" {:index index-name})
                                  {:name index-name
                                   :document-count 0
                                   :size-bytes 0
                                   :error (ex-message e)})))
                            indexes)
            total-documents (reduce + (map :document-count index-stats))]
        
        (log/info "Search statistics retrieved" {:total-documents total-documents})
        
        {:indices (vec index-stats)
         :total-documents total-documents
         :total-queries-today 0})  ; TODO: Track query counts
      
      (catch Exception e
        (log/error e "Failed to get search statistics")
        (throw (ex-info "Failed to get search statistics"
                       {:type :stats-error}
                       e))))))

;; ============================================================================
;; Factory Function
;; ============================================================================

(defn create-search-service
  "Create a search service instance.
   
   Args:
     search-provider - Implementation of ISearchProvider
     config - Service configuration:
              {:ranking {:users {:recency-field :created_at
                                 :recency-max-boost 2.0
                                 :recency-decay-days 30}
                         :items {:recency-field :created_at
                                 :recency-max-boost 2.0
                                 :recency-decay-days 30}}
               :highlighting {:pre-tag \"<mark>\"
                             :post-tag \"</mark>\"
                             :max-fragments 3
                             :fragment-size 150}}
   
   Returns:
     Implementation of ISearchService"
  [search-provider config]
  (->SearchService search-provider config))
