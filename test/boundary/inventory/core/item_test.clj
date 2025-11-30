(ns boundary.inventory.core.item-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.inventory.core.item :as core]))

(deftest prepare-for-creation-test
  (testing "prepares item for creation"
    (let [data {:name "Test"}
          result (core/prepare-for-creation data)]
      (is (some? result)))))
