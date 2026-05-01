(ns boundary.cli.catalogue-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
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
      (is (string? (:docs-url m))   (str "missing :docs-url in " m))))

  (testing "every module :clojars is a symbol"
    (doseq [m (:modules (cat/load-catalogue))]
      (is (symbol? (:clojars m)) (str ":clojars is not a symbol in " (:name m))))))

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

(deftest core-modules-test
  (testing "core-modules returns only :core category"
    (let [cores (cat/core-modules)]
      (is (every? #(= :core (:category %)) cores))
      (is (seq cores))))

  (testing "core-modules includes all 4 required core modules"
    (let [core-names (set (map :name (cat/core-modules)))]
      (is (contains? core-names "core"))
      (is (contains? core-names "observability"))
      (is (contains? core-names "platform"))
      (is (contains? core-names "user")))))

(deftest scripts-deploy-lib-registry-drift-test
  (testing "scripts/deploy.clj all-libs contains i18n and payments"
    (let [deploy-script (io/file (System/getProperty "user.dir") "scripts/deploy.clj")]
      (when (.exists deploy-script)
        (let [content (slurp deploy-script)]
          (is (str/includes? content "\"i18n\"")    "i18n missing from scripts/deploy.clj all-libs — drift from boundary.tools.deploy/all-libs")
          (is (str/includes? content "\"payments\"") "payments missing from scripts/deploy.clj all-libs — drift from boundary.tools.deploy/all-libs"))))))
