(ns boundary.inventory.shell.service-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.inventory.shell.service :as service]
            [boundary.inventory.ports :as ports]))

(deftest create-item-test
  (testing "creates item via service"
    (let [mock-repo (reify ports/IItemRepository
                      (create [_ entity] entity))
          svc (service/create-service mock-repo)
          result (ports/create-item svc {:name "Test"})]
      (is (some? result)))))
