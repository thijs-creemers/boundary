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

;; =============================================================================
;; add-field command tests
;; =============================================================================

(deftest add-field-test
  (testing "generates migration for adding a field"
    (let [fs-adapter (fs/create-file-system-adapter test-output-dir)
          svc (service/create-scaffolder-service fs-adapter)
          
          request {:module-name "product"
                   :entity "Product"
                   :field {:name :description
                           :type :text
                           :required false
                           :unique false}
                   :dry-run false}
          
          result (ports/add-field svc request)]
      
      (is (true? (:success result)))
      (is (= "product" (:module-name result)))
      (is (= 2 (count (:files result))))
      
      ;; Check migration file was created
      (let [migration-file (first (filter #(str/starts-with? (:path %) "migrations/") 
                                          (:files result)))]
        (is (some? migration-file))
        (is (str/includes? (:path migration-file) "add_description_to_products.sql"))
        (is (str/includes? (:content migration-file) "ALTER TABLE"))
        (is (str/includes? (:content migration-file) "ADD COLUMN description"))))))

(deftest add-field-dry-run-test
  (testing "dry run does not write migration file"
    (let [fs-adapter (fs/create-file-system-adapter test-output-dir)
          svc (service/create-scaffolder-service fs-adapter)
          
          request {:module-name "product"
                   :entity "Product"
                   :field {:name :sku
                           :type :string
                           :required true
                           :unique true}
                   :dry-run true}
          
          result (ports/add-field svc request)]
      
      (is (true? (:success result)))
      (is (some #(str/includes? % "Dry run") (:warnings result))))))

;; =============================================================================
;; add-endpoint command tests
;; =============================================================================

(deftest add-endpoint-test
  (testing "generates endpoint instructions"
    (let [fs-adapter (fs/create-file-system-adapter test-output-dir)
          svc (service/create-scaffolder-service fs-adapter)
          
          request {:module-name "product"
                   :path "/products/export"
                   :method :get
                   :handler-name "export-products"
                   :dry-run false}
          
          result (ports/add-endpoint svc request)]
      
      (is (true? (:success result)))
      (is (= "product" (:module-name result)))
      (is (= 1 (count (:files result))))
      
      ;; Check instructions content
      (let [http-file (first (:files result))]
        (is (str/ends-with? (:path http-file) "http.clj"))
        (is (str/includes? (:content http-file) "/products/export"))
        (is (str/includes? (:content http-file) ":get"))
        (is (str/includes? (:content http-file) "export-products"))))))

;; =============================================================================
;; add-adapter command tests
;; =============================================================================

(deftest add-adapter-test
  (testing "generates adapter implementation file"
    (let [fs-adapter (fs/create-file-system-adapter test-output-dir)
          svc (service/create-scaffolder-service fs-adapter)
          
          request {:module-name "notifications"
                   :port "INotificationSender"
                   :adapter-name "slack"
                   :methods [{:name "send-notification" :args ["user-id" "message"]}
                             {:name "send-bulk" :args ["user-ids" "message"]}]
                   :dry-run false}
          
          result (ports/add-adapter svc request)]
      
      (is (true? (:success result)))
      (is (= "notifications" (:module-name result)))
      (is (= 1 (count (:files result))))
      
      ;; Check adapter file was created
      (let [adapter-file (first (:files result))]
        (is (str/ends-with? (:path adapter-file) "slack.clj"))
        (is (str/includes? (:path adapter-file) "adapters/"))
        (is (fs-ports/file-exists? fs-adapter (:path adapter-file)))
        
        ;; Check file content
        (let [content (fs-ports/read-file fs-adapter (:path adapter-file))]
          (is (str/includes? content "defrecord Slack")) ;; Record name is based on adapter-name
          (is (str/includes? content "INotificationSender"))
          (is (str/includes? content "send-notification"))
          (is (str/includes? content "send-bulk")))))))

(deftest add-adapter-dry-run-test
  (testing "dry run does not write adapter file"
    (let [fs-adapter (fs/create-file-system-adapter test-output-dir)
          svc (service/create-scaffolder-service fs-adapter)
          
          request {:module-name "storage"
                   :port "IFileStorage"
                   :adapter-name "s3"
                   :methods [{:name "store-file" :args ["path" "content"]}]
                   :dry-run true}
          
          result (ports/add-adapter svc request)]
      
      (is (true? (:success result)))
      (is (some #(str/includes? % "Dry run") (:warnings result)))
      (is (not (fs-ports/file-exists? fs-adapter 
                                       "src/boundary/storage/shell/adapters/s3.clj"))))))
