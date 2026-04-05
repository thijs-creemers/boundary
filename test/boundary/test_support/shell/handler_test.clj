(ns boundary.test-support.shell.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.test-support.shell.handler :as h]))

(defn- post-request
  "Build a minimal ring request map for POST /test/reset.
   ring.mock.request is not on the test classpath in this repo, so we
   construct the map directly — the handler only reads :body-params/:params."
  [body-params]
  {:request-method :post
   :uri            "/test/reset"
   :body-params    body-params})

(deftest ^:unit reset-handler-returns-seeded-entities
  (testing "handler truncates and seeds, returns JSON with fixture IDs"
    (let [truncate-calls (atom 0)
          seed-calls     (atom 0)
          fake-deps {:truncate! (fn [_] (swap! truncate-calls inc))
                     :seed!     (fn [_] (swap! seed-calls inc)
                                  {:tenant {:id "T-1" :slug "acme"}
                                   :admin  {:id "A-1" :email "admin@acme.test"
                                            :password "Test-Pass-1234!"}
                                   :user   {:id "U-1" :email "user@acme.test"
                                            :password "Test-Pass-1234!"}})}
          handler (h/make-reset-handler fake-deps)
          resp    (handler (post-request {:seed "baseline"}))]
      (is (= 200 (:status resp)))
      (is (= 1 @truncate-calls))
      (is (= 1 @seed-calls))
      (is (= "acme" (-> resp :body :seeded :tenant :slug)))
      (is (= "admin@acme.test" (-> resp :body :seeded :admin :email)))
      (is (nil? (-> resp :body :seeded :admin :password-hash))
          "password-hash must never leak"))))

(deftest ^:unit reset-handler-empty-seed-skips-seeding
  (testing "with seed=empty, handler truncates but does not seed"
    (let [seed-calls (atom 0)
          fake-deps {:truncate! (fn [_] nil)
                     :seed!     (fn [_] (swap! seed-calls inc) {})}
          handler (h/make-reset-handler fake-deps)
          resp    (handler (post-request {:seed "empty"}))]
      (is (= 200 (:status resp)))
      (is (= 0 @seed-calls)))))
