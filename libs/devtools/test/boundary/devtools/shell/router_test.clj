(ns boundary.devtools.shell.router-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.devtools.shell.router :as router]))

(use-fixtures :each
  (fn [f]
    (router/clear-all-state!)
    (f)
    (router/clear-all-state!)))

(deftest add-dynamic-route-test
  (testing "registers a dynamic route"
    (router/add-dynamic-route! :get "/api/test"
                               (fn [_] {:status 200 :body {:ok true}}))
    (let [routes (router/list-dynamic-routes)]
      (is (= 1 (count routes)))
      (is (= "/api/test" (:path (first routes))))
      (is (= :get (:method (first routes)))))))

(deftest remove-dynamic-route-test
  (testing "removes a dynamic route"
    (router/add-dynamic-route! :get "/api/test"
                               (fn [_] {:status 200 :body {:ok true}}))
    (router/remove-dynamic-route! :get "/api/test")
    (is (empty? (router/list-dynamic-routes)))))

(deftest add-tap-test
  (testing "registers a tap on a handler"
    (let [tap-fn (fn [ctx] ctx)]
      (router/add-tap! :create-user tap-fn)
      (let [taps (router/list-taps)]
        (is (= 1 (count taps)))
        (is (= :create-user (first taps)))))))

(deftest remove-tap-test
  (testing "removes a tap"
    (router/add-tap! :create-user (fn [ctx] ctx))
    (router/remove-tap! :create-user)
    (is (empty? (router/list-taps)))))

(deftest clear-dynamic-state-test
  (testing "clears dynamic routes but preserves taps"
    (router/add-dynamic-route! :get "/api/test"
                               (fn [_] {:status 200}))
    (router/add-tap! :create-user (fn [ctx] ctx))
    (router/clear-dynamic-state!)
    (is (empty? (router/list-dynamic-routes)))
    (is (= 1 (count (router/list-taps))) "taps should persist across reset")))

(deftest clear-all-state-test
  (testing "clears all dynamic routes and taps"
    (router/add-dynamic-route! :get "/api/test"
                               (fn [_] {:status 200}))
    (router/add-tap! :create-user (fn [ctx] ctx))
    (router/clear-all-state!)
    (is (empty? (router/list-dynamic-routes)))
    (is (empty? (router/list-taps)))))
