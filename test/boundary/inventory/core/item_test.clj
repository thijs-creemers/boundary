(ns boundary.inventory.core.item-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.inventory.core.item :as core]))

(deftest prepare-new-item-test
  (testing "prepares item for creation"
    (let [data {:name "Test"}
          current-time (java.time.Instant/now)
          result (core/prepare-new-item data current-time)]
      (is (some? result)))))
