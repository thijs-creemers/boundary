(ns boundary.audience.core.audience-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.audience.core.audience :as audience]))

(use-fixtures :each
  (fn [f]
    (audience/clear-registry!)
    (f)))

(deftest ^:unit defaudience-registers-definition
  (testing "defaudience creates var and registers by :id"
    (audience/defaudience test-segment
      {:id      :test-segment
       :label   "Test"
       :filters [{:type :demographics :field :plan :op :eq :value "free"}]})
    (is (= :test-segment (:id test-segment)))
    (is (= "Test" (:label (audience/get-audience :test-segment))))))

(deftest ^:unit registry-operations
  (testing "list-audiences returns registered ids"
    (audience/register-audience! {:id :seg-a :label "A" :filters []})
    (audience/register-audience! {:id :seg-b :label "B" :filters []})
    (is (= #{:seg-a :seg-b} (set (audience/list-audiences)))))

  (testing "get-audience returns nil for unknown id"
    (is (nil? (audience/get-audience :nonexistent))))

  (testing "clear-registry! empties registry"
    (audience/register-audience! {:id :seg-c :label "C" :filters []})
    (audience/clear-registry!)
    (is (empty? (audience/list-audiences)))))
