(ns database-agnostic-user-example
  "Example demonstrating the new database-agnostic user architecture.
   
   This example shows how to:
   1. Create database context
   2. Initialize user schema
   3. Create database-specific repositories
   4. Create database-agnostic user service
   5. Use the service for business operations
   
   The business logic is completely separated from database concerns."
  (:require [boundary.platform.shell.adapters.database.factory :as db-factory]
            [boundary.platform.shell.adapters.database.user :as db-user]
            [boundary.user.shell.service :as user-service]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Example: Database-Agnostic User Architecture
;; =============================================================================

(defn create-user-system
  "Create a complete user management system with dependency injection.
   
   Args:
     db-config: Database configuration map
     
   Returns:
     Map with :service, :user-repo, :session-repo, :db-context
     
   Example:
     (create-user-system {:adapter :postgresql :host \"localhost\" ...})"
  [db-config]
  (log/info "Creating user system" {:database (:adapter db-config)})
  
  ;; 1. Create database context (infrastructure layer)
  (let [db-context (db-factory/db-context db-config)]
    
    ;; 2. Initialize schema (database layer)
    (db-user/initialize-user-schema! db-context)
    
    ;; 3. Create repositories (database layer)
    (let [user-repo (db-user/create-user-repository db-context)
          session-repo (db-user/create-session-repository db-context)
          
          ;; 4. Create service with injected dependencies (business layer)
          service (user-service/create-user-service user-repo session-repo)]
      
      (log/info "User system created successfully")
      {:service service
       :user-repo user-repo
       :session-repo session-repo
       :db-context db-context})))

(defn example-usage
  "Example of using the database-agnostic user system."
  []
  (println "=== Database-Agnostic User Architecture Example ===")
  
  ;; Create system with PostgreSQL
  (let [pg-system (create-user-system {:adapter :postgresql 
                                       :host "localhost" 
                                       :port 5432
                                       :name "myapp"
                                       :username "user"
                                       :password "pass"})
        service (:service pg-system)]
    
    (println "✓ PostgreSQL user system created")
    
    ;; Use service for business operations (completely database-agnostic)
    (let [user-data {:email "john@example.com"
                     :name "John Doe"
                     :role :user
                     :tenant-id (java.util.UUID/randomUUID)}]
      
      ;; Service handles validation, business rules, and delegates to repositories
      (println "Creating user through service...")
      (try
        (let [created-user (.create-user service user-data)]
          (println "✓ User created:" (:email created-user))
          
          ;; Find user
          (let [found-user (.find-user-by-id service (:id created-user))]
            (println "✓ User found:" (:email found-user))))
        (catch Exception e
          (println "✗ Error:" (.getMessage e))))))
  
  ;; The same service code works with SQLite
  (let [sqlite-system (create-user-system {:adapter :sqlite 
                                           :database-path "./example.db"})]
    (println "✓ SQLite user system created with same interface")))

;; =============================================================================
;; Architecture Benefits
;; =============================================================================

(comment
  ;; Benefits of this architecture:
  
  ;; 1. SEPARATION OF CONCERNS
  ;; - Business logic in boundary.user.shell.service (database-agnostic)
  ;; - Database logic in boundary.platform.shell.adapters.database.user
  ;; - Domain models in boundary.user.schema
  
  ;; 2. DEPENDENCY INJECTION
  ;; - Service receives repository interfaces, not database contexts
  ;; - Easy to test with mock repositories
  ;; - Easy to swap database implementations
  
  ;; 3. DATABASE AGNOSTIC
  ;; - Same service code works with any database
  ;; - Database-specific optimizations handled in database layer
  ;; - Schema generation from canonical Malli definitions
  
  ;; 4. CLEAN INTERFACES
  ;; - boundary.user.ports defines contracts
  ;; - Database layer implements contracts
  ;; - Business layer uses contracts
  
  ;; 5. MAINTAINABILITY
  ;; - Changes to database implementation don't affect business logic
  ;; - Changes to business rules don't affect database implementation
  ;; - Clear separation makes testing easier
  
  ;; 6. EXTENSIBILITY
  ;; - Easy to add new database types
  ;; - Easy to add new repository methods
  ;; - Easy to add new business services
  )

;; Uncomment to run the example
; (example-usage)