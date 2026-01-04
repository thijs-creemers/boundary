(ns boundary.platform.core.search.query-test
  "Unit tests for search query DSL.
   
   Tests all query builders, modifiers, combinators, and validators.
   All functions are pure so tests need no mocks or fixtures."
  {:kaocha.testable/meta {:unit true :search true}}
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.platform.core.search.query :as query]))

;; ============================================================================
;; Basic Query Builders
;; ============================================================================

(deftest match-query-test
  (testing "creates match query with single field"
    (let [q (query/match-query :name "John")]
      (is (= :match (:type q)))
      (is (= :name (:field q)))
      (is (= "John" (:text q)))))
  
  (testing "handles empty text"
    (let [q (query/match-query :name "")]
      (is (= "" (:text q)))))
  
  (testing "handles nil field"
    (let [q (query/match-query nil "John")]
      (is (nil? (:field q)))))
  
  (testing "handles multiword search"
    (let [q (query/match-query :bio "software engineer")]
      (is (= "software engineer" (:text q))))))

(deftest phrase-query-test
  (testing "creates phrase query"
    (let [q (query/phrase-query :bio "software engineer")]
      (is (= :phrase (:type q)))
      (is (= :bio (:field q)))
      (is (= "software engineer" (:text q)))))
  
  (testing "preserves exact phrase"
    (let [q (query/phrase-query :description "New York City")]
      (is (= "New York City" (:text q)))))
  
  (testing "handles single word phrase"
    (let [q (query/phrase-query :name "John")]
      (is (= :phrase (:type q)))
      (is (= "John" (:text q))))))

(deftest prefix-query-test
  (testing "creates prefix query"
    (let [q (query/prefix-query :email "john@")]
      (is (= :prefix (:type q)))
      (is (= :email (:field q)))
      (is (= "john@" (:text q)))))
  
  (testing "handles short prefix"
    (let [q (query/prefix-query :name "J")]
      (is (= "J" (:text q)))))
  
  (testing "handles empty prefix"
    (let [q (query/prefix-query :name "")]
      (is (= "" (:text q))))))

(deftest fuzzy-query-test
  (testing "creates fuzzy query with :auto fuzziness"
    (let [q (query/fuzzy-query :name "Jon" :auto)]
      (is (= :fuzzy (:type q)))
      (is (= :name (:field q)))
      (is (= "Jon" (:text q)))
      (is (= :auto (:fuzziness q)))))
  
  (testing "creates fuzzy query with numeric fuzziness"
    (let [q (query/fuzzy-query :email "john@exmple.com" 1)]
      (is (= 1 (:fuzziness q)))))
  
  (testing "handles zero fuzziness"
    (let [q (query/fuzzy-query :name "John" 0)]
      (is (= 0 (:fuzziness q)))))
  
  (testing "handles high fuzziness"
    (let [q (query/fuzzy-query :name "Jhon" 2)]
      (is (= 2 (:fuzziness q))))))

;; ============================================================================
;; Boolean Queries
;; ============================================================================

(deftest bool-query-test
  (testing "creates bool query with must clauses"
    (let [q1 (query/match-query :name "John")
          q2 (query/match-query :role "admin")
          bool-q (query/bool-query {:must [q1 q2]})]
      (is (= :bool (:type bool-q)))
      (is (= 2 (count (get-in bool-q [:clauses :must]))))
      (is (empty? (get-in bool-q [:clauses :should])))
      (is (empty? (get-in bool-q [:clauses :must-not])))))
  
  (testing "creates bool query with should clauses"
    (let [q1 (query/match-query :name "John")
          q2 (query/match-query :name "Jane")
          bool-q (query/bool-query {:should [q1 q2]})]
      (is (= 2 (count (get-in bool-q [:clauses :should]))))
      (is (empty? (get-in bool-q [:clauses :must])))))
  
  (testing "creates bool query with must-not clauses"
    (let [q1 (query/match-query :status "inactive")
          bool-q (query/bool-query {:must-not [q1]})]
      (is (= 1 (count (get-in bool-q [:clauses :must-not]))))))
  
  (testing "creates bool query with all clause types"
    (let [q1 (query/match-query :name "John")
          q2 (query/match-query :role "admin")
          q3 (query/match-query :status "inactive")
          bool-q (query/bool-query {:must [q1]
                                   :should [q2]
                                   :must-not [q3]})]
      (is (= 1 (count (get-in bool-q [:clauses :must]))))
      (is (= 1 (count (get-in bool-q [:clauses :should]))))
      (is (= 1 (count (get-in bool-q [:clauses :must-not]))))))
  
  (testing "handles empty clauses"
    (let [bool-q (query/bool-query {})]
      (is (= :bool (:type bool-q)))
      (is (empty? (get-in bool-q [:clauses :must])))
      (is (empty? (get-in bool-q [:clauses :should])))
      (is (empty? (get-in bool-q [:clauses :must-not]))))))

;; ============================================================================
;; Query Modifiers
;; ============================================================================

(deftest with-limit-test
  (testing "adds limit to query"
    (let [q (query/match-query :name "John")
          limited (query/with-limit q 20)]
      (is (= 20 (:limit limited)))))
  
  (testing "updates existing limit"
    (let [q (-> (query/match-query :name "John")
                (query/with-limit 10)
                (query/with-limit 20))]
      (is (= 20 (:limit q)))))
  
  (testing "handles zero limit"
    (let [q (query/with-limit (query/match-query :name "John") 0)]
      (is (= 0 (:limit q)))))
  
  (testing "preserves other query fields"
    (let [q (-> (query/match-query :name "John")
                (query/with-limit 20))]
      (is (= :match (:type q)))
      (is (= :name (:field q)))
      (is (= "John" (:text q))))))

(deftest with-offset-test
  (testing "adds offset to query"
    (let [q (query/match-query :name "John")
          offset-q (query/with-offset q 20)]
      (is (= 20 (:offset offset-q)))))
  
  (testing "updates existing offset"
    (let [q (-> (query/match-query :name "John")
                (query/with-offset 10)
                (query/with-offset 20))]
      (is (= 20 (:offset q)))))
  
  (testing "handles zero offset"
    (let [q (query/with-offset (query/match-query :name "John") 0)]
      (is (= 0 (:offset q)))))
  
  (testing "preserves other query fields"
    (let [q (-> (query/match-query :name "John")
                (query/with-offset 20))]
      (is (= :match (:type q)))
      (is (= :name (:field q)))
      (is (= "John" (:text q))))))

(deftest filter-query-test
  (testing "adds filters to query"
    (let [q (query/match-query :name "John")
          filtered (query/filter-query q {:role "admin" :active true})]
      (is (= {:role "admin" :active true} (:filters filtered)))))
  
  (testing "merges with existing filters"
    (let [q (-> (query/match-query :name "John")
                (query/filter-query {:role "admin"})
                (query/filter-query {:active true}))]
      (is (= {:role "admin" :active true} (:filters q)))))
  
  (testing "overwrites duplicate filter keys"
    (let [q (-> (query/match-query :name "John")
                (query/filter-query {:role "user"})
                (query/filter-query {:role "admin"}))]
      (is (= "admin" (get-in q [:filters :role])))))
  
  (testing "handles empty filters"
    (let [q (query/filter-query (query/match-query :name "John") {})]
      (is (= {} (:filters q))))))

(deftest with-highlighting-test
  (testing "adds highlighting to query"
    (let [q (query/match-query :name "John")
          highlighted (query/with-highlighting q [:name :bio])]
      (is (= [:name :bio] (:highlight-fields highlighted)))))
  
  (testing "updates existing highlighting"
    (let [q (-> (query/match-query :name "John")
                (query/with-highlighting [:name])
                (query/with-highlighting [:bio]))]
      (is (= [:bio] (:highlight-fields q)))))
  
  (testing "handles empty field list"
    (let [q (query/with-highlighting (query/match-query :name "John") [])]
      (is (= [] (:highlight-fields q)))))
  
  (testing "handles single field"
    (let [q (query/with-highlighting (query/match-query :name "John") [:name])]
      (is (= [:name] (:highlight-fields q))))))

(deftest sort-by-relevance-test
  (testing "adds relevance sort"
    (let [q (query/match-query :name "John")
          sorted (query/sort-by-relevance q)]
      (is (= [{:_score :desc}] (:sort sorted)))))
  
  (testing "overwrites existing sort"
    (let [q (-> (query/match-query :name "John")
                (assoc :sort [{:name :asc}])
                (query/sort-by-relevance))]
      (is (= [{:_score :desc}] (:sort q)))))
  
  (testing "returns query with sort metadata"
    (let [q (query/sort-by-relevance (query/match-query :name "John"))]
      (is (some? (:sort q)))
      (is (vector? (:sort q)))
      (is (= :desc (:_score (first (:sort q))))))))

(deftest sort-by-field-test
  (testing "adds field sort ascending"
    (let [q (query/match-query :name "John")
          sorted (query/sort-by-field q :created-at :asc)]
      (is (= [{:created-at :asc}] (:sort sorted)))))
  
  (testing "adds field sort descending"
    (let [q (query/match-query :name "John")
          sorted (query/sort-by-field q :created-at :desc)]
      (is (= [{:created-at :desc}] (:sort sorted)))))
  
  (testing "appends to existing sort"
    (let [q (-> (query/match-query :name "John")
                (query/sort-by-field :name :asc)
                (query/sort-by-field :email :desc))]
      (is (= 2 (count (:sort q))))
      (is (= :asc (:name (first (:sort q)))))
      (is (= :desc (:email (second (:sort q))))))))

;; ============================================================================
;; Query Composition
;; ============================================================================

(deftest query-composition-test
  (testing "chains multiple modifiers"
    (let [q (-> (query/match-query :name "John")
                (query/filter-query {:role "admin"})
                (query/with-limit 20)
                (query/with-offset 40)
                (query/with-highlighting [:name :bio])
                (query/sort-by-relevance))]
      (is (= :match (:type q)))
      (is (= {:role "admin"} (:filters q)))
      (is (= 20 (:limit q)))
      (is (= 40 (:offset q)))
      (is (= [:name :bio] (:highlight-fields q)))
      (is (= [{:_score :desc}] (:sort q)))))
  
  (testing "composes bool query with filters"
    (let [q1 (query/match-query :name "John")
          q2 (query/match-query :role "admin")
          bool-q (-> (query/bool-query {:must [q1] :should [q2]})
                     (query/filter-query {:active true})
                     (query/with-limit 10))]
      (is (= :bool (:type bool-q)))
      (is (= {:active true} (:filters bool-q)))
      (is (= 10 (:limit bool-q)))))
  
  (testing "builds complex nested bool query"
    (let [q1 (query/match-query :name "John")
          q2 (query/phrase-query :bio "software engineer")
          q3 (query/prefix-query :email "john@")
          q4 (query/match-query :status "inactive")
          bool-q (query/bool-query {:must [q1 q2]
                                   :should [q3]
                                   :must-not [q4]})]
      (is (= 2 (count (get-in bool-q [:clauses :must]))))
      (is (= 1 (count (get-in bool-q [:clauses :should]))))
      (is (= 1 (count (get-in bool-q [:clauses :must-not])))))))

;; ============================================================================
;; Query Combinators
;; ============================================================================

(deftest combine-with-and-test
  (testing "combines two queries with AND"
    (let [q1 (query/match-query :name "John")
          q2 (query/match-query :role "admin")
          combined (query/combine-with-and [q1 q2])]
      (is (= :bool (:type combined)))
      (is (= 2 (count (get-in combined [:clauses :must]))))))
  
  (testing "combines multiple queries with AND"
    (let [q1 (query/match-query :name "John")
          q2 (query/match-query :role "admin")
          q3 (query/match-query :status "active")
          combined (query/combine-with-and [q1 q2 q3])]
      (is (= :bool (:type combined)))
      (is (= 3 (count (get-in combined [:clauses :must]))))))
  
  (testing "combines single query"
    (let [q1 (query/match-query :name "John")
          combined (query/combine-with-and [q1])]
      (is (= :bool (:type combined)))
      (is (= 1 (count (get-in combined [:clauses :must])))))))

(deftest combine-with-or-test
  (testing "combines two queries with OR"
    (let [q1 (query/match-query :name "John")
          q2 (query/match-query :name "Jane")
          combined (query/combine-with-or [q1 q2])]
      (is (= :bool (:type combined)))
      (is (= 2 (count (get-in combined [:clauses :should]))))))
  
  (testing "combines multiple queries with OR"
    (let [q1 (query/match-query :name "John")
          q2 (query/match-query :name "Jane")
          q3 (query/match-query :name "Jack")
          combined (query/combine-with-or [q1 q2 q3])]
      (is (= :bool (:type combined)))
      (is (= 3 (count (get-in combined [:clauses :should]))))))
  
  (testing "combines single query"
    (let [q1 (query/match-query :name "John")
          combined (query/combine-with-or [q1])]
      (is (= :bool (:type combined)))
      (is (= 1 (count (get-in combined [:clauses :should])))))))

;; ============================================================================
;; Query Validators
;; ============================================================================

(deftest valid-query?-test
  (testing "validates match query"
    (let [result (query/valid-query? (query/match-query :name "John"))]
      (is (true? (:valid? result)))
      (is (empty? (:errors result)))))
  
  (testing "validates phrase query"
    (let [result (query/valid-query? (query/phrase-query :bio "software engineer"))]
      (is (true? (:valid? result)))
      (is (empty? (:errors result)))))
  
  (testing "validates prefix query"
    (let [result (query/valid-query? (query/prefix-query :email "john@"))]
      (is (true? (:valid? result)))
      (is (empty? (:errors result)))))
  
  (testing "validates fuzzy query"
    (let [result (query/valid-query? (query/fuzzy-query :name "Jon" :auto))]
      (is (true? (:valid? result)))
      (is (empty? (:errors result)))))
  
  (testing "validates bool query"
    (let [q1 (query/match-query :name "John")
          q2 (query/match-query :role "admin")
          bool-q (query/bool-query {:must [q1 q2]})
          result (query/valid-query? bool-q)]
      (is (true? (:valid? result)))
      (is (empty? (:errors result)))))
  
  (testing "accepts unknown query type with all fields present"
    ;; The validator is permissive - it doesn't enforce a whitelist of query types
    ;; It only validates required fields for known types
    (let [result (query/valid-query? {:type :invalid :field :name :text "test"})]
      (is (true? (:valid? result)))
      (is (empty? (:errors result)))))
  
  (testing "rejects query without type"
    (let [result (query/valid-query? {:field :name :text "test"})]
      (is (false? (:valid? result)))
      (is (some #(re-find #"type" %) (:errors result)))))
  
  (testing "rejects nil query"
    (let [result (query/valid-query? nil)]
      (is (false? (:valid? result)))
      (is (seq (:errors result)))))
  
  (testing "rejects empty map"
    (let [result (query/valid-query? {})]
      (is (false? (:valid? result)))
      (is (seq (:errors result))))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(deftest parse-search-text-test
  (testing "parses single term"
    (is (= ["John"] (query/parse-search-text "John"))))
  
  (testing "parses multiple terms"
    (is (= ["John" "Doe"] (query/parse-search-text "John Doe"))))
  
  (testing "handles extra whitespace"
    (is (= ["John" "Doe"] (query/parse-search-text "  John   Doe  "))))
  
  (testing "handles empty string"
    (is (= [] (query/parse-search-text ""))))
  
  (testing "handles nil"
    (is (nil? (query/parse-search-text nil))))
  
  (testing "handles single whitespace"
    (is (= [] (query/parse-search-text " "))))
  
  (testing "handles multiple types of whitespace"
    (is (= ["John" "Doe"] (query/parse-search-text "John\tDoe\n")))))

(deftest query->map-test
  (testing "converts match query to map"
    (let [q (query/match-query :name "John")
          m (query/query->map q)]
      (is (map? m))
      (is (= :match (:type m)))
      (is (= :name (:field m)))
      (is (= "John" (:text m)))))
  
  (testing "converts query with modifiers to map"
    (let [q (-> (query/match-query :name "John")
                (query/with-limit 20)
                (query/with-offset 10))
          m (query/query->map q)]
      (is (= 20 (:limit m)))
      (is (= 10 (:offset m)))))
  
  (testing "handles nil query"
    ;; (into {} nil) returns {}, not nil
    (is (= {} (query/query->map nil)))))

(deftest default-limit-test
  (testing "returns default limit when not specified"
    (let [q (query/match-query :name "John")
          limit (query/default-limit q)]
      (is (number? limit))
      (is (pos? limit))))
  
  (testing "returns query limit when specified"
    (let [q (query/with-limit (query/match-query :name "John") 50)
          limit (query/default-limit q)]
      (is (= 50 limit)))))

(deftest default-offset-test
  (testing "returns default offset when not specified"
    (let [q (query/match-query :name "John")
          offset (query/default-offset q)]
      (is (number? offset))
      (is (>= offset 0))))
  
  (testing "returns query offset when specified"
    (let [q (query/with-offset (query/match-query :name "John") 100)
          offset (query/default-offset q)]
      (is (= 100 offset)))))

;; ============================================================================
;; Edge Cases
;; ============================================================================

(deftest edge-cases-test
  (testing "match query with special characters"
    (let [q (query/match-query :name "O'Brien")]
      (is (= "O'Brien" (:text q)))))
  
  (testing "phrase query with quotes"
    (let [q (query/phrase-query :bio "He said \"hello\"")]
      (is (= "He said \"hello\"" (:text q)))))
  
  (testing "prefix query with unicode"
    (let [q (query/prefix-query :name "José")]
      (is (= "José" (:text q)))))
  
  (testing "fuzzy query with numbers"
    (let [q (query/fuzzy-query :code "ABC123" 1)]
      (is (= "ABC123" (:text q)))))
  
  (testing "filter with various value types"
    (let [q (query/filter-query (query/match-query :name "John")
                                {:age 30
                                 :active true
                                 :role "admin"
                                 :score 4.5})]
      (is (= 30 (get-in q [:filters :age])))
      (is (true? (get-in q [:filters :active])))
      (is (= "admin" (get-in q [:filters :role])))
      (is (= 4.5 (get-in q [:filters :score])))))
  
  (testing "very long search term"
    (let [long-term (apply str (repeat 1000 "a"))
          q (query/match-query :description long-term)]
      (is (= long-term (:text q)))))
  
  (testing "bool query with deeply nested clauses"
    (let [q1 (query/match-query :name "John")
          q2 (query/match-query :role "admin")
          inner-bool (query/bool-query {:must [q1 q2]})
          outer-bool (query/bool-query {:must [inner-bool]})]
      (is (= :bool (:type outer-bool)))
      (is (= 1 (count (get-in outer-bool [:clauses :must]))))
      (is (= :bool (:type (first (get-in outer-bool [:clauses :must]))))))))
