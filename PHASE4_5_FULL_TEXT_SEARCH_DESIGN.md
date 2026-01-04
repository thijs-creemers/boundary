# Phase 4.5: Full Text Search - Design Document

## Executive Summary

This document outlines the design and implementation strategy for Phase 4.5, which adds enterprise-grade full-text search capabilities to the Boundary Framework.

**Goals**:
1. PostgreSQL full-text search (primary, zero-dependency)
2. Pluggable search adapters (Elasticsearch, OpenSearch, Meilisearch)
3. Rich query DSL (match, phrase, fuzzy, filters, facets)
4. Maintain Functional Core / Imperative Shell architecture
5. Zero breaking changes to existing functionality

**Timeline**: 1 week  
**Status**: 🚧 In Design

---

## 1. Search Strategy & Architecture

### 1.1 Three-Tier Approach

**Tier 1: PostgreSQL Full-Text Search** (Default, Zero Dependencies)
- ✅ No external services required
- ✅ ACID transactions
- ✅ Built-in to PostgreSQL
- ✅ Perfect for small-to-medium datasets (< 1M documents)
- ✅ Zero infrastructure overhead
- ⚠️ Less sophisticated than dedicated search engines

**Tier 2: Meilisearch** (Recommended for Production)
- ✅ Fast, modern, typo-tolerant
- ✅ Simple deployment (single binary)
- ✅ Excellent relevance scoring
- ✅ Real-time indexing
- ✅ Minimal resource usage
- ⚠️ Requires separate service

**Tier 3: Elasticsearch/OpenSearch** (Enterprise Scale)
- ✅ Proven at massive scale
- ✅ Advanced analytics and aggregations
- ✅ Distributed architecture
- ✅ Rich ecosystem
- ⚠️ Complex deployment and operations
- ⚠️ High resource requirements

### 1.2 Design Philosophy

**Start Simple, Scale When Needed**:
1. Start with PostgreSQL FTS (built-in, free)
2. Upgrade to Meilisearch when performance matters (easy migration)
3. Move to Elasticsearch only if massive scale required (< 5% of projects)

**Port-Based Abstraction**:
```clojure
(defprotocol ISearchProvider
  (index-document [this index-name doc-id document])
  (search [this index-name query options])
  (delete-document [this index-name doc-id])
  (bulk-index [this index-name documents])
  (get-document [this index-name doc-id]))
```

---

## 2. PostgreSQL Full-Text Search (Primary Implementation)

### 2.1 Core Features

**Search Types**:
- **Match Query**: Basic text search with stemming
- **Phrase Query**: Exact phrase matching
- **Prefix Query**: Autocomplete/typeahead
- **Boolean Query**: AND, OR, NOT operators

**Ranking**:
- **tf-idf**: Term frequency - inverse document frequency
- **Field Weighting**: Title > body > tags
- **Recency Boost**: Prefer newer documents

**Filters**:
- Date ranges
- Numeric ranges
- Category filters
- Status filters

### 2.2 Implementation Strategy

#### Database Schema

```sql
-- Enable PostgreSQL full-text search extension
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- Trigram similarity (fuzzy search)

-- Add tsvector column for search (user example)
ALTER TABLE users
  ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
      setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
      setweight(to_tsvector('english', coalesce(email, '')), 'B')
    ) STORED;

-- Create GIN index for fast full-text search
CREATE INDEX users_search_idx ON users USING GIN (search_vector);

-- Optional: Trigram index for fuzzy/similarity search
CREATE INDEX users_name_trgm_idx ON users USING GIN (name gin_trgm_ops);
CREATE INDEX users_email_trgm_idx ON users USING GIN (email gin_trgm_ops);
```

#### Query Examples

**Simple Match**:
```sql
SELECT id, name, email,
       ts_rank(search_vector, to_tsquery('english', 'john')) AS rank
FROM users
WHERE search_vector @@ to_tsquery('english', 'john')
ORDER BY rank DESC
LIMIT 20;
```

**Phrase Search**:
```sql
-- Search for exact phrase "John Doe"
SELECT id, name, email,
       ts_rank(search_vector, phraseto_tsquery('english', 'John Doe')) AS rank
FROM users
WHERE search_vector @@ phraseto_tsquery('english', 'John Doe')
ORDER BY rank DESC;
```

**Boolean Search**:
```sql
-- Search for "john AND (admin OR manager)"
SELECT id, name, email,
       ts_rank(search_vector, to_tsquery('english', 'john & (admin | manager)')) AS rank
FROM users
WHERE search_vector @@ to_tsquery('english', 'john & (admin | manager)')
ORDER BY rank DESC;
```

**Fuzzy Search** (using trigrams):
```sql
-- Find similar names (typo tolerance)
SELECT id, name, email,
       similarity(name, 'Jhon Doe') AS similarity_score
FROM users
WHERE name % 'Jhon Doe'  -- % operator = similar to
ORDER BY similarity_score DESC
LIMIT 20;
```

**Autocomplete/Prefix**:
```sql
-- Typeahead: search names starting with "joh"
SELECT id, name, email
FROM users
WHERE name ILIKE 'joh%'
ORDER BY name
LIMIT 10;
```

**Combined: Full-Text + Filters**:
```sql
SELECT id, name, email, role, created_at,
       ts_rank(search_vector, to_tsquery('english', 'john')) AS rank
FROM users
WHERE search_vector @@ to_tsquery('english', 'john')
  AND role = 'admin'
  AND created_at >= '2024-01-01'
  AND deleted_at IS NULL
ORDER BY rank DESC, created_at DESC
LIMIT 20;
```

### 2.3 Configuration

**Search Configuration** (per module):
```clojure
{:boundary/search
 {:provider :postgresql  ; or :meilisearch, :elasticsearch
  
  ;; PostgreSQL-specific config
  :postgresql
  {:language "english"       ; or "dutch", "french", etc.
   :max-results 100
   :default-limit 20
   :fuzzy-threshold 0.3      ; Trigram similarity threshold (0-1)
   :enable-highlighting true
   :weights {:title 'A       ; Weight A (highest)
             :body 'B
             :tags 'C
             :metadata 'D}}   ; Weight D (lowest)
  
  ;; Index configuration per module
  :indexes
  {:users
   {:fields [:name :email :bio]
    :weights {:name 'A :email 'B :bio 'C}
    :filter-fields [:role :created_at :active]}
   
   :items
   {:fields [:name :description :sku :location]
    :weights {:name 'A :sku 'A :description 'B :location 'C}
    :filter-fields [:status :category :created_at]}}}}
```

---

## 3. Functional Core / Imperative Shell Architecture

### 3.1 Core Layer (Pure Functions)

**Location**: `src/boundary/platform/core/search/`

#### `query.clj` - Query DSL (Pure)

```clojure
(ns boundary.platform.core.search.query
  "Pure query DSL functions for search.")

(defn match-query
  "Create a match query (basic text search).
   
   Args:
     field - Field to search (e.g., :name, :all)
     text - Search text
     
   Returns:
     Query map
     
   Pure: true"
  [field text]
  {:type :match
   :field field
   :text text})

(defn phrase-query
  "Create a phrase query (exact phrase match).
   
   Args:
     field - Field to search
     phrase - Exact phrase
     
   Returns:
     Query map
     
   Pure: true"
  [field phrase]
  {:type :phrase
   :field field
   :text phrase})

(defn bool-query
  "Create a boolean query (AND, OR, NOT).
   
   Args:
     clauses - List of {:must ...}, {:should ...}, {:must-not ...}
     
   Returns:
     Query map
     
   Pure: true"
  [clauses]
  {:type :bool
   :clauses clauses})

(defn prefix-query
  "Create a prefix query (autocomplete).
   
   Args:
     field - Field to search
     prefix - Text prefix
     
   Returns:
     Query map
     
   Pure: true"
  [field prefix]
  {:type :prefix
   :field field
   :text prefix})

(defn fuzzy-query
  "Create a fuzzy query (typo tolerance).
   
   Args:
     field - Field to search
     text - Search text
     fuzziness - Edit distance (0-2, or 'AUTO')
     
   Returns:
     Query map
     
   Pure: true"
  [field text fuzziness]
  {:type :fuzzy
   :field field
   :text text
   :fuzziness fuzziness})

(defn filter-query
  "Add filters to query.
   
   Args:
     query - Base query
     filters - Map of filter conditions
     
   Returns:
     Query map with filters
     
   Pure: true"
  [query filters]
  (assoc query :filters filters))

(defn sort-by-relevance
  "Sort results by relevance score.
   
   Pure: true"
  [query]
  (assoc query :sort [{:_score :desc}]))

(defn sort-by-field
  "Sort results by field value.
   
   Args:
     query - Query map
     field - Field to sort by
     direction - :asc or :desc
     
   Pure: true"
  [query field direction]
  (update query :sort (fnil conj []) {field direction}))
```

#### `ranking.clj` - Ranking Logic (Pure)

```clojure
(ns boundary.platform.core.search.ranking
  "Pure ranking and scoring functions.")

(defn calculate-field-weight
  "Calculate weight for field based on config.
   
   PostgreSQL weights: A (1.0) > B (0.4) > C (0.2) > D (0.1)
   
   Pure: true"
  [field config]
  (case (get-in config [:weights field])
    'A 1.0
    'B 0.4
    'C 0.2
    'D 0.1
    0.1))  ; Default

(defn apply-recency-boost
  "Boost score for recent documents.
   
   Args:
     base-score - Original relevance score
     document-age-days - Age of document in days
     decay-factor - Decay rate (0-1)
     
   Returns:
     Boosted score
     
   Pure: true"
  [base-score document-age-days decay-factor]
  (let [time-decay (Math/exp (* (- decay-factor) document-age-days))]
    (* base-score (+ 1.0 time-decay))))

(defn normalize-scores
  "Normalize scores to 0-1 range.
   
   Pure: true"
  [results]
  (let [max-score (apply max (map :score results))
        min-score (apply min (map :score results))
        range (- max-score min-score)]
    (if (zero? range)
      results
      (map #(assoc % :normalized-score
                   (/ (- (:score %) min-score) range))
           results))))
```

#### `highlighting.clj` - Result Highlighting (Pure)

```clojure
(ns boundary.platform.core.search.highlighting
  "Pure functions for search result highlighting."
  (:require [clojure.string :as str]))

(defn highlight-matches
  "Highlight matching terms in text.
   
   Args:
     text - Original text
     search-terms - List of terms to highlight
     highlight-fn - Function to wrap matches (default: <mark>...</mark>)
     
   Returns:
     Text with highlighted terms
     
   Pure: true"
  ([text search-terms]
   (highlight-matches text search-terms
                      (fn [term] (str "<mark>" term "</mark>"))))
  ([text search-terms highlight-fn]
   (let [pattern (re-pattern (str "(?i)\\b(" (str/join "|" search-terms) ")\\b"))]
     (str/replace text pattern #(highlight-fn (second %))))))

(defn extract-snippet
  "Extract relevant snippet from text around search terms.
   
   Args:
     text - Full text
     search-terms - Terms to find
     max-length - Maximum snippet length (default: 200)
     
   Returns:
     Snippet with context around first match
     
   Pure: true"
  [text search-terms max-length]
  (let [pattern (re-pattern (str "(?i)\\b(" (str/join "|" search-terms) ")\\b"))
        match (re-find pattern text)]
    (if match
      (let [match-pos (.indexOf text (first match))
            start (max 0 (- match-pos (quot max-length 2)))
            end (min (count text) (+ match-pos (quot max-length 2)))]
        (str (when (> start 0) "...")
             (subs text start end)
             (when (< end (count text)) "...")))
      (subs text 0 (min max-length (count text))))))
```

### 3.2 Shell Layer (I/O Operations)

**Location**: `src/boundary/platform/shell/search/`

#### `postgresql.clj` - PostgreSQL Adapter

```clojure
(ns boundary.platform.shell.search.postgresql
  "PostgreSQL full-text search adapter."
  (:require [boundary.platform.core.search.query :as query-core]
            [boundary.platform.core.search.ranking :as ranking]
            [boundary.platform.shell.adapters.database.core :as db]
            [boundary.platform.search.ports :as ports]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn- query->tsquery
  "Convert query DSL to PostgreSQL tsquery syntax.
   
   Shell: Generates SQL"
  [query config]
  (case (:type query)
    :match
    (str "to_tsquery('" (:language config) "', ?)")
    
    :phrase
    (str "phraseto_tsquery('" (:language config) "', ?)")
    
    :prefix
    (str "to_tsquery('" (:language config) "', ? || ':*')")
    
    :bool
    ;; Convert {:must [...] :should [...] :must-not [...]} to tsquery
    (let [must (map #(str "(" (query->tsquery % config) ")") (get-in query [:clauses :must]))
          should (map #(str "(" (query->tsquery % config) ")") (get-in query [:clauses :should]))
          must-not (map #(str "!(" (query->tsquery % config) ")") (get-in query [:clauses :must-not]))]
      (str/join " & " (concat must should must-not)))))

(defn- build-search-sql
  "Build PostgreSQL full-text search SQL.
   
   Shell: SQL generation"
  [index-name query config]
  (let [table-name (name index-name)
        tsquery-expr (query->tsquery query config)
        filter-clauses (when-let [filters (:filters query)]
                        (for [[field value] filters]
                          (str (name field) " = ?")))
        where-clause (str "search_vector @@ " tsquery-expr
                         (when (seq filter-clauses)
                           (str " AND " (str/join " AND " filter-clauses))))
        sort-clause (if-let [sort (:sort query)]
                     (str/join ", " (map (fn [[field dir]]
                                          (str (name field) " " (name dir)))
                                        sort))
                     "ts_rank(search_vector, " tsquery-expr ") DESC")]
    {:sql (str "SELECT *, ts_rank(search_vector, " tsquery-expr ") AS _score "
               "FROM " table-name " "
               "WHERE " where-clause " "
               "ORDER BY " sort-clause " "
               "LIMIT ? OFFSET ?")
     :params (concat [(:text query)]
                    (map second (:filters query))
                    [(:limit query 20) (:offset query 0)])}))

(defrecord PostgreSQLSearchProvider [db-ctx config]
  ports/ISearchProvider
  
  (search [this index-name query options]
    (log/info "PostgreSQL full-text search" {:index index-name :query query})
    (let [{:keys [sql params]} (build-search-sql index-name query config)
          results (db/execute-query! db-ctx sql params)]
      {:results results
       :total (count results)  ; Or run separate COUNT query
       :took-ms 0}))  ; TODO: Measure query time
  
  (index-document [this index-name doc-id document]
    ;; PostgreSQL FTS uses generated columns - no explicit indexing needed
    ;; Just insert/update the document
    (log/debug "Document indexed (generated column)" {:index index-name :doc-id doc-id})
    {:indexed true})
  
  (delete-document [this index-name doc-id]
    (log/debug "Document deleted from search" {:index index-name :doc-id doc-id})
    {:deleted true})
  
  (bulk-index [this index-name documents]
    ;; Bulk insert documents - search_vector auto-generated
    (log/info "Bulk indexing documents" {:index index-name :count (count documents)})
    {:indexed (count documents)})
  
  (get-document [this index-name doc-id]
    (let [table-name (name index-name)
          sql (str "SELECT * FROM " table-name " WHERE id = ?")
          result (db/execute-one! db-ctx sql [doc-id])]
      result)))

(defn create-provider
  "Create PostgreSQL search provider.
   
   Shell: Constructor"
  [db-ctx config]
  (->PostgreSQLSearchProvider db-ctx config))
```

#### `meilisearch.clj` - Meilisearch Adapter (Future)

```clojure
(ns boundary.platform.shell.search.meilisearch
  "Meilisearch adapter for full-text search."
  (:require [boundary.platform.search.ports :as ports]
            [clj-http.client :as http]
            [cheshire.core :as json]))

(defrecord MeilisearchProvider [base-url api-key config]
  ports/ISearchProvider
  
  (search [this index-name query options]
    ;; HTTP request to Meilisearch API
    (let [response (http/post (str base-url "/indexes/" (name index-name) "/search")
                             {:headers {"Authorization" (str "Bearer " api-key)}
                              :body (json/generate-string
                                     {:q (:text query)
                                      :limit (:limit query 20)
                                      :offset (:offset query 0)
                                      :filter (:filters query)})
                              :as :json})]
      {:results (-> response :body :hits)
       :total (-> response :body :estimatedTotalHits)
       :took-ms (-> response :body :processingTimeMs)}))
  
  ;; ... other methods
  )

(defn create-provider
  "Create Meilisearch provider."
  [base-url api-key config]
  (->MeilisearchProvider base-url api-key config))
```

### 3.3 Ports Layer

**Location**: `src/boundary/platform/search/ports.clj`

```clojure
(ns boundary.platform.search.ports
  "Search provider ports (protocols).")

(defprotocol ISearchProvider
  "Search provider protocol."
  
  (search [this index-name query options]
    "Execute search query.
     
     Args:
       index-name - Index to search (e.g., :users, :items)
       query - Query map from query DSL
       options - Search options {:highlight? :facets? :explain?}
       
     Returns:
       {:results [...] :total int :took-ms int}")
  
  (index-document [this index-name doc-id document]
    "Index a single document.
     
     Args:
       index-name - Index name
       doc-id - Document ID
       document - Document map
       
     Returns:
       {:indexed bool :doc-id any}")
  
  (delete-document [this index-name doc-id]
    "Delete document from index.
     
     Returns:
       {:deleted bool}")
  
  (bulk-index [this index-name documents]
    "Index multiple documents.
     
     Args:
       documents - List of document maps with :id
       
     Returns:
       {:indexed int :failed int :errors [...]}")
  
  (get-document [this index-name doc-id]
    "Retrieve document by ID.
     
     Returns:
       Document map or nil"))

(defprotocol ISearchService
  "High-level search service."
  
  (search-users [this query-text options]
    "Search users by name, email, bio.")
  
  (search-items [this query-text options]
    "Search inventory items by name, SKU, description.")
  
  (autocomplete [this index-name prefix options]
    "Typeahead/autocomplete suggestions.")
  
  (similar-documents [this index-name doc-id options]
    "Find similar documents (more-like-this)."))
```

---

## 4. Migration & Database Schema

### 4.1 Migration: Add Full-Text Search to Users

**File**: `migrations/007_add_fulltext_search_to_users.sql`

```sql
-- Enable PostgreSQL full-text search extensions
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- Trigram similarity (fuzzy search)

-- Add search_vector column to users table
ALTER TABLE users
  ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
      setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
      setweight(to_tsvector('english', coalesce(email, '')), 'B') ||
      setweight(to_tsvector('english', coalesce(bio, '')), 'C')
    ) STORED;

-- Create GIN index for fast full-text search
CREATE INDEX users_search_vector_idx ON users USING GIN (search_vector);

-- Create trigram indexes for fuzzy/similarity search
CREATE INDEX users_name_trgm_idx ON users USING GIN (name gin_trgm_ops);
CREATE INDEX users_email_trgm_idx ON users USING GIN (email gin_trgm_ops);

-- Update search configuration
COMMENT ON COLUMN users.search_vector IS 'Full-text search vector (auto-generated from name, email, bio)';
```

### 4.2 Migration: Add Full-Text Search to Items (Inventory)

**File**: `migrations/008_add_fulltext_search_to_items.sql`

```sql
-- Add search_vector column to items table
ALTER TABLE items
  ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
      setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
      setweight(to_tsvector('english', coalesce(sku, '')), 'A') ||
      setweight(to_tsvector('english', coalesce(description, '')), 'B') ||
      setweight(to_tsvector('english', coalesce(location, '')), 'C')
    ) STORED;

-- Create GIN index for fast full-text search
CREATE INDEX items_search_vector_idx ON items USING GIN (search_vector);

-- Create trigram indexes for fuzzy search
CREATE INDEX items_name_trgm_idx ON items USING GIN (name gin_trgm_ops);
CREATE INDEX items_sku_trgm_idx ON items USING GIN (sku gin_trgm_ops);

COMMENT ON COLUMN items.search_vector IS 'Full-text search vector (auto-generated from name, sku, description, location)';
```

---

## 5. API Endpoints

### 5.1 Search Endpoints

```clojure
;; User search endpoint
["/api/users/search"
 {:get {:handler (handlers/search-users search-service config)
        :summary "Search users by name, email, bio"
        :parameters {:query {:q string?              ; Search query
                            :limit [:int {:min 1 :max 100}]
                            :offset [:int {:min 0}]
                            :fuzzy? [:boolean]       ; Enable fuzzy search
                            :highlight? [:boolean]}} ; Highlight matches
        :responses {200 {:body {:results vector?
                               :total int?
                               :took-ms int?}}}}}]

;; Autocomplete endpoint
["/api/users/autocomplete"
 {:get {:handler (handlers/autocomplete-users search-service config)
        :summary "Typeahead suggestions for user names"
        :parameters {:query {:q string?
                            :limit [:int {:max 10}]}}
        :responses {200 {:body {:suggestions vector?}}}}}]

;; Item search endpoint
["/api/items/search"
 {:get {:handler (handlers/search-items search-service config)
        :summary "Search inventory items"
        :parameters {:query {:q string?
                            :filters [:map]  ; {:category "electronics" :status "available"}
                            :sort [:string]  ; "relevance", "created_at", "price"
                            :limit [:int]
                            :offset [:int]}}
        :responses {200 {:body {:results vector?
                               :total int?
                               :facets [:map]  ; Category/filter counts
                               :took-ms int?}}}}}]
```

### 5.2 Response Format

**Search Response**:
```json
{
  "results": [
    {
      "id": "123...",
      "name": "John Doe",
      "email": "john@example.com",
      "bio": "Software engineer passionate about Clojure",
      "_score": 0.85,
      "_highlights": {
        "name": "<mark>John</mark> Doe",
        "bio": "Software engineer passionate about <mark>Clojure</mark>"
      }
    }
  ],
  "total": 42,
  "took": 15,
  "pagination": {
    "offset": 0,
    "limit": 20,
    "hasNext": true
  }
}
```

**Autocomplete Response**:
```json
{
  "suggestions": [
    {"text": "John Doe", "score": 0.95},
    {"text": "John Smith", "score": 0.85},
    {"text": "Jane Doe", "score": 0.75}
  ]
}
```

---

## 6. Implementation Plan

### Phase 1: Core Layer (Days 1-2)
1. ✅ Design document (this document)
2. Create query DSL (`query.clj`)
3. Create ranking logic (`ranking.clj`)
4. Create highlighting logic (`highlighting.clj`)
5. Write unit tests for core logic

### Phase 2: PostgreSQL Adapter (Days 2-3)
6. Create ports (`ports.clj`)
7. Implement PostgreSQL adapter (`postgresql.clj`)
8. Create database migrations
9. Write adapter integration tests

### Phase 3: Service Layer (Days 3-4)
10. Create search service (`service.clj`)
11. Implement user search
12. Implement item search
13. Implement autocomplete
14. Write service integration tests

### Phase 4: HTTP Layer (Days 4-5)
15. Add search HTTP handlers
16. Add autocomplete handlers
17. Integrate with existing user/item modules
18. Write HTTP endpoint tests

### Phase 5: Documentation & Polish (Days 5-6)
19. API documentation
20. Search guide with examples
21. Performance tuning guide
22. Update AGENTS.md

### Phase 6: Advanced Features (Day 6-7)
23. Fuzzy search
24. Faceted search
25. Highlighting
26. Performance testing

---

## 7. Testing Strategy

### Unit Tests (Core)
- `query_test.clj` - Query DSL construction
- `ranking_test.clj` - Score calculations
- `highlighting_test.clj` - Result highlighting

### Integration Tests (PostgreSQL)
- `postgresql_test.clj` - Full-text search queries
- `fuzzy_search_test.clj` - Trigram similarity
- `autocomplete_test.clj` - Prefix matching

### HTTP Tests
- `search_endpoints_test.clj` - API endpoint testing
- `pagination_search_test.clj` - Paginated search results

---

## 8. Performance Considerations

### PostgreSQL FTS Performance

| Dataset Size | Search Type | Performance | Index Size |
|--------------|-------------|-------------|------------|
| < 10K docs | Any | Fast (< 10ms) | < 1MB |
| 10K-100K | Match | Good (10-50ms) | 5-50MB |
| 100K-1M | Match | Acceptable (50-200ms) | 50-500MB |
| > 1M | Match | Slow (> 200ms) | Consider Meilisearch |

**Tips**:
- Keep `search_vector` STORED (pre-computed)
- Use GIN indexes (required for performance)
- Limit result set (max 100 results)
- Cache frequent queries
- Consider materialized views for complex searches

---

## 9. Configuration

```clojure
;; resources/conf/dev/config.edn
{:boundary/search
 {:provider :postgresql
  
  :postgresql
  {:language "english"
   :max-results 100
   :default-limit 20
   :fuzzy-threshold 0.3
   :enable-highlighting true
   :cache-ttl 300}  ; 5 minutes
  
  :indexes
  {:users
   {:fields [:name :email :bio]
    :weights {:name 'A :email 'B :bio 'C}
    :filter-fields [:role :active :created_at]}
   
   :items
   {:fields [:name :sku :description :location]
    :weights {:name 'A :sku 'A :description 'B :location 'C}
    :filter-fields [:status :category :quantity]}}}}
```

---

## 10. Success Criteria

✅ **Feature Complete**:
- PostgreSQL full-text search working
- Query DSL implemented (match, phrase, fuzzy, bool)
- Ranking and relevance scoring
- Autocomplete/typeahead
- Result highlighting
- Filter support

✅ **Quality**:
- 100% test coverage for core logic
- Integration tests with PostgreSQL
- Performance benchmarks acceptable (< 50ms for < 100K docs)
- Documentation complete

✅ **Compliance**:
- FC/IS architecture maintained
- Port-based abstraction
- Zero breaking changes
- Pluggable adapters (Meilisearch ready)

---

## 11. Future Enhancements

**Phase 4.6+**:
1. Meilisearch adapter implementation
2. Elasticsearch adapter
3. Faceted search (category counts)
4. More-like-this (similarity search)
5. Spell correction
6. Synonym support
7. Search analytics (query logs, popular terms)

---

## References

- **PostgreSQL Full-Text Search**:  
  https://www.postgresql.org/docs/current/textsearch.html

- **PostgreSQL Trigram Similarity**:  
  https://www.postgresql.org/docs/current/pgtrgm.html

- **Meilisearch Documentation**:  
  https://www.meilisearch.com/docs

- **Elasticsearch Guide**:  
  https://www.elastic.co/guide/en/elasticsearch/reference/current/index.html

---

**Status**: 🚧 In Design  
**Next Step**: Create search core layer (query DSL)  
**ETA**: 7 days from design approval  
**Date**: 2026-01-04
