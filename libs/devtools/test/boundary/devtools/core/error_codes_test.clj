(ns boundary.devtools.core.error-codes-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [boundary.devtools.core.error-codes :as codes]))

(deftest ^:unit lookup-test
  (testing "finds known error codes"
    (let [result (codes/lookup "BND-101")]
      (is (some? result))
      (is (= "BND-101" (:code result)))
      (is (= :config (:category result)))
      (is (string? (:title result)))
      (is (string? (:description result)))))

  (testing "returns nil for unknown codes"
    (is (nil? (codes/lookup "BND-999")))))

(deftest ^:unit by-category-test
  (testing "returns all config errors"
    (let [results (codes/by-category :config)]
      (is (pos? (count results)))
      (is (every? #(= :config (:category %)) results))))

  (testing "returns all validation errors"
    (let [results (codes/by-category :validation)]
      (is (pos? (count results)))
      (is (every? #(= :validation (:category %)) results))))

  (testing "returns empty for unknown category"
    (is (empty? (codes/by-category :unknown)))))

(deftest ^:unit all-codes-test
  (testing "returns all codes sorted"
    (let [results (codes/all-codes)]
      (is (pos? (count results)))
      (is (= (map :code results) (sort (map :code results)))))))

(deftest ^:unit every-code-has-required-fields
  (testing "all codes have required fields"
    (doseq [error (codes/all-codes)]
      (is (string? (:code error)) (str "Missing :code in " error))
      (is (keyword? (:category error)) (str "Missing :category in " (:code error)))
      (is (string? (:title error)) (str "Missing :title in " (:code error)))
      (is (string? (:description error)) (str "Missing :description in " (:code error)))
      (is (string? (:fix error)) (str "Missing :fix in " (:code error))))))

(deftest ^:unit edn-catalog-in-sync
  (testing "error_catalog.edn key-set matches inline catalog"
    (let [edn-catalog (-> "boundary/devtools/core/error_catalog.edn"
                          io/resource
                          slurp
                          edn/read-string)
          clj-keys    (set (keys codes/catalog))
          edn-keys    (set (keys edn-catalog))]
      (is (= clj-keys edn-keys)
          (str "Key mismatch between error_codes.clj and error_catalog.edn.\n"
               "Only in clj: " (clojure.set/difference clj-keys edn-keys) "\n"
               "Only in edn: " (clojure.set/difference edn-keys clj-keys))))))
