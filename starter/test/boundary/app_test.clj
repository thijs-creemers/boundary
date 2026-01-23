(ns boundary.app-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.app :as app]
            [ring.mock.request :as mock]))

(deftest routes-respond
  (let [entities {:users {:label "Users"}
                  :product {:label "Products"}}]
    (testing "home route returns html"
      (let [handler (app/routes entities)
            response (handler (mock/request :get "/"))]
        (is (= 200 (:status response)))
        (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
        (is (re-find #"Boundary Starter" (slurp (:body response))))))
    (testing "admin route lists entities"
      (let [handler (app/routes entities)
            response (handler (mock/request :get "/admin"))
            body (slurp (:body response))]
        (is (= 200 (:status response)))
        (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
        (is (re-find #"Users" body))
        (is (re-find #"Products" body))))))