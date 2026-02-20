# Todo API Example

**A complete REST API for task management built with Boundary Framework**

This example demonstrates:
- ✅ CRUD operations with database persistence
- ✅ Input validation with Malli schemas
- ✅ JWT authentication
- ✅ Error handling with RFC 7807 problem details
- ✅ Comprehensive test coverage
- ✅ API documentation with Swagger UI
- ✅ Database migrations
- ✅ Observability (logging, metrics, error reporting)

**Complexity:** ⭐ Simple
**Time to complete:** 30 minutes
**Lines of code:** ~500 (without tests)

---

## What You'll Build

A task management API with the following features:

### Endpoints

```
Authentication:
POST   /api/v1/auth/register    - Register new user
POST   /api/v1/auth/login       - Login and get JWT token

Tasks:
GET    /api/v1/tasks            - List all tasks (paginated)
POST   /api/v1/tasks            - Create new task
GET    /api/v1/tasks/:id        - Get task by ID
PUT    /api/v1/tasks/:id        - Update task
DELETE /api/v1/tasks/:id        - Delete task
POST   /api/v1/tasks/:id/complete  - Mark task as complete
GET    /api/v1/tasks/stats      - Get task statistics
```

### Data Model

**Task:**
```clojure
{:id          "uuid"
 :user-id     "uuid"
 :title       "string (1-200 chars)"
 :description "string (optional, max 1000 chars)"
 :completed   "boolean"
 :priority    "enum: :low, :medium, :high"
 :due-date    "instant (optional)"
 :created-at  "instant"
 :updated-at  "instant"}
```

---

## Quick Start

### 1. Prerequisites

- Java 11+
- Clojure CLI
- PostgreSQL (or use H2 for quick testing)

### 2. Setup

```bash
# Navigate to example directory
cd examples/todo-api

# Install dependencies
clojure -P -M:dev

# Initialize database
clojure -M:migrate init
clojure -M:migrate migrate
```

### 3. Run

```bash
# Start server
clojure -M:dev -m todo-api.main

# Or use REPL
clojure -M:repl-clj
```

```clojure
;; In REPL:
(require '[integrant.repl :as ig-repl])
(require '[todo-api.system])

(ig-repl/set-prep! #(todo-api.system/system-config))
(ig-repl/go)
;; Server started at http://localhost:3000
```

### 4. Test

```bash
# Register a user
curl -X POST http://localhost:3000/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password123","name":"Test User"}'

# Login
curl -X POST http://localhost:3000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password123"}'
# Save the token from response

# Create a task
curl -X POST http://localhost:3000/api/v1/tasks \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN_HERE" \
  -d '{
    "title":"Complete Boundary tutorial",
    "description":"Follow the todo-api example",
    "priority":"high",
    "dueDate":"2026-01-10T12:00:00Z"
  }'

# List tasks
curl http://localhost:3000/api/v1/tasks \
  -H "Authorization: Bearer YOUR_TOKEN_HERE"
```

### 5. Explore API Docs

```bash
open http://localhost:3000/api-docs/
```

---

## Project Structure

```
todo-api/
├── README.md                   # This file
├── deps.edn                    # Dependencies
├── resources/
│   └── config/
│       ├── dev.edn            # Development config
│       ├── test.edn           # Test config
│       └── prod.edn           # Production config
├── migrations/
│   ├── 001-create-tasks.up.sql
│   └── 001-create-tasks.down.sql
├── src/todo_api/
│   ├── main.clj               # Application entry point
│   ├── system.clj             # Integrant system configuration
│   │
│   ├── task/                  # Task module
│   │   ├── core/
│   │   │   ├── task.clj       # Pure business logic
│   │   │   └── validation.clj # Validation rules
│   │   ├── ports.clj          # Port definitions
│   │   ├── schema.clj         # Malli schemas
│   │   └── shell/
│   │       ├── http.clj       # HTTP handlers & routes
│   │       ├── persistence.clj # Database adapter
│   │       └── service.clj    # Shell orchestration
│   │
│   └── auth/                  # Authentication module
│       ├── core/
│       │   └── auth.clj       # Authentication logic
│       ├── ports.clj
│       ├── schema.clj
│       └── shell/
│           ├── http.clj
│           ├── jwt.clj        # JWT token handling
│           └── service.clj
│
└── test/todo_api/
    ├── task/
    │   ├── core/
    │   │   └── task_test.clj  # Unit tests
    │   └── shell/
    │       └── http_test.clj  # Integration tests
    └── integration_test.clj   # End-to-end tests
```

---

## Implementation Walkthrough

### Step 1: Define Task Schema

**src/todo_api/task/schema.clj:**
```clojure
(ns todo-api.task.schema
  (:require [malli.core :as m]))

(def Priority
  [:enum :low :medium :high])

(def TaskInput
  [:map
   [:title [:string {:min 1 :max 200}]]
   [:description {:optional true} [:string {:max 1000}]]
   [:priority {:optional true} Priority]
   [:dueDate {:optional true} inst?]])

(def Task
  [:map
   [:id uuid?]
   [:userId uuid?]
   [:title [:string {:min 1 :max 200}]]
   [:description {:optional true} [:string {:max 1000}]]
   [:completed boolean?]
   [:priority Priority]
   [:dueDate {:optional true} inst?]
   [:createdAt inst?]
   [:updatedAt inst?]])
```

### Step 2: Implement Business Logic

**src/todo_api/task/core/task.clj:**
```clojure
(ns todo-api.task.core.task)

(defn create-task
  "Creates a new task with default values."
  [user-id task-input]
  (let [now (java.time.Instant/now)]
    {:id (java.util.UUID/randomUUID)
     :user-id user-id
     :title (:title task-input)
     :description (:description task-input)
     :completed false
     :priority (or (:priority task-input) :medium)
     :due-date (:due-date task-input)
     :created-at now
     :updated-at now}))

(defn complete-task
  "Marks a task as completed."
  [task]
  (assoc task
         :completed true
         :updated-at (java.time.Instant/now)))

(defn update-task
  "Updates task with new values."
  [task updates]
  (merge task
         (select-keys updates [:title :description :priority :due-date])
         {:updated-at (java.time.Instant/now)}))

(defn overdue?
  "Checks if task is overdue."
  [task]
  (and (:due-date task)
       (not (:completed task))
       (.isBefore (:due-date task) (java.time.Instant/now))))
```

**Key Points:**
- **Pure functions** - No side effects, easy to test
- **Domain logic** - Business rules are clear and explicit
- **Immutable data** - All transformations return new values

### Step 3: Define Ports

**src/todo_api/task/ports.clj:**
```clojure
(ns todo-api.task.ports)

(defprotocol ITaskRepository
  "Repository for task persistence."
  (find-task-by-id [this task-id]
    "Find task by ID. Returns nil if not found.")
  (find-tasks-by-user [this user-id filters]
    "Find all tasks for user with optional filters.")
  (save-task! [this task]
    "Save task (create or update).")
  (delete-task! [this task-id]
    "Delete task by ID.")
  (task-stats [this user-id]
    "Get task statistics for user."))
```

### Step 4: Implement Database Adapter

**src/todo_api/task/shell/persistence.clj:**
```clojure
(ns todo-api.task.shell.persistence
  (:require [todo-api.task.ports :as ports]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [honey.sql :as honey]))

(defrecord PostgresTaskRepository [datasource]
  ports/ITaskRepository

  (find-task-by-id [this task-id]
    (sql/get-by-id datasource :tasks task-id))

  (find-tasks-by-user [this user-id filters]
    (let [query (-> {:select [:*]
                     :from [:tasks]
                     :where [:= :user_id user-id]}
                    (cond->
                      (:completed filters)
                      (assoc-in [:where] [:and
                                          [:= :user_id user-id]
                                          [:= :completed (:completed filters)]])

                      (:priority filters)
                      (assoc-in [:where] [:and
                                          [:= :user_id user-id]
                                          [:= :priority (:priority filters)]]))
                    (honey/format))]
      (jdbc/execute! datasource query)))

  (save-task! [this task]
    (sql/insert! datasource :tasks task
                 {:on-conflict [:id]
                  :do-update-set (keys task)}))

  (delete-task! [this task-id]
    (sql/delete! datasource :tasks {:id task-id}))

  (task-stats [this user-id]
    (let [query (honey/format
                 {:select [[[:count :*] :total]
                           [[:count [:case [:= :completed true] 1]] :completed]
                           [[:count [:case [:= :completed false] 1]] :active]
                           [[:count [:case [:= :priority "high"] 1]] :high_priority]]
                  :from [:tasks]
                  :where [:= :user_id user-id]})
          result (jdbc/execute-one! datasource query)]
      result)))

(defn create-postgres-task-repository
  "Creates a PostgreSQL task repository."
  [datasource]
  (->PostgresTaskRepository datasource))
```

### Step 5: Create HTTP Handlers

**src/todo_api/task/shell/http.clj:**
```clojure
(ns todo-api.task.shell.http
  (:require [todo-api.task.core.task :as task]
            [todo-api.task.schema :as schema]
            [todo-api.task.ports :as ports]))

(defn list-tasks-handler
  "GET /api/v1/tasks - List all tasks for authenticated user."
  [task-service]
  (fn [request]
    (let [user-id (get-in request [:identity :user-id])
          filters (select-keys (:query-params request) [:completed :priority])
          tasks (ports/find-tasks-by-user task-service user-id filters)]
      {:status 200
       :body {:tasks tasks
              :count (count tasks)}})))

(defn create-task-handler
  "POST /api/v1/tasks - Create new task."
  [task-service]
  (fn [request]
    (let [user-id (get-in request [:identity :user-id])
          task-input (:body-params request)
          new-task (task/create-task user-id task-input)
          saved-task (ports/save-task! task-service new-task)]
      {:status 201
       :body saved-task})))

(defn get-task-handler
  "GET /api/v1/tasks/:id - Get task by ID."
  [task-service]
  (fn [request]
    (let [task-id (get-in request [:path-params :id])
          user-id (get-in request [:identity :user-id])
          task (ports/find-task-by-id task-service task-id)]
      (cond
        (nil? task)
        {:status 404
         :body {:error "Task not found"}}

        (not= (:user-id task) user-id)
        {:status 403
         :body {:error "Forbidden"}}

        :else
        {:status 200
         :body task}))))

(defn complete-task-handler
  "POST /api/v1/tasks/:id/complete - Mark task as complete."
  [task-service]
  (fn [request]
    (let [task-id (get-in request [:path-params :id])
          user-id (get-in request [:identity :user-id])
          task (ports/find-task-by-id task-service task-id)]
      (cond
        (nil? task)
        {:status 404
         :body {:error "Task not found"}}

        (not= (:user-id task) user-id)
        {:status 403
         :body {:error "Forbidden"}}

        :else
        (let [completed-task (task/complete-task task)
              saved-task (ports/save-task! task-service completed-task)]
          {:status 200
           :body saved-task})))))

(defn task-stats-handler
  "GET /api/v1/tasks/stats - Get task statistics."
  [task-service]
  (fn [request]
    (let [user-id (get-in request [:identity :user-id])
          stats (ports/task-stats task-service user-id)]
      {:status 200
       :body stats})))

;; Route definitions
(defn task-routes
  "Define task API routes."
  [task-service]
  [{:path "/tasks"
    :methods {:get {:handler (list-tasks-handler task-service)
                    :summary "List all tasks"
                    :tags ["tasks"]
                    :parameters {:query [:map
                                         [:completed {:optional true} :boolean]
                                         [:priority {:optional true} schema/Priority]]}}
              :post {:handler (create-task-handler task-service)
                     :summary "Create new task"
                     :tags ["tasks"]
                     :parameters {:body schema/TaskInput}
                     :responses {201 {:body schema/Task}}}}}

   {:path "/tasks/stats"
    :methods {:get {:handler (task-stats-handler task-service)
                    :summary "Get task statistics"
                    :tags ["tasks"]}}}

   {:path "/tasks/:id"
    :methods {:get {:handler (get-task-handler task-service)
                    :summary "Get task by ID"
                    :tags ["tasks"]
                    :parameters {:path [:map [:id :string]]}}
              :put {:handler (update-task-handler task-service)
                    :summary "Update task"
                    :tags ["tasks"]
                    :parameters {:path [:map [:id :string]]
                                 :body [:map
                                        [:title {:optional true} :string]
                                        [:description {:optional true} :string]
                                        [:priority {:optional true} schema/Priority]
                                        [:dueDate {:optional true} inst?]]}}
              :delete {:handler (delete-task-handler task-service)
                       :summary "Delete task"
                       :tags ["tasks"]
                       :parameters {:path [:map [:id :string]]}}}}

   {:path "/tasks/:id/complete"
    :methods {:post {:handler (complete-task-handler task-service)
                     :summary "Mark task as complete"
                     :tags ["tasks"]
                     :parameters {:path [:map [:id :string]]}}}}])
```

---

## Testing

### Unit Tests (Pure Functions)

**test/todo_api/task/core/task_test.clj:**
```clojure
(ns todo-api.task.core.task-test
  (:require [clojure.test :refer [deftest testing is]]
            [todo-api.task.core.task :as task]))

(deftest create-task-test
  (testing "creates task with required fields"
    (let [user-id (java.util.UUID/randomUUID)
          input {:title "Test task"}
          result (task/create-task user-id input)]
      (is (= "Test task" (:title result)))
      (is (= user-id (:user-id result)))
      (is (= false (:completed result)))
      (is (= :medium (:priority result)))))

  (testing "uses provided priority"
    (let [user-id (java.util.UUID/randomUUID)
          input {:title "High priority task" :priority :high}
          result (task/create-task user-id input)]
      (is (= :high (:priority result))))))

(deftest complete-task-test
  (testing "marks task as completed"
    (let [task {:id (java.util.UUID/randomUUID)
                :completed false}
          result (task/complete-task task)]
      (is (= true (:completed result)))))

  (testing "updates timestamp"
    (let [task {:id (java.util.UUID/randomUUID)
                :completed false
                :updated-at (java.time.Instant/parse "2026-01-01T00:00:00Z")}
          result (task/complete-task task)]
      (is (.isAfter (:updated-at result) (:updated-at task))))))
```

### Integration Tests (HTTP Endpoints)

**test/todo_api/integration_test.clj:**
```clojure
(ns todo-api.integration-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [todo-api.system :as system]
            [integrant.core :as ig]
            [clj-http.client :as http]))

(def test-system (atom nil))

(defn start-test-system []
  (reset! test-system (ig/init (system/system-config :test))))

(defn stop-test-system []
  (when @test-system
    (ig/halt! @test-system)))

(use-fixtures :once
  (fn [f]
    (start-test-system)
    (f)
    (stop-test-system)))

(deftest end-to-end-test
  (testing "complete task workflow"
    ;; Register user
    (let [register-resp (http/post "http://localhost:3000/api/v1/auth/register"
                                   {:content-type :json
                                    :form-params {:email "test@example.com"
                                                  :password "password123"
                                                  :name "Test User"}
                                    :as :json})
          user (:body register-resp)]

      ;; Login
      (let [login-resp (http/post "http://localhost:3000/api/v1/auth/login"
                                  {:content-type :json
                                   :form-params {:email "test@example.com"
                                                 :password "password123"}
                                   :as :json})
            token (get-in login-resp [:body :accessToken])]

        ;; Create task
        (let [task-resp (http/post "http://localhost:3000/api/v1/tasks"
                                   {:headers {"Authorization" (str "Bearer " token)}
                                    :content-type :json
                                    :form-params {:title "Test task"
                                                  :priority "high"}
                                    :as :json})
              task (:body task-resp)]

          (is (= 201 (:status task-resp)))
          (is (= "Test task" (:title task)))
          (is (= "high" (:priority task)))

          ;; Get task
          (let [get-resp (http/get (str "http://localhost:3000/api/v1/tasks/" (:id task))
                                   {:headers {"Authorization" (str "Bearer " token)}
                                    :as :json})]
            (is (= 200 (:status get-resp))))

          ;; Complete task
          (let [complete-resp (http/post (str "http://localhost:3000/api/v1/tasks/" (:id task) "/complete")
                                         {:headers {"Authorization" (str "Bearer " token)}
                                          :as :json})
                completed-task (:body complete-resp)]
            (is (= 200 (:status complete-resp)))
            (is (= true (:completed completed-task)))))))))
```

### Run Tests

```bash
# Run all tests
clojure -M:test

# Run specific test
clojure -M:test --focus todo-api.task.core.task-test

# Run with coverage
clojure -M:coverage
```

---

## Key Learnings

### 1. **FC/IS Pattern Benefits**

**Pure Core Logic:**
```clojure
;; Easy to test - no mocking needed
(deftest complete-task-test
  (let [task {:id "123" :completed false}
        result (task/complete-task task)]
    (is (= true (:completed result)))))
```

**Shell Handles Side Effects:**
```clojure
;; Database, HTTP, external services
(defn complete-task-handler [task-service]
  (fn [request]
    (let [task (ports/find-task-by-id task-service task-id)  ; I/O
          completed (task/complete-task task)                 ; Pure
          saved (ports/save-task! task-service completed)]    ; I/O
      {:status 200 :body saved})))
```

### 2. **Port-Based Architecture**

**Easy to swap implementations:**
```clojure
;; Production
(create-postgres-task-repository datasource)

;; Testing
(create-in-memory-task-repository)

;; Both implement ITaskRepository
```

### 3. **Schema-Driven Development**

**Single source of truth:**
```clojure
;; Schema defines data shape
(def TaskInput [:map [:title [:string {:min 1 :max 200}]]])

;; Reitit validates automatically
{:post {:parameters {:body schema/TaskInput}}}

;; Core functions are type-safe
```

---

## Next Steps

### Extend the Example

**1. Add Tags:**
```clojure
;; Modify schema
(def Task
  [:map
   ;; ... existing fields
   [:tags {:optional true} [:vector :string]]])

;; Implement filtering by tags
(defn find-tasks-by-tags [task-service user-id tags]
  ...)
```

**2. Add Subtasks:**
```clojure
(def Subtask
  [:map
   [:id uuid?]
   [:parent-task-id uuid?]
   [:title :string]
   [:completed boolean?]])
```

**3. Add Due Date Notifications:**
```clojure
(defn tasks-due-soon [task-service user-id]
  (filter #(and (:due-date %)
                (not (:completed %))
                (within-24-hours? (:due-date %)))
          (find-all-tasks task-service user-id)))
```

### Production Enhancements

**1. Add Pagination:**
```clojure
(defn list-tasks-handler [task-service]
  (fn [request]
    (let [limit (or (get-in request [:query-params :limit]) 20)
          offset (or (get-in request [:query-params :offset]) 0)]
      ...)))
```

**2. Add Sorting:**
```clojure
(defn sort-tasks [tasks sort-by]
  (case sort-by
    "due-date" (sort-by :due-date tasks)
    "priority" (sort-by :priority tasks)
    "created" (sort-by :created-at tasks)
    tasks))
```

**3. Add Full-Text Search:**
```clojure
(defn search-tasks [task-service user-id query]
  (find-tasks-by-user task-service user-id {:search query}))
```

---

## Deployment

See [Operations Guide](../../docs-site/content/guides/operations.adoc) for complete deployment guide.

**Quick Deploy:**
```bash
# Build JAR
clojure -T:build jar

# Deploy to production
java -jar target/todo-api-standalone.jar
```

**Docker:**
```bash
docker build -t todo-api .
docker run -p 3000:3000 \
  -e DATABASE_URL=postgresql://... \
  -e JWT_SECRET=... \
  todo-api
```

---

## Resources

- [Boundary Documentation](../../docs-site/)
- [Quickstart Guide](../../docs-site/content/getting-started/quickstart.md)
- [Full Tutorial](../../docs-site/content/getting-started/tutorial.md)
- [Operations Guide](../../docs-site/content/guides/operations.adoc)

---

**Congratulations!** You've completed the todo-api example. 🎉

**Next:** Try building the [blog example](../blog/) for a full-stack application.
