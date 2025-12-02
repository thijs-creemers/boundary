(ns boundary.scaffolder.shell.service-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [boundary.scaffolder.shell.service :as service]
            [boundary.platform.shell.adapters.filesystem.core :as fs]
            [boundary.platform.shell.adapters.filesystem.protocols :as fs-ports]
            [boundary.scaffolder.ports :as ports]
            [clojure.java.io :as io]))

(def test-output-dir ".test-output")

(defn cleanup-test-output
  "Remove test output directory."
  []
  (when (.exists (io/file test-output-dir))
    (let [dir (io/file test-output-dir)]
      (doseq [f (file-seq dir)]
        (when (.isFile f)
          (io/delete-file f)))
      (doseq [d (reverse (file-seq dir))]
        (when (.isDirectory d)
          (io/delete-file d))))))

(defn test-fixture [f]
  (cleanup-test-output)
  (f)
  (cleanup-test-output))

(use-fixtures :each test-fixture)

(deftest generate-customer-module-test
  (testing "generates complete customer module"
    (let [fs-adapter (fs/create-file-system-adapter test-output-dir)
          svc (service/create-scaffolder-service fs-adapter)

          request {:module-name "customer"
                   :entities [{:name "Customer"
                               :fields [{:name :name
                                         :type :string
                                         :required true}
                                        {:name :email
                                         :type :email
                                         :required true
                                         :unique true}
                                        {:name :phone
                                         :type :string
                                         :required false}
                                        {:name :active
                                         :type :boolean
                                         :required true
                                         :default true}]}]
                   :interfaces {:http true :cli true :web true}
                   :features {:audit true :pagination true}
                   :dry-run false}

          result (ports/generate-module svc request)]

      ;; Check result structure
      (is (true? (:success result)))
      (is (= "customer" (:module-name result)))
      (is (= 12 (count (:files result))))

      ;; Check files were created
      (is (fs-ports/file-exists? fs-adapter "src/boundary/customer/schema.clj"))
      (is (fs-ports/file-exists? fs-adapter "src/boundary/customer/ports.clj"))
      (is (fs-ports/file-exists? fs-adapter "src/boundary/customer/core/customer.clj"))
      (is (fs-ports/file-exists? fs-adapter "src/boundary/customer/core/ui.clj"))
      (is (fs-ports/file-exists? fs-adapter "src/boundary/customer/shell/service.clj"))
      (is (fs-ports/file-exists? fs-adapter "src/boundary/customer/shell/persistence.clj"))
      (is (fs-ports/file-exists? fs-adapter "src/boundary/customer/shell/http.clj"))
      (is (fs-ports/file-exists? fs-adapter "src/boundary/customer/shell/web_handlers.clj"))
      (is (fs-ports/file-exists? fs-adapter "migrations/005_create_customers.sql"))

      ;; Check schema file content
      (let [schema-content (fs-ports/read-file fs-adapter "src/boundary/customer/schema.clj")]
        (is (some? schema-content))
        (is (str/includes? schema-content "(ns boundary.customer.schema"))
        (is (str/includes? schema-content "(def Customer"))
        (is (str/includes? schema-content "(def CreateCustomerRequest"))
        (is (str/includes? schema-content "(def UpdateCustomerRequest")))

      ;; Check ports file content
      (let [ports-content (fs-ports/read-file fs-adapter "src/boundary/customer/ports.clj")]
        (is (some? ports-content))
        (is (str/includes? ports-content "(defprotocol ICustomerRepository"))
        (is (str/includes? ports-content "(defprotocol ICustomerService")))

      ;; Check core file content
      (let [core-content (fs-ports/read-file fs-adapter "src/boundary/customer/core/customer.clj")]
        (is (some? core-content))
        (is (str/includes? core-content "(defn prepare-new-customer"))
        (is (str/includes? core-content "(defn apply-customer-update")))

      ;; Check migration file content
      (let [migration-content (fs-ports/read-file fs-adapter "migrations/005_create_customers.sql")]
        (is (some? migration-content))
        (is (str/includes? migration-content "CREATE TABLE IF NOT EXISTS customers"))
        (is (str/includes? migration-content "name TEXT NOT NULL"))
        (is (str/includes? migration-content "email TEXT NOT NULL UNIQUE"))
        (is (str/includes? migration-content "phone TEXT"))
        (is (str/includes? migration-content "active BOOLEAN NOT NULL"))))))

(deftest generate-module-dry-run-test
  (testing "dry run does not write files"
    (let [fs-adapter (fs/create-file-system-adapter test-output-dir)
          svc (service/create-scaffolder-service fs-adapter)

          request {:module-name "test-module"
                   :entities [{:name "TestEntity"
                               :fields [{:name :name :type :string}]}]
                   :interfaces {:http true}
                   :dry-run true}

          result (ports/generate-module svc request)]

      ;; Check result
      (is (true? (:success result)))
      (is (= 12 (count (:files result))))
      (is (some #(str/includes? % "Dry run") (:warnings result)))

      ;; Verify no files were written
      (is (not (fs-ports/file-exists? fs-adapter "src/boundary/test-module/schema.clj"))))))

(deftest generate-module-validation-test
  (testing "validates request schema"
    (let [fs-adapter (fs/create-file-system-adapter test-output-dir)
          svc (service/create-scaffolder-service fs-adapter)

          ;; Invalid request - missing required fields
          invalid-request {:module-name "test"}

          result (ports/generate-module svc invalid-request)]

      ;; Check error result
      (is (false? (:success result)))
      (is (seq (:errors result))))))
