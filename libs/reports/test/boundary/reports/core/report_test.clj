(ns boundary.reports.core.report-test
  "Unit tests for pure core functions — no I/O, no adapters."
  (:require [boundary.reports.core.report :as sut]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.time LocalDate ZoneId]))

(def ^:private formatting-context
  {:zone-id (ZoneId/of "UTC")})

;; =============================================================================
;; Test fixture — clear registry between tests
;; =============================================================================

(use-fixtures :each
  (fn [f]
    (sut/clear-registry!)
    (f)
    (sut/clear-registry!)))

;; =============================================================================
;; format-cell
;; =============================================================================

(deftest format-cell-string-test
  ^:unit
  (testing "nil value returns empty string"
    (is (= "" (sut/format-cell* nil :string formatting-context))))
  (testing "non-nil value is coerced to string"
    (is (= "hello" (sut/format-cell* "hello" :string formatting-context)))
    (is (= "42"    (sut/format-cell* 42 :string formatting-context))))
  (testing "nil format treated as :string"
    (is (= "world" (sut/format-cell* "world" nil formatting-context))))
  (testing "legacy format-cell helper is deprecated"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"format-cell is deprecated"
         (sut/format-cell "world" nil)))))

(deftest format-cell-number-test
  ^:unit
  (testing "integer becomes double"
    (is (= 5.0 (sut/format-cell* 5 :number formatting-context))))
  (testing "nil becomes 0.0"
    (is (= 0.0 (sut/format-cell* nil :number formatting-context)))))

(deftest format-cell-currency-test
  ^:unit
  (testing "nil returns default"
    (is (= "€ 0,00" (sut/format-cell* nil :currency formatting-context))))
  (testing "positive value formatted with EUR prefix"
    (let [result (sut/format-cell* 1234.56 :currency formatting-context)]
      (is (str/starts-with? result "€ "))))
  (testing "zero formatted"
    (is (= "€ 0,00" (sut/format-cell* 0 :currency formatting-context)))))

(deftest format-cell-date-test
  ^:unit
  (testing "nil returns empty string"
    (is (= "" (sut/format-cell* nil :date formatting-context))))
  (testing "LocalDate returns ISO date string"
    (let [date   (LocalDate/of 2026 3 13)
          result (sut/format-cell* date :date formatting-context)]
      (is (= "2026-03-13" result))))
  (testing "java.util.Date returns ISO date string"
    (let [date (java.util.Date/from (java.time.Instant/parse "2026-03-13T00:00:00Z"))
          result (sut/format-cell* date :date formatting-context)]
      (is (re-matches #"2026-03-13" result)))))

;; =============================================================================
;; map-columns
;; =============================================================================

(deftest map-columns-test
  ^:unit
  (testing "maps record through column defs"
    (let [columns [{:key :name  :label "Name"   :format :string}
                   {:key :price :label "Price"  :format :currency}
                   {:key :qty   :label "Qty"    :format :number}]
          record  {:name "Widget" :price 9.99 :qty 3}]
      (is (= ["Widget" "€ 9,99" 3.0]
             (sut/map-columns* columns record formatting-context)))))
  (testing "missing key returns formatted nil"
    (let [columns [{:key :missing :label "X" :format :string}]
          record  {}]
      (is (= [""] (sut/map-columns* columns record formatting-context)))))
  (testing "legacy map-columns helper is deprecated"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"map-columns is deprecated"
         (sut/map-columns [] {})))))

;; =============================================================================
;; build-table-rows
;; =============================================================================

(deftest build-table-rows-test
  ^:unit
  (testing "returns hiccup tbody with one tr per record"
    (let [columns [{:key :name  :label "Name"}
                   {:key :value :label "Value" :format :number}]
          data    [{:name "A" :value 1}
                   {:name "B" :value 2}]
          result  (sut/build-table-rows* columns data formatting-context)]
      (is (= :tbody (first result)))
      (is (= 2 (count (rest result))))))
  (testing "empty data produces empty tbody"
    (let [result (sut/build-table-rows* [{:key :x :label "X"}] [] formatting-context)]
      (is (= [:tbody] result))))
  (testing "legacy build-table-rows helper is deprecated"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"build-table-rows is deprecated"
         (sut/build-table-rows [] [])))))

;; =============================================================================
;; defreport macro and registry
;; =============================================================================

;; Defined at top level so clj-kondo can resolve the symbol.
;; clear-registry! is called in the :each fixture so the registry stays clean.
(sut/defreport test-report-a
  {:id       :test-report-a
   :type     :pdf
   :template (fn [_] [:html [:body [:h1 "Test"]]])})

(deftest defreport-macro-test
  ^:unit
  (testing "defreport binds var to definition map"
    (is (= :test-report-a (:id test-report-a)))
    (is (= :pdf (:type test-report-a))))
  (testing "defreport registers definition in the registry"
    ;; Re-register since the :each fixture clears the registry before each test.
    (sut/register-report! test-report-a)
    (is (= test-report-a (sut/get-report :test-report-a))))
  (testing "list-reports includes registered id"
    (sut/register-report! test-report-a)
    (is (some #{:test-report-a} (sut/list-reports)))))

(deftest register-report-test
  ^:unit
  (testing "programmatic registration via register-report!"
    (let [defn {:id :prog-report :type :excel}]
      (sut/register-report! defn)
      (is (= defn (sut/get-report :prog-report))))))

;; =============================================================================
;; prepare-report
;; =============================================================================

(deftest prepare-report-valid-test
  ^:unit
  (testing "valid report definition returns :valid? true"
    (let [defn   {:id :valid-report :type :pdf}
          result (sut/prepare-report defn)]
      (is (true? (:valid? result)))
      (is (empty? (:errors result)))
      (is (= defn (:definition result))))))

(deftest prepare-report-invalid-test
  ^:unit
  (testing "missing :id and :type returns :valid? false"
    (let [result (sut/prepare-report {})]
      (is (false? (:valid? result)))
      (is (seq (:errors result))))))

;; =============================================================================
;; resolve-data
;; =============================================================================

(deftest resolve-data-test
  ^:unit
  (testing "returns nil when no :data-source"
    (is (nil? (sut/resolve-data {:id :r :type :pdf} {}))))
  (testing "calls :data-source fn with opts"
    (let [opts {:limit 5}
          defn {:id          :r
                :type        :pdf
                :data-source (fn [o] (assoc o :called? true))}]
      (is (= {:limit 5 :called? true}
             (sut/resolve-data defn opts))))))

;; =============================================================================
;; build-sections-hiccup
;; =============================================================================

(deftest build-sections-hiccup-test
  ^:unit
  (testing "produces :html root"
    (let [sections [{:type :header :content [:h1 "Report Title"]}
                    {:type    :table
                     :columns [{:key :name :label "Name"}]}
                    {:type :footer :content [:p "Page 1"]}]
          result   (sut/build-sections-hiccup* sections [{:name "Alice"}] formatting-context)]
      (is (= :html (first result)))))
  (testing "spacer section renders div.spacer"
    (let [sections [{:type :spacer}]
          result   (sut/build-sections-hiccup* sections [] formatting-context)
          ;; body is [:html [:head ...] [:body [:div.spacer]]]
          body     (last result)]
      (is (some #(= [:div.spacer] %) (rest body)))))
  (testing "legacy build-sections-hiccup helper is deprecated"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"build-sections-hiccup is deprecated"
         (sut/build-sections-hiccup [] [])))))
