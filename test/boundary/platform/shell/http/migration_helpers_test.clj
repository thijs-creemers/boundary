(ns boundary.platform.shell.http.migration-helpers-test
  "Tests for Reitit to normalized route conversion helpers."
  (:require [boundary.platform.shell.http.migration-helpers :as helpers]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]))

(deftest reitit-simple-route-conversion-test
  (testing "Converts simple Reitit route to normalized format"
    (let [reitit-routes [["/users" {:get {:handler (fn [_] {:status 200})
                                          :summary "List users"}}]]
          result (helpers/reitit-routes->normalized reitit-routes)]
      
      (is (= 1 (count result)))
      (is (= "/users" (:path (first result))))
      (is (map? (:methods (first result))))
      (is (fn? (get-in result [0 :methods :get :handler])))
      (is (= "List users" (get-in result [0 :methods :get :summary]))))))

(deftest reitit-nested-route-conversion-test
  (testing "Converts nested Reitit routes to normalized format"
    (let [reitit-routes [["/users"
                         {:middleware [:auth]
                          :get {:handler (fn [_] {:status 200})}
                          :post {:handler (fn [_] {:status 201})}}
                         ["/:id"
                          {:get {:handler (fn [_] {:status 200})}}]]]
          result (helpers/reitit-routes->normalized reitit-routes)]
      
      (is (= 1 (count result)))
      
      (let [parent (first result)]
        ;; Check parent route
        (is (= "/users" (:path parent)))
        (is (contains? (:methods parent) :get))
        (is (contains? (:methods parent) :post))
        (is (= [:auth] (:middleware (:meta parent))))
        
        ;; Check child route
        (is (= 1 (count (:children parent))))
        (let [child (first (:children parent))]
          (is (= "/:id" (:path child)))
          (is (contains? (:methods child) :get)))))))

(deftest reitit-multiple-routes-conversion-test
  (testing "Converts multiple Reitit routes to normalized format"
    (let [reitit-routes [["/users" {:get {:handler (fn [_] {})}}]
                        ["/items" {:post {:handler (fn [_] {})}}]
                        ["/products" {:delete {:handler (fn [_] {})}}]]
          result (helpers/reitit-routes->normalized reitit-routes)]
      
      (is (= 3 (count result)))
      (is (= "/users" (:path (nth result 0))))
      (is (= "/items" (:path (nth result 1))))
      (is (= "/products" (:path (nth result 2)))))))

(deftest validate-normalized-routes-test
  (testing "Validates correct normalized routes"
    (let [routes [{:path "/users"
                   :methods {:get {:handler (fn [_] {})}}}]
          result (helpers/validate-normalized-routes routes)]
      
      (is (:valid? result))
      (is (empty? (:errors result)))))
  
  (testing "Detects missing path"
    (let [routes [{:methods {:get {:handler (fn [_] {})}}}]
          result (helpers/validate-normalized-routes routes)]
      
      (is (not (:valid? result)))
      (is (some #(str/includes? % "missing :path") (:errors result)))))
  
  (testing "Detects invalid path format"
    (let [routes [{:path "users"  ; Missing leading /
                   :methods {:get {:handler (fn [_] {})}}}]
          result (helpers/validate-normalized-routes routes)]
      
      (is (not (:valid? result)))
      (is (some #(str/includes? % "must start with /") (:errors result)))))
  
  (testing "Detects missing handler"
    (let [routes [{:path "/users"
                   :methods {:get {:summary "Missing handler"}}}]
          result (helpers/validate-normalized-routes routes)]
      
      (is (not (:valid? result)))
      (is (some #(str/includes? % "missing :handler") (:errors result))))))

(deftest reitit-metadata-preservation-test
  (testing "Preserves non-method metadata during conversion"
    (let [reitit-routes [["/users"
                         {:middleware [:auth :logging]
                          :name ::users
                          :custom-key "custom-value"
                          :get {:handler (fn [_] {})}}]]
          result (helpers/reitit-routes->normalized reitit-routes)
          meta (:meta (first result))]
      
      (is (= [:auth :logging] (:middleware meta)))
      (is (= ::users (:name meta)))
      (is (= "custom-value" (:custom-key meta))))))
