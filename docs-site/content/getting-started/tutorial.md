---
title: "Building a Task Management API"
weight: 2
description: "Comprehensive hands-on tutorial for building a production-ready Task Management API with Boundary Framework"
---

# Boundary Framework Tutorial: Building a Task Management API

Welcome to the comprehensive, hands-on tutorial for the Boundary Framework. In this tutorial, you will build a production-ready Task Management API from scratch. 

**Time:** 1-2 hours
**Level:** Beginner to Intermediate

---

## What You'll Build

We're going to build a "TaskMaster" API that allows users to:
1. Register and log in securely (JWT-based)
2. Create, read, update, and delete (CRUD) tasks
3. Categorize tasks with tags and priority
4. Validate all inputs using Malli schemas
5. Implement custom business rules in the Functional Core
6. Add observability with logging and metrics
7. Verify everything with a complete test suite
8. Handle advanced scenarios like soft deletes and audit logs

By the end of this tutorial, you'll have a deep understanding of the Functional Core / Imperative Shell (FC/IS) architecture and how to build scalable, maintainable Clojure applications with Boundary.

---

## Chapter 1: Introduction & Prerequisites

### What You'll Learn
- The core philosophy of the Boundary Framework
- System requirements and environment setup
- How to verify your installation
- The architecture of a Boundary module

### The Boundary Philosophy
Boundary is designed around the **Functional Core / Imperative Shell** paradigm. This means:
- **Functional Core**: All business logic is kept in pure, side-effect-free functions. These are incredibly easy to test and reason about. They take data and return data.
- **Imperative Shell**: All I/O (database, HTTP, logging) is kept at the edges of your system. This layer orchestrates the flow of data between the outside world and your pure logic.
- **Clean Boundaries**: Protocols (ports) define the contracts between these layers. This allows you to swap out implementations (e.g., switching from SQLite to PostgreSQL) without touching your business logic.

### Prerequisites

Before we begin, ensure you have the following installed:

- **Java 11+** (OpenJDK or Amazon Corretto)
- **Clojure CLI**
- **SQLite** (Default for development)
- **curl** (For testing endpoints)
- **A text editor** (VSCode with Calva, IntelliJ with Cursive, or Emacs with CIDER)

**Verify your environment:**

```bash
java -version   # Should show 11 or higher
clojure -version
curl --version
```bash

### The Anatomy of a Module
In Boundary, everything is organized into modules. A typical module looks like this:

```
src/boundary/task/
├── core/
│   ├── task.clj           # Pure business logic (FC)
│   └── ui.clj             # UI components (Hiccup)
├── shell/
│   ├── service.clj        # Orchestration layer (IS)
│   ├── http.clj           # HTTP handlers & routes
│   └── persistence.clj    # Database implementation
├── ports.clj              # Protocol definitions
└── schema.clj             # Malli validation schemas
```text

### Time Estimate: 10 minutes

---

## Chapter 2: Project Setup

### What You'll Learn
- How to initialize a new Boundary project
- Understanding the project structure
- Configuring your development environment
- Running initial migrations
- Starting the REPL

### Step 1: Clone the Framework
For this tutorial, we will use the main Boundary repository as our starting point.

```bash
git clone https://github.com/thijs-creemers/boundary.git task-master
cd task-master
```bash

### Step 2: Project Structure
Take a moment to explore the directory structure. Boundary is a monorepo with several libraries in the `libs/` directory.

```
.
├── AGENTS.md           # Quick reference for development
├── deps.edn            # Project dependencies
├── libs/               # Boundary library components
│   ├── core/           # Foundation: validation, utilities, interceptors
│   ├── observability/  # Logging, metrics, error reporting
│   ├── platform/       # HTTP, database, CLI infrastructure
│   ├── user/           # Authentication, authorization, MFA
│   ├── admin/          # Auto-CRUD admin interface
│   └── scaffolder/     # Module code generator
├── resources/
│   └── conf/           # Configuration files (Aero)
└── migrations/         # Database migrations (SQL)
```bash

### Step 3: Configure the Database
Boundary uses **Aero** for configuration. By default, it's set up to use SQLite in development, which requires no extra setup.

Check your `resources/conf/dev/config.edn`:
```clojure
{:boundary/db-context
 {:jdbc-url "jdbc:sqlite:dev-database.db"}}
```bash

Aero allows you to use tags like `#env`, `#include`, and `#merge` to create flexible, environment-aware configurations.

### Step 4: Run Initial Migrations
The `user` module comes with built-in migrations for user management and sessions. Let's apply them.

```bash
# Initialize migration system (creates the migrations table if it doesn't exist)
clojure -M:migrate init

# Run pending migrations (applies all .up.sql files)
clojure -M:migrate up

# Verify status
clojure -M:migrate status
```text

**Expected Output:**
```
Applied migrations:
- 20240101000000-create-users-table
- 20240101000001-create-sessions-table
```bash

### Step 5: Start the REPL
The REPL (Read-Eval-Print Loop) is the heart of Clojure development.

```bash
clojure -M:repl-clj
```text

Once the REPL starts (usually on port 7888), you can connect your editor to it. This allows you to evaluate code instantly without restarting the application.

### Checkpoint: Verification
At this point, you should have a `dev-database.db` file in your project root, and migrations should be successfully applied. Your REPL should be running and ready for input.

### Time Estimate: 15 minutes

---

## Chapter 3: User Authentication

### What You'll Learn
- How Boundary handles authentication
- Registering a new user via API
- Logging in to receive a JWT token
- Understanding the JWT lifecycle
- Securing endpoints with interceptors

### Step 1: Initialize the System
In your REPL, start the Integrant system:

```clojure
(require '[integrant.repl :as ig-repl])
(require '[boundary.system])

;; Tell Integrant how to find the config
(ig-repl/set-prep! #(boundary.system/system-config))

;; Start the system
(ig-repl/go)
```text

**Output:**
```
INFO  boundary.server - Starting HTTP server on port 3000
INFO  boundary.server - Swagger UI available at http://localhost:3000/api-docs/
INFO  boundary.server - Server started successfully
```bash

### Step 2: Register a New User
We'll use `curl` to create our first user account. This uses the `boundary/user` module.

```bash
curl -X POST http://localhost:3000/api/v1/users \
  -H "Content-Type: application/json" \
  -d '{
    "email": "tutorial@boundary.dev",
    "name": "Tutorial User",
    "password": "Password123!",
    "role": "admin"
  }'
```text

**Expected Response:**
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-1234567890ab",
  "email": "tutorial@boundary.dev",
  "name": "Tutorial User",
  "role": "admin",
  "active": true,
  "createdAt": "2026-01-26T10:00:00Z"
}
```bash

### Step 3: Login and Get Token
Now, let's authenticate to get a JWT (JSON Web Token).

```bash
curl -X POST http://localhost:3000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "tutorial@boundary.dev",
    "password": "Password123!"
  }'
```text

**Expected Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 3600,
  "userId": "a1b2c3d4-e5f6-7890-abcd-1234567890ab"
}
```bash

**Save that `accessToken`!** You'll need it as a Bearer token in the next steps.

### How it Works: Interceptors
Boundary uses **interceptors** for cross-cutting concerns like authentication. In `boundary/user/shell/http.clj`, you'll find an interceptor that validates the JWT:

```clojure
(def auth-interceptor
  {:name :auth
   :enter (fn [ctx]
            (let [token (get-in ctx [:request :headers "authorization"])]
              (if (valid-token? token)
                (assoc-in ctx [:request :identity] (decode-token token))
                (throw (ex-info "Unauthorized" {:type :unauthorized})))))})
```bash

By adding this interceptor to a route, you ensure that only authenticated users can access it.

### Time Estimate: 20 minutes

---

## Chapter 4: Generating the Task Module

### What You'll Learn
- Using the Boundary Scaffolder to generate production-ready code
- Understanding the generated file structure
- Wiring the new module into the system
- Customizing the generated schema

### Step 1: Scaffold the Module
Boundary's scaffolder generates all the boilerplate for a new module. It's not just a simple template; it generates a complete, functional module following best practices.

Run the following command:

```bash
clojure -M:dev -m boundary.scaffolder.shell.cli-entry generate \
  --module-name task \
  --entity Task \
  --field title:string:required \
  --field description:string \
  --field priority:string:required \
  --field due-date:instant \
  --field completed:boolean:required
```bash

**What happened?**
The scaffolder created 12 files and generated 473 tests!

### Step 2: Explore the Generated Files
Let's look at the key files in `libs/task/src/boundary/task/`:

#### `schema.clj`
This file defines the data structure of your entity using **Malli**.

```clojure
(def task-schema
  [:map
   [:id :uuid]
   [:title [:string {:min 1 :max 255}]]
   [:description {:optional true} [:maybe :string]]
   [:priority [:string {:min 1}]]
   [:due-date {:optional true} [:maybe inst?]]
   [:completed :boolean]
   [:created-at inst?]
   [:updated-at inst?]])
```bash

#### `core/task.clj`
This is your **Functional Core**. It contains pure functions for transforming task data.

```clojure
(defn prepare-task [task-data]
  (let [now (java.time.Instant/now)]
    (merge task-data
           {:id (random-uuid)
            :created-at now
            :updated-at now})))
```bash

#### `shell/service.clj`
The **Imperative Shell**. It orchestrates the process of creating a task:
1. Validates the input data
2. Calls the core to prepare the entity
3. Calls the persistence port to save it to the database

### Step 3: Apply the New Migration
The scaffolder generated a SQL migration in `resources/migrations/`.

```bash
clojure -M:migrate up
```bash

### Step 4: Wire it Up
Currently, the system doesn't know about our new module. We need to add it to the system configuration.

Open `src/boundary/system.clj` and add the `task` module to the Integrant configuration and routes.

**In `src/boundary/system.clj`:**

```clojure
(require '[boundary.task.shell.http :as task-http]
         '[boundary.task.shell.module-wiring])

;; Add to routes:
(defn all-routes [config]
  (concat
    (user-http/user-routes config)
    (task-http/task-routes config))) ;; Add this line
```bash

### Step 5: Refresh the REPL
Go back to your REPL and reload the system:

```clojure
(ig-repl/reset)
```bash

### Time Estimate: 25 minutes

---

## Chapter 5: CRUD Operations

### What You'll Learn
- Creating tasks via the API
- Reading and listing tasks
- Updating and deleting tasks
- Using the JWT token for authorized requests
- Understanding the HTTP response codes

### Step 1: Create a Task
Now let's use our API to create a task. Replace `YOUR_TOKEN` with the `accessToken` you received in Chapter 3.

```bash
curl -X POST http://localhost:3000/api/v1/tasks \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "title": "Complete Boundary Tutorial",
    "description": "Build the TaskMaster API",
    "priority": "high",
    "completed": false
  }'
```text

**Response (201 Created):**
```json
{
  "id": "f8d7e6d5-...",
  "title": "Complete Boundary Tutorial",
  "description": "Build the TaskMaster API",
  "priority": "high",
  "completed": false,
  "createdAt": "2026-01-26T11:00:00Z"
}
```bash

### Step 2: List All Tasks
Boundary supports built-in pagination.

```bash
curl -H "Authorization: Bearer YOUR_TOKEN" \
  "http://localhost:3000/api/v1/tasks?limit=10&offset=0"
```text

**Expected Response:**
```json
{
  "items": [...],
  "total": 1,
  "limit": 10,
  "offset": 0
}
```bash

### Step 3: Get a Single Task
Use the `id` from the previous response:

```bash
curl -H "Authorization: Bearer YOUR_TOKEN" \
  http://localhost:3000/api/v1/tasks/TASK_ID
```bash

### Step 4: Update a Task
Let's mark it as completed! Notice how we only send the fields we want to change.

```bash
curl -X PUT http://localhost:3000/api/v1/tasks/TASK_ID \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "completed": true
  }'
```bash

### Step 5: Delete a Task
```bash
curl -X DELETE http://localhost:3000/api/v1/tasks/TASK_ID \
  -H "Authorization: Bearer YOUR_TOKEN"
```text

### Checkpoint: Persistence
Verify that your changes are actually saved by checking the list of tasks after an update or delete.

### Time Estimate: 20 minutes

---

## Chapter 6: Validation & Error Handling

### What You'll Learn
- How Malli schemas enforce data integrity
- Triggering validation errors
- Understanding Boundary's error response format
- Adding custom validation constraints

### Step 1: Trigger a Validation Error
Let's try to create a task without a `title`, which we marked as `required`.

```bash
curl -X POST http://localhost:3000/api/v1/tasks \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "description": "Missing title",
    "priority": "low",
    "completed": false
  }'
```text

**Expected Response (400 Bad Request):**
```json
{
  "type": "validation-error",
  "message": "Validation failed",
  "errors": {
    "title": ["is required"]
  }
}
```text

Boundary's error interceptor automatically catches validation exceptions and formats them into a clean JSON response.

### Step 2: Custom Validation Rules
Open `libs/task/src/boundary/task/schema.clj`. Let's add a rule that the `priority` must be one of: `low`, `medium`, or `high`.

```clojure
(def task-priority-schema
  [:enum "low" "medium" "high"])

;; Update task-schema:
(def task-schema
  [:map
   ;; ... other fields
   [:priority task-priority-schema]])
```bash

### Step 3: Test the New Rule
After updating the schema and refreshing the REPL (`(ig-repl/reset)`), try sending an invalid priority:

```bash
curl -X POST http://localhost:3000/api/v1/tasks \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "title": "Invalid Priority",
    "priority": "super-high",
    "completed": false
  }'
```bash

### Step 4: Cross-Field Validation
Sometimes validation depends on multiple fields. For example, a `due-date` must be in the future.

```clojure
(def create-task-schema
  [:and
   [:map
    [:title :string]
    [:due-date {:optional true} inst?]]
   [:fn {:error/message "Due date must be in the future"}
    (fn [{:keys [due-date]}]
      (if due-date
        (.isAfter due-date (java.time.Instant/now))
        true))]])
```bash

### Time Estimate: 20 minutes

---

## Chapter 7: Business Logic in the Functional Core

### What You'll Learn
- Why keep logic in the core?
- Implementing a "complete task" business rule
- Testing business logic in isolation

### Step 1: Define the Rule
Imagine we have a rule that when a task is marked as completed, we also want to record the completion date. This is business logic and belongs in `core/task.clj`.

```clojure
(defn complete-task [task]
  (-> task
      (assoc :completed true)
      (assoc :completed-at (java.time.Instant/now))
      (assoc :updated-at (java.time.Instant/now))))
```bash

### Step 2: Update the Shell
In `shell/service.clj`, we update the `update-task` method to use this new core function:

```clojure
(defn update-task [this id updates]
  (let [existing (ports/get-task repository id)]
    (if (and (:completed updates) (not (:completed existing)))
      (let [completed-task (task-core/complete-task existing)]
        (ports/save-task repository completed-task))
      ;; ... normal update logic
      )))
```bash

### Step 3: Why This Pattern?
By keeping `complete-task` in the core, we can test it with simple Clojure maps, without needing a database, an HTTP server, or even the Integrant system. This isolation is what makes Boundary applications so robust and maintainable over time.

### Time Estimate: 15 minutes

---

## Chapter 8: Testing

### What You'll Learn
- Running unit tests for pure core logic
- Running integration tests for services
- Running contract tests for the database
- Understanding the three-tier testing strategy

### Step 1: The Three-Tier Strategy
Boundary uses a specific testing strategy to maximize coverage and speed:
1. **Unit Tests** (:unit): Test the Functional Core (pure functions). Ultra-fast, no I/O.
2. **Integration Tests** (:integration): Test the Shell services using mocks for ports.
3. **Contract Tests** (:contract): Test the Adapters (database, API clients) against real infrastructure.

### Step 2: Run All Tests
```bash
clojure -M:test:db/h2
```bash

### Step 3: Run Unit Tests Only
These are the tests you'll run most frequently during development.

```bash
clojure -M:test:db/h2 --focus-meta :unit
```bash

### Step 4: Writing a Unit Test
Open `libs/task/test/boundary/task/core/task_test.clj`.

```clojure
(deftest complete-task-test
  (testing "marks task as completed and sets timestamp"
    (let [task {:id "123" :completed false}
          result (task-core/complete-task task)]
      (is (= true (:completed result)))
      (is (inst? (:completed-at result))))))
```bash

### Step 5: Testing with the REPL
You can also run tests directly from your REPL:

```clojure
(require '[clojure.test :refer [run-tests]])
(require '[boundary.task.core.task-test])
(run-tests 'boundary.task.core.task-test)
```text

### Time Estimate: 20 minutes

---

## Chapter 9: Observability

### What You'll Learn
- Structured logging with `boundary/observability`
- Adding custom metrics
- Viewing error reports
- Using correlation IDs for request tracing

### Step 1: Structured Logging
Boundary doesn't just print strings; it logs data. This makes logs searchable and indexable.

```clojure
(require '[boundary.observability.ports :as obs])

(obs/info logger "Task created" {:task-id id :user-id user-id})
```bash

These logs are automatically enriched with correlation IDs, timestamps, and environment info.

### Step 2: Custom Metrics
You can easily track performance or business metrics.

```clojure
(obs/increment-counter metrics "tasks.created" 1 {:priority "high"})
```text

In production, these metrics can be pushed to Prometheus, Datadog, or CloudWatch.

### Step 3: Error Reporting
When an exception occurs, Boundary's interceptor automatically reports it to your error tracking service (like Sentry).

```clojure
(try
  (do-something-risky)
  (catch Exception e
    (obs/report-error error-reporter e {:context "risk-calculation"})))
```bash

### Time Estimate: 15 minutes

---

## Chapter 10: Advanced Scenarios

In this chapter, we'll dive deep into real-world requirements that often come up after the basic CRUD is in place. We'll implement soft deletes and a custom audit log interceptor.

### Step 1: Implementing Soft Deletes

Soft deletes allow you to "delete" a record by marking it with a timestamp instead of removing it from the database. This is vital for data recovery and auditability.

#### 1. Update the Schema
First, open `libs/task/src/boundary/task/schema.clj` and add the `deleted-at` field.

```clojure
(def task-schema
  [:map
   [:id :uuid]
   [:title [:string {:min 1 :max 255}]]
   [:description {:optional true} [:maybe :string]]
   [:priority [:string {:min 1}]]
   [:due-date {:optional true} [:maybe inst?]]
   [:completed :boolean]
   [:deleted-at {:optional true} [:maybe inst?]] ;; Add this
   [:created-at inst?]
   [:updated-at inst?]])
```bash

#### 2. Functional Core Logic
In `libs/task/src/boundary/task/core/task.clj`, add the logic to "delete" a task.

```clojure
(defn mark-as-deleted [task]
  (assoc task :deleted-at (java.time.Instant/now)))
```bash

#### 3. Persistence Implementation
In `libs/task/src/boundary/task/shell/persistence.clj`, we need to update two things:
- The delete method should now be an update.
- The list/find methods should exclude deleted records.

```clojure
;; In SqliteTaskRepository record:

(delete-task [this id]
  (let [existing (ports/get-task this id)]
    (when existing
      (let [deleted (task-core/mark-as-deleted existing)]
        (jdbc/execute-one! ds 
          ["UPDATE tasks SET deleted_at = ? WHERE id = ?" 
           (str (:deleted-at deleted)) 
           (str id)])))))

(list-tasks [this params]
  (jdbc/execute! ds 
    ["SELECT * FROM tasks WHERE deleted_at IS NULL LIMIT ? OFFSET ?" 
     (:limit params) 
     (:offset params)]))
```bash

### Step 2: Custom Audit Log Interceptor

Audit logs track who did what and when. Instead of sprinkling logging code everywhere, we can use an interceptor to handle this automatically for specific routes.

#### 1. Define the Interceptor
Create a new file (or add to your shell namespace) for the interceptor:

```clojure
(defn audit-log-interceptor [action]
  {:name :audit-log
   :leave (fn [ctx]
            (let [user-id (get-in ctx [:request :identity :user-id])
                  status  (get-in ctx [:response :status])
                  body    (get-in ctx [:response :body])]
              ;; Only log successful operations
              (when (and user-id (<= 200 status 299))
                (println "AUDIT LOG:" 
                         {:action action
                          :user-id user-id
                          :timestamp (java.time.Instant/now)
                          :payload (select-keys body [:id :title])}))
              ctx))})
```bash

#### 2. Apply it to Routes
In `libs/task/src/boundary/task/shell/http.clj`, wrap your handlers with the interceptor:

```clojure
(def task-routes
  ["/tasks"
   {:interceptors [auth-interceptor]}
   ["" {:post {:handler    create-handler
               :interceptors [(audit-log-interceptor :create-task)]}}]
   ["/:id" {:put {:handler   update-handler
                  :interceptors [(audit-log-interceptor :update-task)]}
            :delete {:handler delete-handler
                     :interceptors [(audit-log-interceptor :delete-task)]}}]])
```bash

Now, every time a task is created or updated, a structured audit log entry will be generated!

---

## Chapter 11: UI Development with HTMX & Hiccup

Boundary isn't just for APIs; it's a full-stack framework. We use **Hiccup** for server-side HTML and **HTMX** for dynamic interactions without writing complex JavaScript.

### Step 1: The UI Component
In `libs/task/src/boundary/task/core/ui.clj`, define a task list component.

```clojure
(ns boundary.task.core.ui
  (:require [boundary.shared.ui.core.icons :as icons]))

(defn task-item [task]
  [:li {:id (str "task-" (:id task))}
   [:span {:style (when (:completed task) "text-decoration: line-through")}
    (:title task)]
   [:button {:hx-post (str "/web/tasks/" (:id task) "/toggle")
             :hx-target (str "#task-" (:id task))
             :hx-swap "outerHTML"}
    (if (:completed task) "Undo" "Complete")]])

(defn task-list [tasks]
  [:div.container
   [:h1 "Your Tasks"]
   [:ul#task-list
    (for [t tasks]
      (task-item t))]
   [:form {:hx-post "/web/tasks" :hx-target "#task-list" :hx-swap "beforeend"}
    [:input {:name "title" :placeholder "New task..."}]
    [:button "Add Task"]]])
```bash

### Step 2: The Web Handler
In `libs/task/src/boundary/task/shell/http.clj`, add a handler that returns HTML instead of JSON.

```clojure
(defn render-tasks-handler [request]
  (let [tasks (ports/list-tasks repository {:limit 100 :offset 0})]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (ui/task-list tasks)}))

(defn toggle-task-handler [request]
  (let [id (-> request :path-params :id)
        task (ports/get-task repository id)
        updated (task-core/toggle-complete task)]
    (ports/save-task repository updated)
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (ui/task-item updated)}))
```bash

### Step 3: Why HTMX?
Notice the `:hx-post` and `:hx-target` attributes. These tell HTMX to:
1. Make an AJAX request when the button is clicked.
2. Replace only the specific task item in the DOM with the HTML returned by the server.

This gives you a "Single Page App" feel with 100% server-side code. No React, no build step, no pain.

### Step 4: Design Tokens & Styling
Boundary uses a "Design Token" approach for styling, centralized in `resources/public/css/tokens.css`. This ensures consistency across the app.

**Using Tokens in CSS:**
```css
.task-item {
  padding: var(--spacing-md);
  background-color: var(--color-surface);
  border-radius: var(--radius-sm);
  margin-bottom: var(--spacing-sm);
}
```text

**Using Icons:**
Instead of emojis, use the built-in Lucide icon library.
```clojure
[:button
 (icons/icon :trash {:size 18})
 " Delete"]
```bash

---

## Chapter 12: Advanced Testing

We've covered unit tests, but production systems need more. Let's look at **Contract Tests** and **Property-Based Testing**.

### Step 1: Contract Tests (Database)
Contract tests ensure your adapters (like SQLite) correctly implement the protocols. They run against a real database instance, verifying that your SQL queries behave as expected.

```clojure
(ns boundary.task.shell.persistence-contract-test
  (:require [clojure.test :refer :all]
            [boundary.task.ports :as ports]
            [boundary.task.shell.persistence :as persistence]
            [next.jdbc :as jdbc]))

(defn get-test-datasource []
  (jdbc/get-datasource "jdbc:sqlite::memory:"))

(deftest sqlite-contract-test
  (let [ds (get-test-datasource)
        _ (run-migrations! ds) ;; Ensure schema is present
        repo (persistence/->SqliteTaskRepository ds)]
    (testing "can save and retrieve a task"
      (let [task {:id (random-uuid) 
                  :title "Contract Test" 
                  :completed false
                  :created-at (java.time.Instant/now)
                  :updated-at (java.time.Instant/now)}]
        (ports/save-task repo task)
        (let [retrieved (ports/get-task repo (:id task))]
          (is (= "Contract Test" (:title retrieved)))
          (is (false? (:completed retrieved))))))))
```bash

### Step 2: Property-Based Testing
Using `test.check`, we can test that our code works for *any* valid input, not just the ones we thought of. This is especially useful for complex validation logic or mathematical calculations.

#### 1. Define a Generator
Generators describe how to produce random data that follows your schema.

```clojure
(require '[clojure.test.check.generators :as gen])

(def task-gen
  (gen/hash-map
    :id gen/uuid
    :title (gen/not-empty gen/string-alphanumeric)
    :priority (gen/elements ["low" "medium" "high"])
    :completed (gen/return false)))
```bash

#### 2. Define the Property
A property is a claim that should hold true for all generated values.

```clojure
(require '[clojure.test.check.properties :as prop])

(def complete-task-property
  (prop/for-all [task task-gen]
    (let [result (task-core/complete-task task)]
      (and (:completed result)
           (inst? (:completed-at result))
           (= (:id task) (:id result))))))
```bash

#### 3. Run the Check
```clojure
(require '[clojure.test.check :as tc])
(tc/quick-check 100 complete-task-property)
;; => {:result true, :num-tests 100, :seed 173788...}
```bash

---

## Chapter 13: Production Deployment

Moving from "it works on my machine" to "it's live" requires a few extra steps.

### Step 1: The Uberjar
Clojure apps are typically deployed as an **Uberjar**—a single `.jar` file containing your code and all its dependencies.

```bash
clojure -T:build clean
clojure -T:build uber
```bash

### Step 2: Configuration Profiles
Use Aero's `#profile` tag to vary configuration by environment.

```clojure
;; resources/conf/config.edn
{:boundary/db-context
 {:jdbc-url #profile {:dev "jdbc:sqlite:dev.db"
                      :prod #env DATABASE_URL}}}
```bash

### Step 3: Running in Production
```bash
# Set environment
export BND_ENV=prod
export DATABASE_URL="jdbc:postgresql://db.example.com:5432/myapp"

# Start the app
java -jar target/boundary-standalone.jar server
```bash

---

## Chapter 14: Troubleshooting & FAQ

### REPL Issues
**Q: I changed a file but the REPL doesn't see the change.**
**A:** Use `(ig-repl/reset)`. This stops the system, reloads all changed namespaces, and restarts the system. If you changed a `defrecord`, you might need to `(ig-repl/halt)` and `(ig-repl/go)` to ensure the new record definition is used.

### Database Issues
**Q: "Database is locked" (SQLite).**
**A:** This usually happens when multiple processes are trying to write to SQLite at once. Ensure you don't have multiple REPLs or servers running against the same `.db` file.

**Q: Migration failed midway.**
**A:** Boundary's migration tool is transactional. Check the `migrations` table to see what was applied. You can manually fix the state with `clojure -M:migrate rollback` if necessary.

### JWT Issues
**Q: My token is rejected with "Expired".**
**A:** Check your system clock. If you're running in a VM or Docker, the time might have drifted. Also, ensure your `JWT_SECRET` is at least 32 characters long.

---

## Chapter 15: Glossary of Terms

| Term | Definition |
|------|------------|
| **Functional Core (FC)** | The part of your code that contains pure functions and business logic. |
| **Imperative Shell (IS)** | The part of your code that handles side effects (I/O, DB, HTTP). |
| **Integrant** | A micro-framework for data-driven system configuration and lifecycle management. |
| **Aero** | A small library for configuring Clojure applications using EDN files. |
| **Malli** | A high-performance data validation and specification library for Clojure. |
| **Port** | A Clojure Protocol defining an interface (the "what"). |
| **Adapter** | A record implementing a Protocol (the "how" for a specific technology). |
| **Interceptor** | A function that can intercept the request/response flow (middleware). |
| **Uberjar** | A single JAR file containing an application and all its dependencies. |
| **Hiccup** | A library for representing HTML as Clojure data structures (vectors and maps). |
| **HTMX** | A library that allows you to access AJAX, CSS Transitions, WebSockets and Server Sent Events directly in HTML. |

---

## Chapter 16: Appendix - Common Pitfalls & Anti-Patterns

As you grow with Boundary, keep an eye out for these common "gotchas" that can lead to messy codebases.

### 1. Putting I/O in the Core
**The Mistake:**
```clojure
;; ❌ BAD - Core function calling the database
(ns boundary.task.core.task
  (:require [boundary.task.shell.persistence :as db]))

(defn create-if-valid [data]
  (if (valid? data)
    (db/save data)
    (throw ...)))
```bash
**The Fix:**
Core should only return data. Let the Shell decide what to do with that data (e.g., save it).

### 2. Snake_case in Clojure
**The Mistake:**
```clojure
;; ❌ BAD - Using snake_case for keys
{:user_id "123" :first_name "John"}
```
**The Fix:**
Always use `kebab-case` internally. Convert to `snake_case` only at the database boundary or `camelCase` at the API boundary using Boundary's built-in utilities.

### 3. Ignoring the REPL
**The Mistake:**
Making changes and then running `clojure -M:test` or restarting the whole app to see them.
**The Fix:**
Keep your REPL connected. Evaluate individual functions (Cmd+Enter in Calva). Use `(ig-repl/reset)` to pick up configuration changes. This is the "Clojure Way" and will make you 10x faster.

### 4. Oversized Modules
**The Mistake:**
Putting everything into a single `task` module—including user management, billing, and notifications.
**The Fix:**
Follow the Single Responsibility Principle. If it's a different domain entity with different business rules, it probably belongs in its own module.

---

## Chapter 17: Community & Contribution

Boundary is an evolving framework, and we welcome your input!

### How to Contribute
1. **Report Bugs**: Open an issue on GitHub if you find something broken.
2. **Improve Docs**: Submit a PR if you find a typo or want to clarify a section.
3. **Build Modules**: Share the reusable modules you build with the community.

### Final words
You are now equipped to build sophisticated, high-performance web applications with Clojure. Remember: **Keep your core pure, your shell thin, and your boundaries clean.**

**Happy Coding with Boundary!** 🚀

---

## See also

- [Quickstart Guide](quickstart.md) - Get started in 5 minutes
- [Authentication Guide](../guides/authentication.md) - JWT auth, sessions, and MFA
- [Testing Guide](../guides/testing.md) - Testing strategies and best practices
- [Admin Testing](../guides/admin-testing.md) - Testing the admin UI
- [Database Setup](../guides/database-setup.md) - PostgreSQL configuration

