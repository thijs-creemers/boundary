(ns boundary.user.cli-integration-test
  "Integration tests for CLI with real service and mock repositories.
   
   Tests end-to-end CLI execution with actual UserService but mocked persistence."
  {:cli true :integration true}
  (:require [boundary.user.service-test :as service-test]
            [boundary.user.shell.cli :as cli]
            [boundary.user.shell.service :as user-service]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [support.cli-helpers :as helpers])
  (:import [java.util UUID]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn create-test-service
  "Create a UserService with mock repositories for testing."
  []
  (let [{:keys [user-repository session-repository]} (service-test/create-mock-repositories)]
    (user-service/create-user-service user-repository session-repository)))

;; =============================================================================
;; End-to-End User Workflow Tests
;; =============================================================================

(deftest test-complete-user-workflow
  (testing "Complete user lifecycle: create, find, update, list, delete"
    (let [service (create-test-service)
          tenant-id (str (UUID/randomUUID))]
      
      ;; Step 1: Create user
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "create"
                                       "--email" "workflow@example.com"
                                       "--name" "Workflow Test"
                                       "--role" "user"
                                       "--tenant-id" tenant-id]))]
        (is (= 0 (:status result)))
        (is (helpers/table-has-data? (:out result) ["workflow@example.com"])))
      
      ;; Step 2: Find user by email
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "find"
                                       "--email" "workflow@example.com"
                                       "--tenant-id" tenant-id]))]
        (is (= 0 (:status result)))
        (is (helpers/table-has-data? (:out result) ["Workflow Test"])))
      
      ;; Step 3: List users (should have 1)
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "list"
                                       "--tenant-id" tenant-id]))]
        (is (= 0 (:status result)))
        (helpers/assert-table-contains (:out result) {:row-count 1})))))

(deftest test-user-duplication-prevention
  (testing "Duplicate email detection across CLI calls"
    (let [service (create-test-service)
          tenant-id (str (UUID/randomUUID))]
      
      ;; Create first user
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "create"
                                       "--email" "duplicate@example.com"
                                       "--name" "First User"
                                       "--role" "user"
                                       "--tenant-id" tenant-id]))]
        (is (= 0 (:status result))))
      
      ;; Attempt to create duplicate
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "create"
                                       "--email" "duplicate@example.com"
                                       "--name" "Second User"
                                       "--role" "admin"
                                       "--tenant-id" tenant-id]))]
        (is (= 1 (:status result)))
        (is (helpers/error-contains? (:err result) "already exists"))))))

(deftest test-session-workflow
  (testing "Session lifecycle: create, find, invalidate"
    (let [service (create-test-service)
          tenant-id (str (UUID/randomUUID))
          user-id (str (UUID/randomUUID))]
      
      ;; Create session
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["session" "create"
                                           "--user-id" user-id
                                           "--tenant-id" tenant-id]))
            output (:out result)]
        (is (= 0 (:status result)))
        ;; Extract session token from output (first column after header)
        (let [lines (str/split-lines output)
              data-line (when (>= (count lines) 4) (nth lines 3))
              token (when data-line
                     (-> data-line
                         (str/split #"\|")
                         second
                         str/trim))]
          (when token
            ;; Invalidate the session
            (let [result (helpers/capture-cli-output
                          #(cli/run-cli! service ["session" "invalidate"
                                                 "--token" token]))]
              (is (= 0 (:status result)))
              (is (helpers/table-has-data? (:out result) ["invalidated successfully"])))))))

;; =============================================================================
;; JSON Format Integration Tests
;; =============================================================================

(deftest test-json-format-end-to-end
  (testing "Complete workflow with JSON output"
    (let [service (create-test-service)
          tenant-id (str (UUID/randomUUID))]
      
      ;; Create with JSON
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "create"
                                       "--email" "json@example.com"
                                       "--name" "JSON User"
                                       "--role" "viewer"
                                       "--tenant-id" tenant-id
                                       "--format" "json"]))]
        (is (= 0 (:status result)))
        (is (helpers/json-output? (:out result)))
        (let [parsed (helpers/parse-json (:out result))]
          (is (= "json@example.com" (:email parsed)))
          (is (string? (:id parsed)))))
      
      ;; List with JSON
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "list"
                                       "--tenant-id" tenant-id
                                       "--format" "json"]))]
        (is (= 0 (:status result)))
        (is (helpers/json-output? (:out result)))
        (let [parsed (helpers/parse-json (:out result))]
          (is (= 1 (count (:users parsed))))
          (is (= 1 (:count parsed))))))))

;; =============================================================================
;; Error Handling Integration Tests
;; =============================================================================

(deftest test-cascading-operations
  (testing "Update and delete require existing user"
    (let [service (create-test-service)
          fake-id (str (UUID/randomUUID))]
      
      ;; Update non-existent user
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "update"
                                       "--id" fake-id
                                       "--name" "New Name"]))]
        (is (= 1 (:status result)))
        (is (helpers/error-contains? (:err result) "not found")))
      
      ;; Delete non-existent user
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "delete"
                                       "--id" fake-id]))]
        (is (= 1 (:status result)))
        (is (helpers/error-contains? (:err result) "not found"))))))

(deftest test-tenant-isolation
  (testing "Users are isolated by tenant"
    (let [service (create-test-service)
          tenant-a (str (UUID/randomUUID))
          tenant-b (str (UUID/randomUUID))]
      
      ;; Create user in tenant A
      (helpers/capture-cli-output
       #(cli/run-cli! service ["user" "create"
                          "--email" "tenant-a@example.com"
                          "--name" "Tenant A User"
                          "--role" "user"
                          "--tenant-id" tenant-a]))
      
      ;; Create user in tenant B
      (helpers/capture-cli-output
       #(cli/run-cli! service ["user" "create"
                          "--email" "tenant-b@example.com"
                          "--name" "Tenant B User"
                          "--role" "user"
                          "--tenant-id" tenant-b]))
      
      ;; List tenant A - should only see tenant A user
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "list"
                                       "--tenant-id" tenant-a]))]
        (is (= 0 (:status result)))
        (is (helpers/table-has-data? (:out result) ["tenant-a@example.com"]))
        (is (not (helpers/table-has-data? (:out result) ["tenant-b@example.com"]))))
      
      ;; List tenant B - should only see tenant B user
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "list"
                                       "--tenant-id" tenant-b]))]
        (is (= 0 (:status result)))
        (is (helpers/table-has-data? (:out result) ["tenant-b@example.com"]))
        (is (not (helpers/table-has-data? (:out result) ["tenant-a@example.com"])))))))

;; =============================================================================
;; Filter and Pagination Tests
;; =============================================================================

(deftest test-role-filtering
  (testing "List users filtered by role"
    (let [service (create-test-service)
          tenant-id (str (UUID/randomUUID))]
      
      ;; Create multiple users with different roles
      (doseq [[email role] [["admin1@example.com" "admin"]
                            ["admin2@example.com" "admin"]
                            ["user1@example.com" "user"]
                            ["viewer1@example.com" "viewer"]]]
        (helpers/capture-cli-output
         #(cli/run-cli! service ["user" "create"
                                 "--email" email
                                 "--name" email
                                 "--role" role
                                 "--tenant-id" tenant-id])))
      
      ;; List only admins
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "list"
                                            "--tenant-id" tenant-id
                                            "--role" "admin"]))]
        (is (= 0 (:status result)))
        (is (helpers/table-has-data? (:out result) ["admin1@example.com" "admin2@example.com"]))
        (is (not (helpers/table-has-data? (:out result) ["user1@example.com"]))))
      
      ;; List only users
      (let [result (helpers/capture-cli-output
                    #(cli/run-cli! service ["user" "list"
                                            "--tenant-id" tenant-id
                                            "--role" "user"]))]
        (is (= 0 (:status result)))
        (is (helpers/table-has-data? (:out result) ["user1@example.com"]))
        (is (not (helpers/table-has-data? (:out result) ["admin1@example.com"])))))))
