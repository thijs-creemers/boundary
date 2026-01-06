(ns boundary.platform.core.search.query
  "Pure query DSL functions for full-text search.
   
   This namespace provides a query DSL for building search queries
   in a database-agnostic way. All functions are pure (no side effects).
   
   Example:
     (-> (match-query :name \"John\")
         (filter-query {:role \"admin\"})
         (sort-by-relevance))
         
   Architecture: Functional Core (Pure)"
  (:require [clojure.string :as str]))

;; ============================================================================
;; Query Builders
;; ============================================================================

(defn match-query
  "Create a match query for basic text search.
   
   Match queries search for terms with stemming and tokenization.
   Example: searching for \"running\" will also match \"run\", \"runs\".
   
   Args:
     field - Field to search (:name, :email, :all for all fields)
     text - Search text
     
   Returns:
     Query map
     
   Example:
     (match-query :name \"John Doe\")
     ;=> {:type :match :field :name :text \"John Doe\"}
     
   Pure: true"
  [field text]
  {:type :match
   :field field
   :text text})

(defn phrase-query
  "Create a phrase query for exact phrase matching.
   
   Phrase queries require terms to appear in the exact order specified.
   Example: \"software engineer\" will not match \"engineer software\".
   
   Args:
     field - Field to search
     phrase - Exact phrase to match
     
   Returns:
     Query map
     
   Example:
     (phrase-query :name \"John Doe\")
     ;=> {:type :phrase :field :name :text \"John Doe\"}
     
   Pure: true"
  [field phrase]
  {:type :phrase
   :field field
   :text phrase})

(defn prefix-query
  "Create a prefix query for autocomplete/typeahead.
   
   Prefix queries match documents where the field starts with the given prefix.
   Useful for autocomplete features.
   
   Args:
     field - Field to search
     prefix - Text prefix
     
   Returns:
     Query map
     
   Example:
     (prefix-query :name \"joh\")
     ;=> {:type :prefix :field :name :text \"joh\"}
     
   Pure: true"
  [field prefix]
  {:type :prefix
   :field field
   :text prefix})

(defn fuzzy-query
  "Create a fuzzy query with typo tolerance.
   
   Fuzzy queries allow matching with small variations (typos, misspellings).
   Fuzziness controls how many character changes are allowed.
   
   Args:
     field - Field to search
     text - Search text (may contain typos)
     fuzziness - Edit distance (0-2, or :auto)
                 :auto = 0 for 1-2 char terms, 1 for 3-5 chars, 2 for 6+ chars
     
   Returns:
     Query map
     
   Example:
     (fuzzy-query :name \"Jhon\" :auto)
     ;=> {:type :fuzzy :field :name :text \"Jhon\" :fuzziness :auto}
     
   Pure: true"
  [field text fuzziness]
  {:type :fuzzy
   :field field
   :text text
   :fuzziness fuzziness})

(defn bool-query
  "Create a boolean query with AND, OR, NOT logic.
   
   Boolean queries combine multiple queries using boolean operators.
   
   Args:
     clauses - Map with :must (AND), :should (OR), :must-not (NOT) clauses
               {:must [query1 query2]      ; All must match (AND)
                :should [query3 query4]    ; At least one should match (OR)
                :must-not [query5]}        ; Must not match (NOT)
     
   Returns:
     Query map
     
   Example:
     (bool-query {:must [(match-query :name \"John\")]
                  :should [(match-query :role \"admin\")
                          (match-query :role \"manager\")]
                  :must-not [(match-query :status \"inactive\")]})
     
   Pure: true"
  [clauses]
  {:type :bool
   :clauses clauses})

;; ============================================================================
;; Query Modifiers
;; ============================================================================

(defn with-limit
  "Set result limit (items per page).
   
   Args:
     query - Base query
     limit - Maximum results to return (1-100)
     
   Returns:
     Query map with limit
     
   Example:
     (-> (match-query :name \"John\")
         (with-limit 50))
     
   Pure: true"
  [query limit]
  (assoc query :limit limit))

(defn with-offset
  "Set result offset (for pagination).
   
   Args:
     query - Base query
     offset - Number of results to skip
     
   Returns:
     Query map with offset
     
   Example:
     (-> (match-query :name \"John\")
         (with-limit 20)
         (with-offset 40))  ; Page 3
     
   Pure: true"
  [query offset]
  (assoc query :offset offset))

(defn filter-query
  "Add filters to query (exact match filters).
   
   Filters are applied as exact matches (not scored).
   Common use: filter by status, category, date ranges.
   
   Args:
     query - Base query
     filters - Map of filter conditions
               {:role \"admin\" :active true :created-after \"2024-01-01\"}
     
   Returns:
     Query map with filters
     
   Example:
     (-> (match-query :name \"John\")
         (filter-query {:role \"admin\" :active true}))
     
   Pure: true"
  [query filters]
  (update query :filters (fnil merge {}) filters))

(defn sort-by-relevance
  "Sort results by relevance score (highest first).
   
   This is the default sort for most search queries.
   
   Args:
     query - Query map
     
   Returns:
     Query map with relevance sort
     
   Example:
     (-> (match-query :name \"John\")
         (sort-by-relevance))
     
   Pure: true"
  [query]
  (assoc query :sort [{:_score :desc}]))

(defn sort-by-field
  "Sort results by field value.
   
   Args:
     query - Query map
     field - Field to sort by
     direction - :asc (ascending) or :desc (descending)
     
   Returns:
     Query map with field sort
     
   Example:
     (-> (match-query :name \"John\")
         (sort-by-field :created-at :desc))
     
   Pure: true"
  [query field direction]
  (update query :sort (fnil conj []) {field direction}))

(defn with-highlighting
  "Enable result highlighting.
   
   Highlights matching terms in search results.
   Example: \"John Doe\" becomes \"<mark>John</mark> Doe\"
   
   Args:
     query - Query map
     fields - Fields to highlight (optional, defaults to searched fields)
     
   Returns:
     Query map with highlighting enabled
     
   Example:
     (-> (match-query :name \"John\")
         (with-highlighting [:name :bio]))
     
   Pure: true"
  ([query]
   (assoc query :highlight true))
  ([query fields]
   (assoc query :highlight true :highlight-fields fields)))

;; ============================================================================
;; Query Validation
;; ============================================================================

(defn valid-query?
  "Validate query structure.
   
   Checks if query has required fields and valid values.
   
   Args:
     query - Query map
     
   Returns:
     {:valid? bool :errors [error-messages]}
     
   Example:
     (valid-query? (match-query :name \"John\"))
     ;=> {:valid? true :errors []}
     
     (valid-query? {:type :match})
     ;=> {:valid? false :errors [\"Missing required field: :text\"]}
     
   Pure: true"
  [query]
  (let [errors (cond-> []
                 (nil? (:type query))
                 (conj "Missing required field: :type")

                 (and (#{:match :phrase :prefix :fuzzy} (:type query))
                      (nil? (:text query)))
                 (conj "Missing required field: :text")

                 (and (#{:match :phrase :prefix :fuzzy} (:type query))
                      (nil? (:field query)))
                 (conj "Missing required field: :field")

                 (and (= :bool (:type query))
                      (nil? (:clauses query)))
                 (conj "Missing required field: :clauses for bool query")

                 (and (:limit query)
                      (or (< (:limit query) 1)
                          (> (:limit query) 100)))
                 (conj "Limit must be between 1 and 100")

                 (and (:offset query)
                      (< (:offset query) 0))
                 (conj "Offset must be non-negative")

                 (and (= :fuzzy (:type query))
                      (:fuzziness query)
                      (not (#{0 1 2 :auto} (:fuzziness query))))
                 (conj "Fuzziness must be 0, 1, 2, or :auto"))]
    {:valid? (empty? errors)
     :errors errors}))

;; ============================================================================
;; Query Combinators
;; ============================================================================

(defn combine-with-and
  "Combine multiple queries with AND logic (all must match).
   
   Args:
     queries - List of query maps
     
   Returns:
     Boolean query with all queries in :must clause
     
   Example:
     (combine-with-and [(match-query :name \"John\")
                        (match-query :role \"admin\")])
     
   Pure: true"
  [queries]
  (bool-query {:must queries}))

(defn combine-with-or
  "Combine multiple queries with OR logic (at least one must match).
   
   Args:
     queries - List of query maps
     
   Returns:
     Boolean query with all queries in :should clause
     
   Example:
     (combine-with-or [(match-query :role \"admin\")
                       (match-query :role \"manager\")])
     
   Pure: true"
  [queries]
  (bool-query {:should queries}))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn parse-search-text
  "Parse search text into tokens.
   
   Splits search text into individual terms for processing.
   Removes extra whitespace and empty tokens.
   
   Args:
     text - Search text
     
   Returns:
     Vector of search tokens
     
   Example:
     (parse-search-text \"  John   Doe  \")
     ;=> [\"John\" \"Doe\"]
     
   Pure: true"
  [text]
  (when text
    (->> (str/split (str text) #"\s+")
         (remove str/blank?)
         vec)))

(defn query->map
  "Convert query DSL to plain map (for serialization/debugging).
   
   Args:
     query - Query map
     
   Returns:
     Plain Clojure map
     
   Example:
     (query->map (match-query :name \"John\"))
     ;=> {:type :match :field :name :text \"John\"}
     
   Pure: true"
  [query]
  (into {} query))

(defn default-limit
  "Get default limit if not specified.
   
   Args:
     query - Query map
     default - Default limit value (default: 20)
     
   Returns:
     Limit value
     
   Example:
     (default-limit {} 20)
     ;=> 20
     
     (default-limit {:limit 50} 20)
     ;=> 50
     
   Pure: true"
  ([query]
   (default-limit query 20))
  ([query default]
   (or (:limit query) default)))

(defn default-offset
  "Get default offset if not specified.
   
   Args:
     query - Query map
     default - Default offset value (default: 0)
     
   Returns:
     Offset value
     
   Example:
     (default-offset {})
     ;=> 0
     
     (default-offset {:offset 40})
     ;=> 40
     
   Pure: true"
  ([query]
   (default-offset query 0))
  ([query default]
   (or (:offset query) default)))
