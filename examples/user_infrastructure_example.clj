(ns boundary.examples.user-infrastructure-example
  "Example demonstrating the new user infrastructure architecture.
   
   This example shows:
   1. How to create repositories using the new infrastructure layer
   2. How to use the database-agnostic service layer
   3. How to compose and test business operations
   
   Run this example to see the new architecture in action."
  (:require [boundary.user.shell.persistence :as user-persistence]
            [boundary.user.shell.service :as user-service]
            [boundary.platform.shell.adapters.database.factory :as db-factory]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

;; =============================================================================
;; Setup - Database Context and Repositories
;; =============================================================================

(defn create-example-context
  "Create a sample database context for demonstration.
   In real usage, load this from your application configuration."
  []
  ;; Example configuration - adjust for your database
  (let [config {:database-url "jdbc:sqlite:example.db"
                :adapter :sqlite}]
    (db-factory/create-database-context config)))

(defn setup-infrastructure
  "Set up the infrastructure layer - repositories and services."
  []
  (let [ctx (create-example-context)]

    ;; Initialize database schema
    (log/info "Initializing user database schema...")
    (user-db/initialize-user-schema! ctx)

    ;; Create repositories
    (log/info "Creating repository instances...")
    (let [user-repo (user-db/create-user-repository ctx)
          session-repo (user-db/create-session-repository ctx)]

      ;; Create database-agnostic service
      (log/info "Creating user service...")
      (let [service (user-service/create-user-service user-repo session-repo)]

        {:context ctx
         :user-repository user-repo
         :session-repository session-repo
         :user-service service}))))

;; =============================================================================
;; Example Operations
;; =============================================================================

(defn example-user-operations
  "Demonstrate user operations using the new infrastructure."
  [{:keys [user-service]}]

  (let [tenant-id (UUID/randomUUID)]

    (log/info "=== User Operations Example ===")

    ;; 1. Create a user using the service layer
    (log/info "Creating user via service...")
    (let [user-data {:email "alice@example.com"
                     :password "secure-password-123"
                     :role :user
                     :active true
                     :tenant-id tenant-id}]

      (try
        (let [created-user (user-service/create-user user-service user-data)]
          (log/info "User created successfully:" {:user-id (:id created-user)
                                                  :email (:email created-user)})

          ;; 2. Authenticate the user
          (log/info "Authenticating user...")
          (let [auth-result (user-service/authenticate user-service
                                                       (:email created-user)
                                                       "secure-password-123")]
            (if auth-result
              (log/info "Authentication successful:" {:session-token (:session-token auth-result)})
              (log/warn "Authentication failed")))

          ;; 3. Find user by email
          (log/info "Finding user by email...")
          (when-let [found-user (user-service/find-user-by-email user-service
                                                                 (:email created-user)
                                                                 tenant-id)]
            (log/info "User found:" {:id (:id found-user) :email (:email found-user)}))

          created-user)

        (catch Exception e
          (log/error "Error in user operations:" {:error (.getMessage e)}))))))

(defn example-repository-operations
  "Demonstrate direct repository usage (less common, but still available)."
  [{:keys [user-repository]}]

  (let [tenant-id (UUID/randomUUID)]

    (log/info "=== Repository Operations Example ===")

    ;; Direct repository usage (more low-level)
    (let [user-entity {:email "bob@example.com"
                       :password-hash "hashed-password"  ; Pre-hashed
                       :role :admin
                       :active true
                       :tenant-id tenant-id}]

      (try
        ;; Create user directly via repository
        (let [created-user (.create-user user-repository user-entity)]
          (log/info "User created via repository:" {:user-id (:id created-user)})

          ;; Query users by tenant
          (let [users-result (.find-users-by-tenant user-repository tenant-id {})]
            (log/info "Users in tenant:" {:count (:total-count users-result)
                                          :users (map :email (:users users-result))}))

          created-user)

        (catch Exception e
          (log/error "Error in repository operations:" {:error (.getMessage e)}))))))

;; =============================================================================
;; Backward Compatibility Example
;; =============================================================================

(defn example-deprecated-usage
  "Example showing that deprecated namespaces still work (with warnings)."
  []
  (log/info "=== Backward Compatibility Example ===")

  ;; This will work but show deprecation warnings
  (try
    (require '[boundary.platform.shell.adapters.database.user :as old-db-user])

    (let [ctx (create-example-context)]
      ;; Old-style usage (deprecated but functional)
      (let [old-user-repo ((resolve 'old-db-user/create-user-repository) ctx)]
        (log/info "Deprecated namespace still works:" {:type (type old-user-repo)})))

    (catch Exception e
      (log/error "Error using deprecated namespace:" {:error (.getMessage e)}))))

;; =============================================================================
;; Testing Example
;; =============================================================================

(defn example-testing-with-mocks
  "Example showing how the new architecture enables better testing."
  []
  (log/info "=== Testing Example (with mocks) ===")

  ;; Mock repository for testing
  (let [mock-user-repo (reify boundary.user.ports/IUserRepository
                         (find-user-by-email [_ email tenant-id]
                           (when (= email "test@example.com")
                             {:id (UUID/randomUUID)
                              :email email
                              :password-hash "mock-hash"
                              :role :user
                              :active true
                              :tenant-id tenant-id})))

        mock-session-repo (reify boundary.user.ports/IUserSessionRepository
                            (create-session [_ session-data]
                              (assoc session-data
                                     :id (UUID/randomUUID)
                                     :session-token "mock-token-12345")))

        ;; Create service with mocks
        service (user-service/create-user-service mock-user-repo mock-session-repo)]

    ;; Test business logic without database
    (try
      (let [result (user-service/authenticate service "test@example.com" "any-password")]
        (if result
          (log/info "Mock authentication successful:" {:token (:session-token result)})
          (log/info "Mock authentication failed")))

      (catch Exception e
        (log/info "Expected behavior - mock doesn't implement password verification:"
                  {:error (.getMessage e)})))))

;; =============================================================================
;; Main Example Runner
;; =============================================================================

(defn run-examples
  "Run all examples to demonstrate the new user infrastructure."
  []
  (log/info "Starting User Infrastructure Examples...")

  ;; Setup infrastructure
  (let [infrastructure (setup-infrastructure)]

    ;; Run examples
    (example-user-operations infrastructure)
    (example-repository-operations infrastructure)
    (example-deprecated-usage)
    (example-testing-with-mocks))

  (log/info "Examples completed!"))

;; Uncomment to run examples:
;; (run-examples)

(comment
  ;; Interactive development examples

  ;; 1. Quick setup
  (def infra (setup-infrastructure))

  ;; 2. Create a user
  (user-service/create-user (:user-service infra)
                            {:email "dev@example.com"
                             :password "dev-password"
                             :role :developer
                             :tenant-id (UUID/randomUUID)})

  ;; 3. Test authentication
  (user-service/authenticate (:user-service infra)
                             "dev@example.com"
                             "dev-password"))
