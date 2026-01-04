(ns boundary.platform.search.ports
  "Port definitions (protocols) for search functionality.
   
   Defines abstractions for search providers and search services.
   Implementations in shell layer provide concrete adapters.
   
   Architecture: Ports Layer (Protocols)")

;; ============================================================================
;; Search Provider Protocol
;; ============================================================================

(defprotocol ISearchProvider
  "Protocol for search provider implementations.
   
   Implementations handle the low-level search operations against
   specific backends (PostgreSQL full-text, Elasticsearch, etc.).
   
   All methods should return results in a consistent format regardless
   of the underlying search technology."
  
  (search
    [this query-map]
    "Execute a search query.
     
     Args:
       query-map - Search query specification:
                   {:query {:type :match :field :name :text \"John\"}
                    :filters [{:field :status :value \"active\"}]
                    :sort [{:field :_score :order :desc}]
                    :from 0
                    :size 20}
     
     Returns:
       {:results [{:id ... :score ... :_highlights {...}}]
        :total 100
        :max-score 0.95
        :took-ms 15}")
  
  (index-document
    [this index-name document]
    "Index a document for searching.
     
     Args:
       index-name - Name of the search index (e.g., \"users\", \"items\")
       document - Document to index:
                  {:id \"123\"
                   :name \"John Doe\"
                   :email \"john@example.com\"
                   :bio \"Software engineer\"}
     
     Returns:
       {:indexed true
        :id \"123\"
        :index \"users\"}")
  
  (delete-document
    [this index-name document-id]
    "Remove a document from the search index.
     
     Args:
       index-name - Name of the search index
       document-id - ID of document to delete
     
     Returns:
       {:deleted true
        :id \"123\"
        :index \"users\"}")
  
  (update-document
    [this index-name document-id updates]
    "Update an indexed document.
     
     Args:
       index-name - Name of the search index
       document-id - ID of document to update
       updates - Map of fields to update
     
     Returns:
       {:updated true
        :id \"123\"
        :index \"users\"}")
  
  (bulk-index
    [this index-name documents]
    "Index multiple documents in bulk.
     
     Args:
       index-name - Name of the search index
       documents - Collection of documents to index
     
     Returns:
       {:indexed-count 50
        :failed-count 0
        :errors []}")
  
  (create-index
    [this index-name config]
    "Create a new search index.
     
     Args:
       index-name - Name of the index to create
       config - Index configuration:
                {:fields {:name {:type :text :weight 'A}
                          :email {:type :text :weight 'B}
                          :bio {:type :text :weight 'C}}
                 :language \"english\"}
     
     Returns:
       {:created true
        :index \"users\"}")
  
  (delete-index
    [this index-name]
    "Delete a search index.
     
     Args:
       index-name - Name of the index to delete
     
     Returns:
       {:deleted true
        :index \"users\"}")
  
  (get-index-stats
    [this index-name]
    "Get statistics about a search index.
     
     Args:
       index-name - Name of the index
     
     Returns:
       {:index \"users\"
        :document-count 1000
        :size-bytes 1048576
        :last-updated #inst \"2024-01-15T10:30:00Z\"}"))

;; ============================================================================
;; Search Service Protocol
;; ============================================================================

(defprotocol ISearchService
  "Protocol for high-level search service.
   
   Orchestrates search operations, combines core business logic
   with search provider capabilities. Handles ranking, highlighting,
   and result transformation."
  
  (search-users
    [this query options]
    "Search for users.
     
     Args:
       query - Search query string (e.g., \"John engineer\")
       options - Search options:
                 {:filters {:status \"active\"}
                  :sort :relevance  ; or :name, :created-at
                  :highlight? true
                  :from 0
                  :size 20
                  :boost-recent? true}
     
     Returns:
       {:results [{:id \"123\"
                   :name \"John Doe\"
                   :email \"john@example.com\"
                   :score 0.95
                   :rank 1
                   :_highlights {:name \"<mark>John</mark> Doe\"}}]
        :total 100
        :page {:from 0 :size 20}
        :took-ms 25}")
  
  (search-items
    [this query options]
    "Search for items (inventory, products, etc.).
     
     Args:
       query - Search query string
       options - Search options (same as search-users)
     
     Returns:
       Search results in same format as search-users")
  
  (suggest
    [this prefix field options]
    "Get search suggestions/autocomplete.
     
     Args:
       prefix - Partial text to complete (e.g., \"Joh\")
       field - Field to suggest from (e.g., :name)
       options - Suggestion options:
                 {:limit 10
                  :filters {:status \"active\"}}
     
     Returns:
       {:suggestions [\"John Doe\" \"John Smith\" \"Johnny\"]
        :count 3}")
  
  (reindex-all
    [this index-name]
    "Rebuild search index from database.
     
     Args:
       index-name - Name of index to rebuild
     
     Returns:
       {:reindexed-count 1000
        :failed-count 0
        :duration-ms 5000}")
  
  (get-search-stats
    [this]
    "Get overall search statistics.
     
     Returns:
       {:indices [{:name \"users\" :document-count 1000}
                  {:name \"items\" :document-count 5000}]
        :total-documents 6000
        :total-queries-today 150}"))

;; ============================================================================
;; Factory Functions
;; ============================================================================

(defn create-search-service
  "Create a search service instance.
   
   Args:
     search-provider - Implementation of ISearchProvider
     config - Service configuration:
              {:default-size 20
               :max-size 100
               :highlight-enabled? true
               :boost-recent? true
               :recency-decay-factor 0.1}
   
   Returns:
     Implementation of ISearchService"
  [search-provider config]
  ;; Implementation provided in shell layer
  (throw (ex-info "create-search-service must be implemented in shell layer"
                  {:type :not-implemented})))
