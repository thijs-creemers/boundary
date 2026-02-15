---
title: "Code Comparisons"
weight: 90
---

# Code Comparisons: Boundary vs Other Frameworks

This guide provides side-by-side comparisons of implementing common features in Boundary versus other popular frameworks. Each comparison highlights the architectural differences and benefits of the Functional Core / Imperative Shell pattern.

---

## Django vs Boundary: User Creation

### Django (Mixed Concerns)

In Django, business logic, validation, I/O, and side effects are typically mixed together in views or model methods:

```python
# Django view - everything mixed together
from django.http import JsonResponse
from django.core.mail import send_mail
from .models import User
import logging

logger = logging.getLogger(__name__)

def create_user(request):
    """Create a new user - all concerns mixed."""
    # Extract data from request
    email = request.POST.get('email')
    name = request.POST.get('name')
    
    # Validation mixed with I/O
    if not email or '@' not in email:
        return JsonResponse({'error': 'Invalid email'}, status=400)
    
    # Database query (side effect)
    if User.objects.filter(email=email).exists():
        return JsonResponse({'error': 'Email already exists'}, status=409)
    
    # Create user (side effect)
    user = User.objects.create(
        email=email,
        name=name,
        tier='bronze'
    )
    
    # Send email (side effect)
    try:
        send_mail(
            'Welcome!',
            f'Welcome {name}!',
            'noreply@example.com',
            [email],
            fail_silently=False,
        )
    except Exception as e:
        logger.error(f"Failed to send email: {e}")
    
    # Log (side effect)
    logger.info(f"User created: {email}")
    
    # Return response
    return JsonResponse({
        'id': user.id,
        'email': user.email,
        'tier': user.tier
    }, status=201)
```

**Problems with Django Approach:**

- ❌ **Cannot test business logic without database** - User creation logic requires database access
- ❌ **Cannot reuse logic elsewhere** - Business rules embedded in HTTP view
- ❌ **Mixed concerns** - Validation, I/O, email, logging all intertwined
- ❌ **Hard to understand** - What's business logic vs infrastructure?
- ❌ **Difficult to change** - Want to add CLI? Must duplicate logic

### Boundary (FC/IS Separation)

Boundary separates pure business logic from infrastructure concerns:

```clojure
;; ==========================================
;; FUNCTIONAL CORE - Pure business logic
;; src/boundary/user/core/user.clj
;; ==========================================

(ns boundary.user.core.user)

(defn prepare-user-creation
  "Pure function: prepares user data for creation.
   
   Args:
     user-data - Map with :email, :name
     current-time - java.time.Instant (injected)
     
   Returns:
     Map with :action keyword and :user entity
     
   Pure: true (no side effects, deterministic)"
  [user-data current-time]
  {:action :create
   :user {:email (:email user-data)
          :name (:name user-data)
          :tier :bronze
          :created-at current-time}
   :effects [{:type :send-welcome-email
              :email (:email user-data)
              :name (:name user-data)}]})

(defn validate-user-data
  "Pure function: validates user data against business rules.
   
   Returns:
     {:valid? boolean :errors [...]}
     
   Pure: true"
  [user-data]
  (let [errors []]
    (if (and (:email user-data)
             (re-matches #".+@.+" (:email user-data)))
      {:valid? true :data user-data}
      {:valid? false 
       :errors [{:field :email :code :invalid}]})))

;; ==========================================
;; IMPERATIVE SHELL - Side effects
;; src/boundary/user/shell/service.clj
;; ==========================================

(ns boundary.user.shell.service
  (:require [boundary.user.core.user :as user-core]
            [boundary.user.ports :as ports]
            [boundary.shared.shell.interceptors :as interceptors]))

(defrecord UserService [user-repository email-service logger metrics error-reporter]
  ports/IUserService
  
  (create-user [this user-data]
    (interceptors/execute-service-operation
      {:operation-name "create-user"
       :logger logger
       :metrics metrics
       :error-reporter error-reporter
       :context {:email (:email user-data)}}
      (fn []
        ;; 1. Validate input (shell responsibility)
        (let [validation (user-core/validate-user-data user-data)]
          (if-not (:valid? validation)
            {:status :error :errors (:errors validation)}
            
            ;; 2. Check for existing user (I/O - shell responsibility)
            (let [existing (.find-by-email user-repository (:email user-data))]
              (if existing
                {:status :conflict
                 :errors [{:field :email :code :already-exists}]}
                
                ;; 3. Call pure core logic (no side effects)
                (let [current-time (java.time.Instant/now)
                      result (user-core/prepare-user-creation user-data current-time)
                      created-user (.create-user user-repository (:user result))]
                  
                  ;; 4. Execute effects (shell responsibility)
                  (doseq [effect (:effects result)]
                    (when (= :send-welcome-email (:type effect))
                      (.send-welcome email-service (:email effect) (:name effect))))
                  
                  {:status :success :user created-user})))))))))

;; ==========================================
;; HTTP ADAPTER - REST interface
;; src/boundary/user/shell/http.clj
;; ==========================================

(ns boundary.user.shell.http
  (:require [boundary.user.ports :as ports]))

(defn create-user-handler [user-service]
  (fn [request]
    (let [user-data (get-in request [:body-params])
          result (ports/create-user user-service user-data)]
      (case (:status result)
        :success {:status 201
                  :body {:user (:user result)}}
        :conflict {:status 409
                   :body {:errors (:errors result)}}
        :error {:status 400
                :body {:errors (:errors result)}}))))
```

**Benefits of Boundary Approach:**

- ✅ **Test core without database** - `prepare-user-creation` is pure, test with simple data
- ✅ **Reuse logic everywhere** - Same core logic for REST, CLI, background jobs
- ✅ **Clear separation** - Business rules in core, I/O in shell
- ✅ **Easy to understand** - Pure functions are self-documenting
- ✅ **Easy to change** - Add GraphQL? Just write new adapter, reuse core
- ✅ **Automatic observability** - Interceptor pattern adds logging/metrics/errors

**Testing Comparison:**

```python
# Django test - requires database
from django.test import TestCase

class UserCreationTest(TestCase):
    def test_create_user(self):
        # Must set up database, mock email service, etc.
        response = self.client.post('/users/', {
            'email': 'test@example.com',
            'name': 'Test User'
        })
        self.assertEqual(response.status_code, 201)
        # Must clean up database after test
```

```clojure
;; Boundary test - no database needed for core logic
(ns boundary.user.core.user-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.user.core.user :as user-core]))

(deftest prepare-user-creation-test
  (testing "creates user with correct tier"
    (let [user-data {:email "test@example.com" :name "Test"}
          current-time #inst "2024-01-01T00:00:00Z"
          result (user-core/prepare-user-creation user-data current-time)]
      (is (= :create (:action result)))
      (is (= :bronze (get-in result [:user :tier])))
      (is (= current-time (get-in result [:user :created-at]))))))
;; No database, no mocks, instant execution
```

---

## Rails vs Boundary: User Creation

### Rails (Active Record Pattern)

Rails typically puts business logic in models using Active Record:

```ruby
# Rails model - mixed concerns
class User < ApplicationRecord
  validates :email, presence: true, uniqueness: true, format: { with: URI::MailTo::EMAIL_REGEXP }
  validates :name, presence: true
  
  after_create :send_welcome_email
  after_create :log_user_creation
  
  # Business logic mixed with persistence
  def self.create_with_tier(user_params)
    user = new(user_params)
    user.tier = calculate_initial_tier(user)
    
    if user.save
      user
    else
      nil
    end
  end
  
  private
  
  def self.calculate_initial_tier(user)
    # Business logic embedded in model
    user.referral_code.present? ? 'silver' : 'bronze'
  end
  
  def send_welcome_email
    UserMailer.welcome_email(self).deliver_later
  rescue => e
    Rails.logger.error("Failed to send welcome email: #{e}")
  end
  
  def log_user_creation
    Rails.logger.info("User created: #{email}")
  end
end

# Rails controller
class UsersController < ApplicationController
  def create
    user = User.create_with_tier(user_params)
    
    if user
      render json: user, status: :created
    else
      render json: { errors: user.errors.full_messages }, status: :unprocessable_entity
    end
  end
  
  private
  
  def user_params
    params.require(:user).permit(:email, :name, :referral_code)
  end
end
```

**Problems with Rails Approach:**

- ❌ **Business logic tied to database** - Active Record couples logic to persistence
- ❌ **Callbacks are hidden** - `after_create` makes side effects invisible
- ❌ **Hard to test in isolation** - Must load Rails, database, email infrastructure
- ❌ **Framework magic** - Validations, callbacks, concerns obscure control flow
- ❌ **Testing requires full stack** - Even unit tests need database

### Boundary (Explicit & Pure)

```clojure
;; ==========================================
;; FUNCTIONAL CORE - Pure business logic
;; ==========================================

(ns boundary.user.core.user)

(defn calculate-initial-tier
  "Pure function: determines initial user tier based on referral.
   
   Pure: true (no database, no side effects)"
  [user-data]
  (if (:referral-code user-data)
    :silver
    :bronze))

(defn prepare-user-creation
  "Pure function: prepares complete user entity with tier.
   
   All business rules clearly visible and testable."
  [user-data current-time]
  (let [tier (calculate-initial-tier user-data)]
    {:action :create
     :user {:email (:email user-data)
            :name (:name user-data)
            :tier tier
            :referral-code (:referral-code user-data)
            :created-at current-time}
     :effects [{:type :send-welcome-email
                :email (:email user-data)}
               {:type :log-creation
                :email (:email user-data)}]}))

;; ==========================================
;; IMPERATIVE SHELL - Explicit side effects
;; ==========================================

(ns boundary.user.shell.service
  (:require [boundary.user.core.user :as user-core]))

(defn create-user
  "Shell function: orchestrates I/O around pure core logic.
   
   All side effects are explicit and visible."
  [{:keys [user-repository email-service logger]} user-data]
  ;; 1. Validate (shell)
  (let [validation (validate-user-data user-data)]
    (if-not (:valid? validation)
      {:status :error :errors (:errors validation)}
      
      ;; 2. Check existing (explicit I/O)
      (let [existing (.find-by-email user-repository (:email user-data))]
        (if existing
          {:status :conflict}
          
          ;; 3. Call pure core logic
          (let [current-time (java.time.Instant/now)
                result (user-core/prepare-user-creation user-data current-time)]
            
            ;; 4. Persist (explicit I/O)
            (let [created (.create-user user-repository (:user result))]
              
              ;; 5. Execute effects (explicit, sequential)
              (doseq [effect (:effects result)]
                (case (:type effect)
                  :send-welcome-email
                  (try
                    (.send-welcome email-service (:email effect))
                    (catch Exception e
                      (.error logger "Failed to send email" {:error e})))
                  
                  :log-creation
                  (.info logger "User created" {:email (:email effect)})))
              
              {:status :success :user created})))))))
```

**Benefits of Boundary Approach:**

- ✅ **Business logic completely independent** - No framework coupling
- ✅ **All side effects explicit** - No hidden callbacks
- ✅ **Easy to test** - `calculate-initial-tier` tested with pure data
- ✅ **No magic** - Control flow is obvious
- ✅ **Instant tests** - Core logic tests run in milliseconds

**Testing Comparison:**

```ruby
# Rails test - requires full stack
require 'test_helper'

class UserTest < ActiveSupport::TestCase
  test "creates user with correct tier" do
    # Must load Rails, database, factories
    user = User.create_with_tier(
      email: 'test@example.com',
      name: 'Test User',
      referral_code: 'ABC123'
    )
    
    assert_equal 'silver', user.tier
    # Database must be cleaned up after
  end
end
```

```clojure
;; Boundary test - pure, instant
(deftest calculate-initial-tier-test
  (testing "silver tier with referral code"
    (is (= :silver
           (user-core/calculate-initial-tier {:referral-code "ABC123"}))))
  
  (testing "bronze tier without referral code"
    (is (= :bronze
           (user-core/calculate-initial-tier {})))))
;; Runs in < 1ms, no database
```

---

## Spring Boot vs Boundary: User Creation

### Spring Boot (Annotation-Heavy)

Spring Boot relies heavily on dependency injection, annotations, and framework magic:

```java
// Spring Boot - annotation-heavy, framework-coupled
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(unique = true, nullable = false)
    @Email
    private String email;
    
    @Column(nullable = false)
    private String name;
    
    @Enumerated(EnumType.STRING)
    private Tier tier;
    
    @CreatedDate
    private Instant createdAt;
    
    // Getters, setters, constructors...
}

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
}

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private EmailService emailService;
    
    @Autowired
    private AuditLogger auditLogger;
    
    @Transactional
    public User createUser(CreateUserRequest request) {
        // Validation
        if (request.getEmail() == null || !request.getEmail().contains("@")) {
            throw new ValidationException("Invalid email");
        }
        
        // Check existing (database query)
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ConflictException("Email already exists");
        }
        
        // Business logic mixed with persistence
        User user = new User();
        user.setEmail(request.getEmail());
        user.setName(request.getName());
        user.setTier(calculateInitialTier(request));
        
        // Save (database)
        User saved = userRepository.save(user);
        
        // Side effects
        try {
            emailService.sendWelcomeEmail(saved.getEmail(), saved.getName());
        } catch (Exception e) {
            log.error("Failed to send email", e);
        }
        
        auditLogger.logUserCreation(saved.getId(), saved.getEmail());
        
        return saved;
    }
    
    private Tier calculateInitialTier(CreateUserRequest request) {
        return request.getReferralCode() != null ? Tier.SILVER : Tier.BRONZE;
    }
}

@RestController
@RequestMapping("/api/users")
public class UserController {
    @Autowired
    private UserService userService;
    
    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody @Valid CreateUserRequest request) {
        try {
            User user = userService.createUser(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(user);
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().build();
        } catch (ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
}
```

**Problems with Spring Boot Approach:**

- ❌ **Heavy framework dependency** - Annotations couple code to Spring
- ❌ **Complex testing** - Must load Spring context, mock beans
- ❌ **Hidden control flow** - `@Transactional`, `@Async` hide behavior
- ❌ **Slow startup** - Spring context initialization adds seconds
- ❌ **Configuration complexity** - XML or Java config files

### Boundary (Lightweight, Explicit)

```clojure
;; ==========================================
;; FUNCTIONAL CORE - No framework coupling
;; ==========================================

(ns boundary.user.core.user)

(defn calculate-initial-tier
  "Pure function - no Spring, no annotations, no magic."
  [user-data]
  (if (:referral-code user-data) :silver :bronze))

(defn prepare-user-creation
  "Pure function - testable without Spring context."
  [user-data current-time]
  {:action :create
   :user {:email (:email user-data)
          :name (:name user-data)
          :tier (calculate-initial-tier user-data)
          :created-at current-time}
   :effects [{:type :send-welcome-email
              :email (:email user-data)}]})

;; ==========================================
;; IMPERATIVE SHELL - Explicit dependencies
;; ==========================================

(ns boundary.user.shell.service
  (:require [boundary.user.core.user :as user-core]))

(defrecord UserService [user-repository email-service audit-logger]
  ;; Explicit protocol implementation (no magic)
  ports/IUserService
  
  (create-user [this user-data]
    ;; All dependencies explicit in record fields
    (let [validation (validate-user-data user-data)]
      (if-not (:valid? validation)
        {:status :error :errors (:errors validation)}
        
        (let [existing (.find-by-email user-repository (:email user-data))]
          (if existing
            {:status :conflict}
            
            (let [current-time (java.time.Instant/now)
                  result (user-core/prepare-user-creation user-data current-time)
                  created (.create-user user-repository (:user result))]
              
              ;; Explicit effect execution (no @Async magic)
              (try
                (.send-welcome email-service (:email created))
                (catch Exception e
                  (log/error e "Failed to send email")))
              
              (.log-creation audit-logger (:id created) (:email created))
              
              {:status :success :user created})))))))

;; ==========================================
;; SYSTEM WIRING - Integrant (explicit, data-driven)
;; ==========================================

;; config.edn
{:boundary/user-repository
 {:ctx (ig/ref :boundary/db-context)}
 
 :boundary/email-service
 {:smtp-host #env "SMTP_HOST"
  :smtp-port #env "SMTP_PORT"}
 
 :boundary/audit-logger
 {:db (ig/ref :boundary/db-context)}
 
 :boundary/user-service
 {:user-repository (ig/ref :boundary/user-repository)
  :email-service (ig/ref :boundary/email-service)
  :audit-logger (ig/ref :boundary/audit-logger)}}
```

**Benefits of Boundary Approach:**

- ✅ **No framework coupling** - Pure Clojure, no annotations
- ✅ **Simple testing** - No Spring context needed
- ✅ **Explicit control flow** - All behavior is visible
- ✅ **Fast startup** - Milliseconds vs seconds
- ✅ **Data-driven config** - Aero config vs complex Java configs

**Testing Comparison:**

```java
// Spring Boot test - requires Spring context
@SpringBootTest
class UserServiceTest {
    @Autowired
    private UserService userService;
    
    @MockBean
    private UserRepository userRepository;
    
    @MockBean
    private EmailService emailService;
    
    @Test
    void testCreateUser() {
        // Must load entire Spring context (slow)
        when(userRepository.findByEmail(anyString()))
            .thenReturn(Optional.empty());
        
        User user = userService.createUser(new CreateUserRequest(...));
        
        assertEquals(Tier.BRONZE, user.getTier());
    }
}
// Test runs in 5-10 seconds due to Spring context
```

```clojure
;; Boundary test - instant, no framework
(deftest calculate-initial-tier-test
  (testing "calculates correct tier"
    (is (= :silver
           (user-core/calculate-initial-tier {:referral-code "ABC"})))
    (is (= :bronze
           (user-core/calculate-initial-tier {})))))
;; Runs in < 1ms, no Spring, no mocks
```

---

## Summary: Key Differences

| Aspect | Django/Rails/Spring | Boundary |
|--------|---------------------|----------|
| **Business Logic** | Mixed with framework/DB | Pure, framework-agnostic |
| **Testing** | Requires database/framework | Pure functions, instant tests |
| **Side Effects** | Hidden (callbacks, transactions) | Explicit and visible |
| **Reusability** | Tied to web layer | Works in REST/CLI/jobs |
| **Control Flow** | Magic (decorators, annotations) | Explicit and clear |
| **Startup Time** | Seconds (framework loading) | Milliseconds |
| **Learning Curve** | Framework-specific patterns | Functional programming |
| **Testability** | Integration tests dominant | Unit tests for core, integration for shell |

---

## When to Choose What?

### Choose Django/Rails if:

- ✅ You want rapid prototyping with scaffolding
- ✅ You're building a traditional CRUD web app
- ✅ Your team is already expert in Django/Rails
- ✅ You need extensive plugin ecosystem

### Choose Spring Boot if:

- ✅ You're in a Java-heavy organization
- ✅ You need enterprise Java integration
- ✅ You have complex transaction requirements
- ✅ Your team prefers OOP patterns

### Choose Boundary if:

- ✅ You want testable, maintainable business logic
- ✅ You need multiple interfaces (REST + CLI + Web)
- ✅ You value functional programming benefits
- ✅ You want fast startup and development cycle
- ✅ You need to understand and control your architecture
- ✅ You want to avoid framework lock-in

---

## Next Steps

- **Try the Tutorial**: [Your First Module](../getting-started/your-first-module) - Build a blog module in 30 minutes
- **Deep Dive**: [Functional Core / Imperative Shell](../guides/functional-core-imperative-shell) - Understand the pattern in depth
- **Architecture**: [Ports and Adapters](../guides/ports-and-adapters) - Learn hexagonal architecture
- **Production**: [Deployment Guide](../getting-started/deployment) - Deploy Boundary to production
