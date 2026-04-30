(ns boundary.cli.catalogue-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.cli.catalogue :as cat]))

(deftest load-catalogue-test
  (testing "catalogue loads without error"
    (let [c (cat/load-catalogue)]
      (is (map? c))
      (is (contains? c :modules))
      (is (seq (:modules c)))))

  (testing "every module has required fields"
    (doseq [m (:modules (cat/load-catalogue))]
      (is (string? (:name m))       (str "missing :name in " m))
      (is (string? (:description m)) (str "missing :description in " m))
      (is (keyword? (:category m))  (str "missing :category in " m))
      (is (string? (:version m))    (str "missing :version in " m))
      (is (string? (:add-command m)) (str "missing :add-command in " m))
      (is (string? (:config-snippet m)) (str "missing :config-snippet in " m))
      (is (string? (:test-config-snippet m)) (str "missing :test-config-snippet in " m))
      (is (string? (:docs-url m))   (str "missing :docs-url in " m)))))

(deftest find-module-test
  (testing "finds a module by name"
    (let [m (cat/find-module "payments")]
      (is (= "payments" (:name m)))))

  (testing "returns nil for unknown module"
    (is (nil? (cat/find-module "does-not-exist"))))

  (testing "core modules are present"
    (doseq [core-name ["core" "observability" "platform" "user"]]
      (is (cat/find-module core-name) (str "core module missing: " core-name))))

  (testing "optional modules include payments and storage"
    (is (cat/find-module "payments"))
    (is (cat/find-module "storage"))))

(deftest optional-modules-test
  (testing "optional-modules returns only :optional category"
    (let [opts (cat/optional-modules)]
      (is (every? #(= :optional (:category %)) opts))
      (is (seq opts)))))
