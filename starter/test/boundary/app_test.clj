(ns boundary.app-test
  (:require [clojure.test :refer [deftest is testing]]))

(load-file "src/boundary/app.clj")

(deftest routes-respond
  (let [entities {:users {:label "Users"}
                  :product {:label "Products"}}
        routes-fn (requiring-resolve 'boundary.app/routes)]
    (testing "home route returns html"
      (let [handler (routes-fn entities)
            response (handler {:request-method :get
                               :uri "/"})]
        (is (= 200 (:status response)))
        (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
        (is (re-find #"Boundary Starter" (slurp (:body response))))))
    (testing "admin route lists entities"
      (let [handler (routes-fn entities)
            response (handler {:request-method :get
                               :uri "/admin"})
            body (slurp (:body response))]
        (is (= 200 (:status response)))
        (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
        (is (re-find #"Users" body))
        (is (re-find #"Products" body))))))
