# Module Structure and Conventions

*Real examples from Boundary's module-centric architecture with scaffolding templates*

## Module-Centric Architecture Overview

Boundary implements a **complete domain ownership** model where each module owns its entire vertical stack. This ensures clear boundaries, team autonomy, and independent evolution.

### Standard Module Layout

Every domain module follows this consistent structure:

```
src/boundary/{module-name}/
├── core/                          # Functional Core (Pure Logic)
│   ├── {domain}.clj               # Main domain functions
│   ├── {subdomain1}.clj           # Domain-specific logic
│   └── {subdomain2}.clj           # More domain functions
├── ports.clj                      # Abstract interfaces (protocols)
├── schema.clj                     # Data validation schemas
├── http.clj                       # HTTP API endpoints & routing
├── cli.clj                        # CLI commands & parsing
└── shell/                         # Imperative Shell (Infrastructure)
    ├── adapters.clj               # Concrete port implementations
    └── service.clj                # Service orchestration
```

## Concrete Module Examples

### User Module Deep Dive

**Complete structure:**
```
src/boundary/user/
├── core/                          # Pure business logic
│   ├── user.clj                   # Core user domain functions
│   ├── membership.clj             # Membership calculations
│   └── preferences.clj            # User preferences logic
├── ports.clj                      # User-specific abstract interfaces
├── schema.clj                     # User data validation schemas
├── http.clj                       # User HTTP endpoints
├── cli.clj                        # User CLI commands
└── shell/                         # Infrastructure layer
    ├── adapters.clj               # SQLite/PostgreSQL implementations
    └── service.clj                # User service orchestration
```

**Core Layer Example (`boundary.user.core.user`):**
```clojure
(ns boundary.user.core.user
  "Pure user domain functions - no side effects")

;; Pure function - takes data, returns data
(defn create-new-user
  "Creates a new user following business rules.
   
   Business Rules:
   - Email must be unique within tenant
   - Admin role requires existing admin to create
   - Active users receive welcome email"
  [user-input ports]
  (let [email-unique? (nil? ((:user-repository ports) 
                            :find-by-email (:email user-input)))]
    (if email-unique?
      {:status :success
       :data (assoc user-input :id (java.util.UUID/randomUUID))
       :effects [{:type :persist-user :user user-input}
                 {:type :send-welcome-email :email (:email user-input)}]}
      {:status :error
       :errors [{:field :email :code :already-exists}]})))
```

**Port Definition (`boundary.user.ports`):**
```clojure
(ns boundary.user.ports
  "Abstract interfaces for user module dependencies")

(defprotocol IUserRepository
  "User data persistence interface"
  (find-user-by-id [this user-id])
  (find-user-by-email [this email tenant-id])
  (create-user [this user-entity])
  (update-user [this user-entity]))

(defprotocol IUserNotificationService
  "User notification capabilities"
  (send-welcome-email [this user])
  (send-password-reset [this user reset-token]))
```

**Schema Definition (`boundary.user.schema`):**
```clojure
(ns boundary.user.schema
  "User module data validation schemas")

(def User
  [:map {:title "User Entity"}
   [:id :uuid]
   [:email [:string {:min 5 :max 255}]]
   [:name [:string {:min 1 :max 100}]]
   [:role [:enum :admin :user :viewer]]
   [:active :boolean]
   [:tenant-id :uuid]])

(def CreateUserRequest
  [:map {:title "Create User Request"}
   [:email :string]
   [:name :string]
   [:role [:enum :admin :user :viewer]]
   [:active {:optional true} :boolean]])
```

**Shell Service (`boundary.user.shell.service`):**
```clojure
(ns boundary.user.shell.service
  "User service orchestration - coordinates core with infrastructure")

(defn register-user [system user-data]
  "Service function that injects dependencies into core logic"
  (let [{:keys [user-repository user-notifications]} system
        
        ;; Call pure core function with dependencies
        result (user-core/create-new-user user-data 
                                         {:user-repository user-repository})]
    
    ;; Execute side effects based on core result
    (case (:status result)
      :success (do
                ;; Execute effects returned by core
                (doseq [effect (:effects result)]
                  (case (:type effect)
                    :persist-user (ports/create-user user-repository (:user effect))
                    :send-welcome-email (ports/send-welcome-email user-notifications (:user effect))))
                result)
      :error result)))
```

### Billing Module Structure

**Layout:**
```
src/boundary/billing/
├── core/
│   ├── pricing.clj                # Price calculations
│   ├── discounts.clj              # Discount logic
│   └── invoicing.clj              # Invoice generation
├── ports.clj                      # Billing-specific interfaces
├── schema.clj                     # Invoice, payment schemas
├── http.clj                       # Billing API endpoints
├── cli.clj                        # Billing CLI commands
└── shell/
    ├── adapters.clj               # Payment gateway adapters
    └── service.clj                # Billing service orchestration
```

**Billing Ports Example:**
```clojure
(defprotocol IPaymentProcessor
  "Payment processing capabilities"
  (process-payment [this payment-request])
  (refund-payment [this transaction-id amount reason])
  (get-payment-status [this transaction-id]))

(defprotocol IBillingRepository
  "Billing data persistence interface"
  (create-invoice [this invoice-data])
  (find-invoices-for-customer [this customer-id])
  (update-invoice-status [this invoice-id status]))
```

### Workflow Module Structure

**Layout:**
```
src/boundary/workflow/
├── core/
│   ├── state_machine.clj          # Process state logic
│   └── transitions.clj            # State transition rules
├── ports.clj                      # Workflow interfaces
├── schema.clj                     # Process, task schemas
├── http.clj                       # Workflow API endpoints
├── cli.clj                        # Workflow CLI commands
└── shell/
    ├── adapters.clj               # Workflow persistence
    └── service.clj                # Workflow service
```

## Shared Infrastructure

### Shell Layer Organization

The shell layer provides shared infrastructure that all modules can use:

```
src/boundary/shell/
├── adapters/                      # Concrete adapter implementations
│   ├── database/                  # Database-specific adapters
│   │   ├── sqlite.clj             # SQLite utilities
│   │   ├── postgresql.clj         # PostgreSQL utilities
│   │   └── h2.clj                 # H2 for testing
│   ├── external/                  # External service adapters
│   │   ├── email_smtp.clj         # SMTP email adapter
│   │   ├── payment_stripe.clj     # Stripe payment adapter
│   │   └── notifications.clj      # Push notification adapter
│   └── filesystem/                # File system adapters
│       ├── config_files.clj       # Configuration file handling
│       └── temp_storage.clj       # Temporary file storage
├── interfaces/                    # Interface implementations
│   ├── http/                      # HTTP server infrastructure
│   │   ├── server.clj             # Web server component
│   │   ├── middleware.clj         # HTTP middleware
│   │   └── routes.clj             # Route aggregation
│   ├── cli/                       # CLI infrastructure
│   │   ├── main.clj               # CLI entry point
│   │   ├── commands.clj           # Command orchestration
│   │   └── parsing.clj            # Argument parsing utilities
│   └── web/                       # WebSocket/SSE infrastructure
│       ├── websockets.clj         # WebSocket handling
│       └── sse.clj                # Server-sent events
└── system/                        # System lifecycle
    ├── components/                # Integrant components
    │   ├── postgresql.clj         # Database component
    │   └── sqlite.clj             # SQLite component
    ├── lifecycle.clj              # System lifecycle management
    └── wiring.clj                 # Dependency injection
```

### Shared Utilities

```
src/boundary/shared/
├── core/                          # Shared pure functions
│   ├── calculations.clj           # Common calculations
│   └── validation.clj             # Cross-module validations
└── utils/                         # Utility functions
    └── [empty - ready for shared utilities]
```

## Module Interaction Patterns

### 1. Direct Core Function Calls (Same Module)

```clojure
;; Within user module
(ns boundary.user.shell.service)
(require '[boundary.user.core.user :as user-core])

(defn register-user [system user-data]
  (user-core/create-new-user user-data system))
```

### 2. Cross-Module Communication via Ports

```clojure
;; Billing module calling user module through dependency injection
(ns boundary.billing.shell.service)

(defn process-subscription [system subscription-data]
  (let [{:keys [user-repository billing-repository]} system
        user (ports/find-user-by-id user-repository (:user-id subscription-data))]
    (billing-core/create-subscription user subscription-data system)))
```

### 3. Event-Based Communication (Future)

```clojure
;; User module publishes event, billing module subscribes
(defn register-user [system user-data]
  (let [result (user-core/create-new-user user-data system)]
    (when (= :success (:status result))
      (publish-event system {:type :user-created 
                            :user-id (get-in result [:data :id])}))
    result))
```

## Module Scaffolding Template

### New Module Generator Template

**Directory structure:**
```bash
#!/usr/bin/env zsh
# create-module.sh - Generate new module structure

MODULE_NAME=$1
if [[ -z "$MODULE_NAME" ]]; then
  echo "Usage: ./create-module.sh <module-name>"
  exit 1
fi

# Create directory structure
mkdir -p "src/boundary/${MODULE_NAME}/{core,shell}"
mkdir -p "test/boundary/${MODULE_NAME}/{core,shell}"

# Create files from templates
cat > "src/boundary/${MODULE_NAME}/ports.clj" << EOF
(ns boundary.${MODULE_NAME}.ports
  "Abstract interfaces for ${MODULE_NAME} module")

;; TODO: Define protocols for ${MODULE_NAME} domain
;; Example:
;; (defprotocol I${MODULE_NAME^}Repository
;;   "Data persistence interface for ${MODULE_NAME}"
;;   (find-by-id [this id])
;;   (create [this entity])
;;   (update [this entity]))
EOF

cat > "src/boundary/${MODULE_NAME}/schema.clj" << EOF
(ns boundary.${MODULE_NAME}.schema
  "Data validation schemas for ${MODULE_NAME} module"
  (:require [malli.core :as m]))

;; TODO: Define schemas for ${MODULE_NAME} domain
;; Example:
;; (def ${MODULE_NAME^}
;;   [:map {:title "${MODULE_NAME^} Entity"}
;;    [:id :uuid]
;;    [:name [:string {:min 1 :max 100}]]
;;    [:created-at :inst]])
EOF

# ... continue with other template files
```

**Core template (`core/{module-name}.clj`):**
```clojure
(ns boundary.${MODULE_NAME}.core.${MODULE_NAME}
  "Pure ${MODULE_NAME} domain functions - no side effects")

;; TODO: Implement core business logic for ${MODULE_NAME}
;; Remember:
;; - Only pure functions
;; - Take data, return data
;; - Depend only on ports (abstractions)
;; - Return {:status :success/:error :data ... :effects [...]}

(defn create-new-${MODULE_NAME}
  "${MODULE_NAME^} creation with business rules validation"
  [${MODULE_NAME}-input ports]
  ;; TODO: Implement creation logic
  {:status :success
   :data (assoc ${MODULE_NAME}-input :id (java.util.UUID/randomUUID))
   :effects [{:type :persist-${MODULE_NAME} :${MODULE_NAME} ${MODULE_NAME}-input}]})
```

**Shell service template:**
```clojure
(ns boundary.${MODULE_NAME}.shell.service
  "Service orchestration for ${MODULE_NAME} module"
  (:require [boundary.${MODULE_NAME}.core.${MODULE_NAME} :as ${MODULE_NAME}-core]
            [boundary.${MODULE_NAME}.ports :as ports]))

(defn create-${MODULE_NAME} [system ${MODULE_NAME}-data]
  "Orchestrates ${MODULE_NAME} creation with dependency injection"
  (let [result (${MODULE_NAME}-core/create-new-${MODULE_NAME} ${MODULE_NAME}-data system)]
    
    ;; Execute side effects based on core result
    (case (:status result)
      :success (do
                ;; TODO: Execute effects returned by core
                result)
      :error result)))
```

**HTTP interface template:**
```clojure
(ns boundary.${MODULE_NAME}.http
  "HTTP API endpoints for ${MODULE_NAME} module")

;; TODO: Define HTTP routes for ${MODULE_NAME}
;; Example:
;; (defn ${MODULE_NAME}-routes [system]
;;   [["/${MODULE_NAME}s" {:get {:handler (partial list-${MODULE_NAME}s system)}
;;                        :post {:handler (partial create-${MODULE_NAME} system)}}]])
```

**CLI interface template:**
```clojure
(ns boundary.${MODULE_NAME}.cli
  "CLI commands for ${MODULE_NAME} module")

;; TODO: Define CLI commands for ${MODULE_NAME}
;; Example:
;; (defn create-${MODULE_NAME}-command [system args]
;;   ;; Parse args and call service
;;   )
```

## Module Development Best Practices

### 1. Start with Ports and Schemas

Always begin new module development by defining:
1. **Ports**: What external dependencies does this module need?
2. **Schemas**: What data structures will this module work with?
3. **Core Functions**: What business logic needs to be implemented?

### 2. Maintain Clean Boundaries

- **Core layer**: Only pure functions, no I/O
- **Ports**: Only abstract interfaces, no implementations
- **Shell layer**: Only infrastructure concerns, minimal business logic
- **Schemas**: Only data validation, no business rules

### 3. Test Each Layer Appropriately

- **Core**: Unit tests with simple data mocks
- **Shell**: Integration tests with real dependencies
- **Interfaces**: System tests across HTTP/CLI/Web

### 4. Module Interdependencies

- **Prefer loose coupling**: Use ports for cross-module dependencies
- **Avoid circular dependencies**: Module A should not directly depend on module B if B depends on A
- **Use events for complex coordination**: For workflows spanning multiple modules

### 5. Documentation Standards

Each module should include:
- **README.md**: Module purpose and key capabilities
- **Port documentation**: Clear interface contracts
- **Schema examples**: Sample data structures
- **Core function examples**: Usage patterns for key functions

---
*Last Updated: 2025-01-10 18:32*
*Based on: User, Billing, and Workflow module analysis*
