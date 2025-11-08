(ns boundary.user.cli-test
  "Unit tests for CLI with mocked service.

   Tests CLI argument parsing, dispatch, formatting, and error handling
   without requiring actual service implementation."
  {:cli true :unit true}
  (:require [boundary.user.ports :as ports]
            [boundary.user.shell.cli :as cli]
            [clojure.test :refer [deftest testing is]]
            [support.cli-helpers :as helpers])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Mock Service Implementation
;; =============================================================================

(defrecord MockUserService [state]
  ports/IUserService

  (register-user [_ user-data]
    (let [user (assoc user-data
                      :id (UUID/randomUUID)
                      :created-at (Instant/now)
                      :updated-at nil
                      :deleted-at nil)]
      (swap! state assoc-in [:users (:id user)] user)
      user))

  (get-user-by-id [_ user-id]
    (get-in @state [:users user-id]))

  (get-user-by-email [_ email tenant-id]
    (->> (vals (get @state :users {}))
         (filter #(and (= (:email %) email)
                       (= (:tenant-id %) tenant-id)))
         first))

  (list-users-by-tenant [_ tenant-id _options]
    (let [users (->> (vals (get @state :users {}))
                     (filter #(= (:tenant-id %) tenant-id)))]
      {:users users
       :total-count (count users)}))

  (update-user-profile [_ user-entity]
    (if (get-in @state [:users (:id user-entity)])
      (let [updated (assoc user-entity :updated-at (Instant/now))]
        (swap! state assoc-in [:users (:id user-entity)] updated)
        updated)
      (throw (ex-info "User not found" {:type :user-not-found}))))

  (deactivate-user [_ user-id]
    (if (get-in @state [:users user-id])
      (do
        (swap! state assoc-in [:users user-id :deleted-at] (Instant/now))
        true)
      (throw (ex-info "User not found" {:type :user-not-found}))))

  (permanently-delete-user [_ user-id]
    (if (get-in @state [:users user-id])
      (do
        (swap! state update :users dissoc user-id)
        true)
      (throw (ex-info "User not found" {:type :user-not-found}))))

  (authenticate-user [_ session-data]
    (let [now (Instant/now)
          session (assoc session-data
                         :id (UUID/randomUUID)
                         :session-token (str "token-" (UUID/randomUUID))
                         :created-at now
                         :expires-at (.plusSeconds now 3600)
                         :last-accessed-at nil
                         :revoked-at nil)]
      (swap! state assoc-in [:sessions (:session-token session)] session)
      session))

  (validate-session [_ session-token]
    (get-in @state [:sessions session-token]))

  (logout-user [_ session-token]
    (if (get-in @state [:sessions session-token])
      (do
        (swap! state assoc-in [:sessions session-token :revoked-at] (Instant/now))
        true)
      false))

  (logout-user-everywhere [_ user-id]
    (let [sessions (filter #(= (:user-id (val %)) user-id) (get @state :sessions {}))]
      (doseq [[token _] sessions]
        (swap! state assoc-in [:sessions token :revoked-at] (Instant/now)))
      (count sessions))))

(defn create-mock-service
  []
  (->MockUserService (atom {:users {} :sessions {}})))

;; =============================================================================
;; Help Text Tests
;; =============================================================================

(deftest test-root-help
  (testing "Empty args shows root help"
    (let [service (create-mock-service)
          result (helpers/capture-cli-output #(cli/run-cli! service []))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["Usage:" "user" "session"]))))

  (testing "Global --help flag shows root help"
    (let [service (create-mock-service)
          result (helpers/capture-cli-output #(cli/run-cli! service ["--help"]))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["Usage:" "Domains:"])))))

(deftest test-domain-help
  (testing "user --help shows user help"
    (let [service (create-mock-service)
          result (helpers/capture-cli-output #(cli/run-cli! service ["user" "--help"]))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["create" "list" "find" "update" "delete"]))))

  (testing "session --help shows session help"
    (let [service (create-mock-service)
          result (helpers/capture-cli-output #(cli/run-cli! service ["session" "--help"]))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["create" "invalidate" "list"])))))

;; =============================================================================
;; User Create Tests
;; =============================================================================

(deftest test-user-create-success-table
  (testing "Create user with valid args - table output"
    (let [service (create-mock-service)
          tenant-id (str (UUID/randomUUID))
          args ["user" "create"
                "--email" "john@example.com"
                "--name" "John Doe"
                "--role" "user"
                "--tenant-id" tenant-id]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (helpers/assert-table-contains (:out result)
                                     {:headers ["ID" "Email" "Name" "Role"]
                                      :values ["john@example.com" "John Doe" "user"]}))))

(deftest test-user-create-success-json
  (testing "Create user with valid args - JSON output"
    (let [service (create-mock-service)
          tenant-id (str (UUID/randomUUID))
          args ["user" "create"
                "--email" "jane@example.com"
                "--name" "Jane Doe"
                "--role" "admin"
                "--tenant-id" tenant-id
                "--format" "json"]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (is (helpers/json-output? (:out result)))
      (let [parsed (helpers/parse-json (:out result))]
        (is (= "jane@example.com" (:email parsed)))
        (is (= "Jane Doe" (:name parsed)))
        (is (= "admin" (:role parsed)))))))

(deftest test-user-create-missing-args
  (testing "Create user with missing required args"
    (let [service (create-mock-service)
          args ["user" "create" "--email" "test@example.com"]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 1 (:status result)))
      (is (helpers/error-contains? (:err result) "Error:")))))

(deftest test-user-create-invalid-email
  (testing "Create user with invalid email"
    (let [service (create-mock-service)
          tenant-id (str (UUID/randomUUID))
          args ["user" "create"
                "--email" "not-an-email"
                "--name" "Test"
                "--role" "user"
                "--tenant-id" tenant-id]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 1 (:status result)))
      (is (helpers/error-contains? (:err result) "Error:")))))

(deftest test-user-create-invalid-role
  (testing "Create user with invalid role"
    (let [service (create-mock-service)
          tenant-id (str (UUID/randomUUID))
          args ["user" "create"
                "--email" "test@example.com"
                "--name" "Test"
                "--role" "invalid-role"
                "--tenant-id" tenant-id]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 1 (:status result)))
      (is (helpers/error-contains? (:err result) "Error:")))))

(deftest test-user-create-with-active-flag
  (testing "Create user with active=false"
    (let [service (create-mock-service)
          tenant-id (str (UUID/randomUUID))
          args ["user" "create"
                "--email" "inactive@example.com"
                "--name" "Inactive User"
                "--role" "user"
                "--tenant-id" tenant-id
                "--active" "false"]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["inactive@example.com" "false"])))))

;; =============================================================================
;; User List Tests
;; =============================================================================

(deftest test-user-list-empty
  (testing "List users in empty tenant"
    (let [service (create-mock-service)
          tenant-id (str (UUID/randomUUID))
          args ["user" "list" "--tenant-id" tenant-id]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["No results found"])))))

(deftest test-user-list-with-users
  (testing "List users with results"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          ;; Create some users first
          _ (ports/register-user service {:email "user1@example.com"
                                        :name "User One"
                                        :role :user
                                        :tenant-id tenant-id
                                        :active true})
          _ (ports/register-user service {:email "user2@example.com"
                                        :name "User Two"
                                        :role :admin
                                        :tenant-id tenant-id
                                        :active true})
          args ["user" "list" "--tenant-id" (str tenant-id)]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (helpers/assert-table-contains (:out result)
                                     {:headers ["ID" "Email" "Name"]
                                      :values ["user1@example.com" "user2@example.com"]
                                      :row-count 2}))))

(deftest test-user-list-json
  (testing "List users with JSON output"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          _ (ports/register-user service {:email "test@example.com"
                                        :name "Test User"
                                        :role :user
                                        :tenant-id tenant-id
                                        :active true})
          args ["user" "list" "--tenant-id" (str tenant-id) "--format" "json"]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (is (helpers/json-output? (:out result)))
      (let [parsed (helpers/parse-json (:out result))]
        (is (= 1 (count (:users parsed))))
        (is (= 1 (:count parsed)))))))

(deftest test-user-list-with-filters
  (testing "List users with role filter"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          _ (ports/register-user service {:email "admin@example.com"
                                        :name "Admin"
                                        :role :admin
                                        :tenant-id tenant-id
                                        :active true})
          _ (ports/register-user service {:email "user@example.com"
                                        :name "User"
                                        :role :user
                                        :tenant-id tenant-id
                                        :active true})
          args ["user" "list" "--tenant-id" (str tenant-id) "--role" "admin"]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      ;; Should only show admin user
      (is (helpers/table-has-data? (:out result) ["admin@example.com"])))))

;; =============================================================================
;; User Find Tests
;; =============================================================================

(deftest test-user-find-by-id-success
  (testing "Find user by ID - success"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          user (ports/register-user service {:email "find@example.com"
                                           :name "Find Me"
                                           :role :user
                                           :tenant-id tenant-id
                                           :active true})
          args ["user" "find" "--id" (str (:id user))]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["find@example.com" "Find Me"])))))

(deftest test-user-find-by-id-not-found
  (testing "Find user by ID - not found"
    (let [service (create-mock-service)
          fake-id (UUID/randomUUID)
          args ["user" "find" "--id" (str fake-id)]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 1 (:status result)))
      (is (helpers/error-contains? (:err result) "not found")))))

(deftest test-user-find-by-email-success
  (testing "Find user by email - success"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          _ (ports/register-user service {:email "findme@example.com"
                                        :name "Find Me"
                                        :role :user
                                        :tenant-id tenant-id
                                        :active true})
          args ["user" "find" "--email" "findme@example.com" "--tenant-id" (str tenant-id)]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["findme@example.com"])))))

(deftest test-user-find-missing-args
  (testing "Find user without id or email"
    (let [service (create-mock-service)
          args ["user" "find"]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 1 (:status result)))
      (is (helpers/error-contains? (:err result) "required")))))

;; =============================================================================
;; User Update Tests
;; =============================================================================

(deftest test-user-update-success
  (testing "Update user - success"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          user (ports/register-user service {:email "update@example.com"
                                           :name "Original Name"
                                           :role :user
                                           :tenant-id tenant-id
                                           :active true})
          args ["user" "update" "--id" (str (:id user)) "--name" "Updated Name"]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["Updated Name"])))))

(deftest test-user-update-not-found
  (testing "Update user - not found"
    (let [service (create-mock-service)
          fake-id (UUID/randomUUID)
          args ["user" "update" "--id" (str fake-id) "--name" "New Name"]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 1 (:status result)))
      (is (helpers/error-contains? (:err result) "not found")))))

(deftest test-user-update-no-fields
  (testing "Update user without any fields"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          user (ports/register-user service {:email "test@example.com"
                                           :name "Test"
                                           :role :user
                                           :tenant-id tenant-id
                                           :active true})
          args ["user" "update" "--id" (str (:id user))]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 1 (:status result)))
      (is (helpers/error-contains? (:err result) "At least one")))))

;; =============================================================================
;; User Delete Tests
;; =============================================================================

(deftest test-user-delete-success
  (testing "Delete user - success"
    (let [service (create-mock-service)
          tenant-id (UUID/randomUUID)
          user (ports/register-user service {:email "delete@example.com"
                                           :name "Delete Me"
                                           :role :user
                                           :tenant-id tenant-id
                                           :active true})
          args ["user" "delete" "--id" (str (:id user))]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["deleted successfully"])))))

(deftest test-user-delete-not-found
  (testing "Delete user - not found"
    (let [service (create-mock-service)
          fake-id (UUID/randomUUID)
          args ["user" "delete" "--id" (str fake-id)]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 1 (:status result)))
      (is (helpers/error-contains? (:err result) "not found")))))

;; =============================================================================
;; Session Tests
;; =============================================================================

(deftest test-session-create-success
  (testing "Create session - success"
    (let [service (create-mock-service)
          user-id (UUID/randomUUID)
          tenant-id (UUID/randomUUID)
          args ["session" "create"
                "--user-id" (str user-id)
                "--tenant-id" (str tenant-id)]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["Token"])))))

(deftest test-session-invalidate-success
  (testing "Invalidate session - success"
    (let [service (create-mock-service)
          session (ports/authenticate-user service {:user-id (UUID/randomUUID)
                                                 :tenant-id (UUID/randomUUID)})
          args ["session" "invalidate" "--token" (:session-token session)]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 0 (:status result)))
      (is (helpers/table-has-data? (:out result) ["invalidated successfully"])))))

(deftest test-session-invalidate-not-found
  (testing "Invalidate session - not found"
    (let [service (create-mock-service)
          args ["session" "invalidate" "--token" "fake-token"]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 1 (:status result)))
      (is (helpers/error-contains? (:err result) "not found")))))

;; =============================================================================
;; Format Tests
;; =============================================================================

(deftest test-format-flag-validation
  (testing "Invalid format flag"
    (let [service (create-mock-service)
          args ["--format" "xml" "user" "list" "--tenant-id" (str (UUID/randomUUID))]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 1 (:status result)))
      (is (helpers/error-contains? (:err result) "Error:")))))

;; =============================================================================
;; Error Format Tests
;; =============================================================================

(deftest test-error-format-json
  (testing "Error output in JSON format"
    (let [service (create-mock-service)
          fake-id (UUID/randomUUID)
          args ["--format" "json" "user" "find" "--id" (str fake-id)]
          result (helpers/capture-cli-output #(cli/run-cli! service args))]
      (is (= 1 (:status result)))
      (is (helpers/json-output? (:err result)))
      (let [parsed (helpers/parse-json (:err result))]
        (is (contains? parsed :error))
        (is (= :user-not-found (keyword (get-in parsed [:error :type]))))))))
