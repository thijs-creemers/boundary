(ns boundary.devtools.core.prototype-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.core.prototype :as prototype]))

(def sample-spec
  {:fields {:customer [:string {:min 1}]
            :amount   [:decimal {:min 0}]
            :status   [:enum [:draft :sent :paid]]
            :due-date :date}
   :endpoints [:crud :list]})

(deftest build-scaffold-context-test
  (testing "maps prototype spec to scaffolder-compatible context"
    (let [ctx (prototype/build-scaffold-context "invoice" sample-spec)]
      (is (= "invoice" (:module-name ctx)))
      (is (vector? (:entities ctx)))
      (is (= 1 (count (:entities ctx))))
      (let [entity (first (:entities ctx))]
        (is (= "invoice" (:entity-name entity)))
        (is (= 4 (count (:fields entity))))
        (let [first-field (first (:fields entity))]
          (is (contains? first-field :field-name-kebab))
          (is (contains? first-field :malli-type)))))))

(deftest endpoints-to-generators-test
  (testing "maps endpoint keywords to generator function names"
    (let [generators (prototype/endpoints-to-generators [:crud :list :search])]
      (is (contains? (set generators) :schema))
      (is (contains? (set generators) :ports))
      (is (contains? (set generators) :core))
      (is (contains? (set generators) :persistence))
      (is (contains? (set generators) :service))
      (is (contains? (set generators) :http)))))

(deftest build-migration-spec-test
  (testing "converts field spec to migration column definitions"
    (let [columns (prototype/build-migration-spec "invoice" (:fields sample-spec))]
      (is (vector? columns))
      (is (>= (count columns) 4))
      (is (some #(= :id (:name %)) columns)))))

(deftest field-type-mapping-test
  (testing "maps Malli types to SQL types"
    (is (= "VARCHAR(255)" (prototype/malli->sql-type [:string {:min 1}])))
    (is (= "DECIMAL" (prototype/malli->sql-type [:decimal {:min 0}])))
    (is (= "DATE" (prototype/malli->sql-type :date)))
    (is (= "VARCHAR(50)" (prototype/malli->sql-type [:enum [:draft :sent :paid]])))))
