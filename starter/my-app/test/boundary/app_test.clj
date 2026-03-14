(ns boundary.app-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.app :as app]))

(deftest hello-world-test
  (testing "hello-world returns 200 OK"
    (let [response (app/hello-world)]
      (is (= 200 (:status response)))
      (is (= "Hello, Boundary!" (:body response))))))
