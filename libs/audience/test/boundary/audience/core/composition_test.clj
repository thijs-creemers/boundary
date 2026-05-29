(ns boundary.audience.core.composition-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.audience.core.composition :as comp]))

(deftest and-composition
  (testing "AND intersects user ID sets"
    (is (= #{2 3}
           (comp/compose-results
            {:and [{:user-ids #{1 2 3}} {:user-ids #{2 3 4}}]})))))

(deftest or-composition
  (testing "OR unions user ID sets"
    (is (= #{1 2 3 4}
           (comp/compose-results
            {:or [{:user-ids #{1 2}} {:user-ids #{3 4}}]})))))

(deftest not-composition
  (testing "NOT excludes user IDs from universe"
    (is (= #{1 4}
           (comp/compose-results
            {:and [{:user-ids #{1 2 3 4}}
                   {:not {:user-ids #{2 3}}}]})))))

(deftest nested-composition
  (testing "nested AND/OR/NOT"
    (is (= #{3}
           (comp/compose-results
            {:and [{:or [{:user-ids #{1 2 3}} {:user-ids #{3 4 5}}]}
                   {:not {:user-ids #{1 2 4 5}}}]})))))

(deftest resolve-refs
  (testing "segment refs resolved via lookup fn"
    (let [lookup (fn [id]
                   (case id
                     :seg-a {:user-ids #{1 2 3}}
                     :seg-b {:user-ids #{2 3 4}}
                     nil))]
      (is (= #{2 3}
             (comp/resolve-and-compose
              {:and [{:ref :seg-a} {:ref :seg-b}]}
              lookup))))))

(deftest circular-ref-detection
  (testing "circular references throw"
    (let [lookup (fn [id]
                   (case id
                     :seg-a {:compose {:and [{:ref :seg-b}]}}
                     :seg-b {:compose {:and [{:ref :seg-a}]}}
                     nil))]
      (is (thrown? Exception
                   (comp/resolve-and-compose
                    {:and [{:ref :seg-a}]}
                    lookup))))))
