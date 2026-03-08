---
title: "Your First Module: Build a Blog"
weight: 10
---

# Your First Module: Build a Blog

**Time:** 30 minutes  
**Goal:** Build a complete blog module from scratch using Boundary's scaffolder and understand the Functional Core / Imperative Shell architecture in practice.

## What You'll Build

A production-ready blog module with:
- Posts with title, body, author, and published status
- Full CRUD operations (Create, Read, Update, Delete)
- Business logic for publishing posts
- REST API endpoints
- Database schema and migrations
- Comprehensive tests (unit, integration, contract)

## What You'll Learn

- ✅ Using the module scaffolder to generate production code
- ✅ Understanding FC/IS separation in practice
- ✅ Writing pure business logic (testable without mocks)
- ✅ Testing at different layers
- ✅ Adding custom business rules
- ✅ Working with HTTP endpoints

## Prerequisites

- Completed the [5-Minute Quickstart](../guides/quickstart) (system running)
- Basic Clojure knowledge (functions, maps, let)
- Terminal with access to `clojure` command

---

## Step 1: Scaffold the Blog Module

### Generate the Module

From your Boundary project directory, run the interactive wizard:

```bash
bb scaffold generate
```

The wizard guides you through each option and shows a preview before generating anything. Alternatively, pass all arguments directly for a non-interactive run:

```bash
bb scaffold generate \
  --module-name blog \
  --entity Post \
  --field title:string:required \
  --field body:text:required \
  --field author-id:uuid:required \
  --field published-at:date \
  --field slug:string:required:unique
```

**Expected Output:**

```
🏗️  Boundary Module Scaffolder
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📋 Configuration:
   Module: blog
   Entity: Post
   Fields: 5 fields (4 required, 1 optional, 1 unique)

🔨 Generating module structure...

✅ Created libs/blog/src/boundary/blog/schema.clj
✅ Created libs/blog/src/boundary/blog/ports.clj
✅ Created libs/blog/src/boundary/blog/core/post.clj
✅ Created libs/blog/src/boundary/blog/core/ui.clj
✅ Created libs/blog/src/boundary/blog/shell/service.clj
✅ Created libs/blog/src/boundary/blog/shell/persistence.clj
✅ Created libs/blog/src/boundary/blog/shell/http.clj
✅ Created libs/blog/src/boundary/blog/shell/web_handlers.clj
✅ Created migrations/009_create_posts.sql
✅ Created libs/blog/test/boundary/blog/core/post_test.clj
✅ Created libs/blog/test/boundary/blog/shell/post_repository_test.clj
✅ Created libs/blog/test/boundary/blog/shell/service_test.clj

📊 Summary:
   Files generated: 12
   Lines of code: 847
   Test coverage: 100% (21 tests generated)
   
🎉 Module 'blog' generated successfully!

📝 Next steps:
   1. Review generated code in libs/blog/src/boundary/blog/
   2. Run tests: clojure -M:test:db/h2 --focus-meta :blog
   3. Wire module into system (see documentation)
```

### What Just Happened?

The scaffolder created **12 production-ready files** in seconds:

```
Generated files:
✅ libs/blog/src/boundary/blog/schema.clj              - Malli schemas
✅ libs/blog/src/boundary/blog/ports.clj               - Protocol definitions
✅ libs/blog/src/boundary/blog/core/post.clj          - Pure business logic
✅ libs/blog/src/boundary/blog/core/ui.clj            - UI components (Hiccup)
✅ libs/blog/src/boundary/blog/shell/service.clj      - Service orchestration
✅ libs/blog/src/boundary/blog/shell/persistence.clj  - Database adapter
✅ libs/blog/src/boundary/blog/shell/http.clj         - REST routes
✅ libs/blog/src/boundary/blog/shell/web_handlers.clj - Web UI handlers
✅ migrations/009_create_posts.sql          - Database schema
✅ libs/blog/test/boundary/blog/core/post_test.clj    - Unit tests
✅ libs/blog/test/boundary/blog/shell/post_repository_test.clj - Persistence tests
✅ libs/blog/test/boundary/blog/shell/service_test.clj - Service tests
```

**Key Achievement:** Zero manual boilerplate. Everything follows FC/IS architecture automatically.

---

## Step 2: Understand the Core Layer

### Explore Pure Business Logic

Open `libs/blog/src/boundary/blog/core/post.clj`:

```clojure
(ns boundary.blog.core.post
  "Pure business logic for blog posts.
   
   All functions are pure (no side effects, deterministic).
   Testable without mocks, databases, or external services.")

(defn prepare-post-for-creation
  "Pure function to prepare post for database insertion.
   
   Args:
     post-data - Raw post data from request
     current-time - java.time.Instant for timestamps
     
   Returns:
     Post entity map with generated ID and timestamps
     
   Pure: true (no side effects, deterministic)"
  [post-data current-time]
  {:id (java.util.UUID/randomUUID)
   :title (:title post-data)
   :body (:body post-data)
   :author-id (:author-id post-data)
   :slug (:slug post-data)
   :published-at (:published-at post-data)
   :created-at current-time
   :updated-at current-time
   :active true})
```

**Key Insights:**

1. **Pure Function** - No database calls, no HTTP, no side effects
2. **Testable** - Pass in data, get data out. No mocks needed.
3. **Deterministic** - Same inputs always produce same output
4. **Reusable** - Can be called from REST API, CLI, or background job

### Why This Matters

**Traditional Mixed Approach:**
```python
# ❌ Business logic mixed with I/O
def create_post(request):
    post = Post.objects.create(  # Database I/O mixed with logic
        title=request.POST['title'],
        body=request.POST['body'],
        created_at=timezone.now()
    )
    # Testing requires database mock!
    return JsonResponse({'id': post.id})
```

**Boundary's FC/IS Approach:**
```clojure
;; ✅ Pure logic (core layer)
(defn prepare-post-for-creation [data time]
  {:id (random-uuid) :title (:title data) ...})

;; ✅ I/O orchestration (shell layer)
(defn create-post-service [this post-data]
  (let [prepared (post-core/prepare-post-for-creation 
                   post-data 
                   (Instant/now))]
    (.create-post repository prepared)))

;; Test pure logic - no mocks!
(deftest prepare-post-test
  (is (= "My Post" (:title (prepare-post-for-creation 
                            {:title "My Post"} 
                            (Instant/now))))))
```

---

## Step 3: Run the Generated Tests

### Verify Everything Works

```bash
# Run all blog module tests
clojure -M:test:db/h2 --focus-meta :blog
```

**Expected Output:**

```
🧪 Kaocha Test Runner
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Running tests in #{"test"} with focus-meta [:blog]

Testing boundary.blog.core.post-test
  ✓ prepare-post-for-creation-test
    ✓ generates ID and timestamps
    ✓ sets active flag to true
    ✓ preserves input fields
  ✓ validate-post-data-test
    ✓ accepts valid post data
    ✓ rejects missing title
    ✓ rejects empty body

Testing boundary.blog.shell.post-repository-test
  ✓ create-post-test
    ✓ inserts post into database
    ✓ returns post with generated ID
  ✓ find-post-by-id-test
    ✓ returns post when exists
    ✓ returns nil when not found
  ✓ list-posts-test
    ✓ returns paginated results

Testing boundary.blog.shell.service-test
  ✓ create-post-service-test
    ✓ creates post successfully
    ✓ validates required fields
    ✓ handles duplicate slugs

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 Test Summary:
   Ran 15 tests containing 42 assertions
   ✅ 15 passed  ❌ 0 failed  ⚠️  0 errors
   Duration: 847ms

🎉 All tests passed!
```

✅ **100% passing tests** - generated code is production-ready!

### Understand the Test Structure

**Unit Tests** (core layer - fast, no mocks):
```clojure
;; libs/blog/test/boundary/blog/core/post_test.clj
(deftest prepare-post-for-creation-test
  (testing "generates ID and timestamps"
    (let [post-data {:title "Test Post" 
                     :body "Content"
                     :author-id (random-uuid)
                     :slug "test-post"}
          current-time (Instant/now)
          result (post-core/prepare-post-for-creation post-data current-time)]
      
      (is (uuid? (:id result)))
      (is (= "Test Post" (:title result)))
      (is (= current-time (:created-at result)))
      (is (true? (:active result))))))
```

**Why This Test Is Fast:**
- ✅ No database setup required
- ✅ No mocks or stubs needed
- ✅ Runs in milliseconds
- ✅ Tests pure business logic in isolation

---

## Step 4: Add Custom Business Logic

### Implement "Publish Post" Feature

Let's add a publishing feature with business rules. Edit `libs/blog/src/boundary/blog/core/post.clj`:

```clojure
(defn can-publish?
  "Determine if post can be published.
   
   Business Rules:
   - Title must be non-empty
   - Body must be at least 100 characters
   - Must not already be published
   - Must have valid slug
   
   Args:
     post - Post entity map
     
   Returns:
     {:can-publish? boolean
      :reasons [string]}
      
   Pure: true"
  [post]
  (let [reasons (cond-> []
                  (or (nil? (:title post))
                      (clojure.string/blank? (:title post)))
                  (conj "Title is required")
                  
                  (< (count (:body post)) 100)
                  (conj "Body must be at least 100 characters")
                  
                  (:published-at post)
                  (conj "Post is already published")
                  
                  (or (nil? (:slug post))
                      (clojure.string/blank? (:slug post)))
                  (conj "Slug is required"))]
    {:can-publish? (empty? reasons)
     :reasons reasons}))

(defn publish-post
  "Prepare post for publication.
   
   Args:
     post - Post entity
     current-time - Publication timestamp
     
   Returns:
     Updated post with published-at set
     
   Pure: true"
  [post current-time]
  (assoc post 
         :published-at current-time
         :updated-at current-time))
```

### Test the New Logic

Add tests in `libs/blog/test/boundary/blog/core/post_test.clj`:

```clojure
(deftest can-publish-test
  (testing "allows valid posts"
    (let [post {:title "Valid Post"
                :body (apply str (repeat 20 "word "))
                :slug "valid-post"
                :published-at nil}
          result (post-core/can-publish? post)]
      (is (:can-publish? result))
      (is (empty? (:reasons result)))))
  
  (testing "rejects post with short body"
    (let [post {:title "Valid" 
                :body "Too short" 
                :slug "valid"
                :published-at nil}
          result (post-core/can-publish? post)]
      (is (not (:can-publish? result)))
      (is (some #(re-find #"100 characters" %) (:reasons result)))))
  
  (testing "rejects already published post"
    (let [post {:title "Valid"
                :body (apply str (repeat 20 "word "))
                :slug "valid"
                :published-at (java.time.Instant/now)}
          result (post-core/can-publish? post)]
      (is (not (:can-publish? result)))
      (is (some #(re-find #"already published" %) (:reasons result))))))

(deftest publish-post-test
  (testing "sets publication timestamp"
    (let [post {:title "Test" 
                :body "Content" 
                :published-at nil}
          publish-time (java.time.Instant/now)
          result (post-core/publish-post post publish-time)]
      (is (= publish-time (:published-at result)))
      (is (= publish-time (:updated-at result))))))
```

### Run Your Tests

```bash
clojure -M:test:db/h2 -n boundary.blog.core.post-test
```

**Result:** ✅ All tests pass! You just added business logic with **zero mocking**.

---

## Step 5: Wire Business Logic into Service

### Add Service Method

Edit `libs/blog/src/boundary/blog/shell/service.clj`:

```clojure
(ns boundary.blog.shell.service
  (:require [boundary.blog.core.post :as post-core]
            [boundary.blog.ports :as ports]))

(defrecord PostService [post-repository]
  ports/IPostService
  
  ;; ... existing methods ...
  
  (publish-post [this post-id]
    "Publish a blog post (orchestrates I/O).
     
     Shell layer: performs I/O, calls pure core logic."
    (let [post (.find-by-id post-repository post-id)]
      
      ;; Validate post exists
      (when-not post
        (throw (ex-info "Post not found" 
                        {:type :not-found 
                         :post-id post-id})))
      
      ;; Use pure core logic for validation
      (let [check (post-core/can-publish? post)]
        (when-not (:can-publish? check)
          (throw (ex-info "Cannot publish post" 
                          {:type :validation-error
                           :reasons (:reasons check)}))))
      
      ;; Use pure core logic for transformation
      (let [published (post-core/publish-post post (java.time.Instant/now))]
        (.update-post post-repository published)
        published))))
```

### Add to Protocol

Edit `libs/blog/src/boundary/blog/ports.clj`:

```clojure
(defprotocol IPostService
  ;; ... existing methods ...
  (publish-post [this post-id] "Publish a blog post"))
```

### The Pattern

Notice the **FC/IS pattern in action**:

1. **Shell retrieves data** (I/O) - `find-by-id`
2. **Core validates** (pure) - `can-publish?`
3. **Core transforms** (pure) - `publish-post`
4. **Shell persists** (I/O) - `update-post`

**Benefits:**
- ✅ Core logic testable without database
- ✅ Service orchestrates I/O around pure functions
- ✅ Easy to reason about flow
- ✅ Changes to validation don't require database tests

---

## Step 6: Add HTTP Endpoint

### Add Route

Edit `libs/blog/src/boundary/blog/shell/http.clj`:

```clojure
(defn normalized-api-routes
  "Normalized routes for blog API."
  [service]
  [{:path "/posts"
    :methods {:get {:handler (fn [req] (list-posts-handler service req))}
              :post {:handler (fn [req] (create-post-handler service req))}}}
   
   {:path "/posts/:id"
    :methods {:get {:handler (fn [req] (get-post-handler service req))}
              :put {:handler (fn [req] (update-post-handler service req))}
              :delete {:handler (fn [req] (delete-post-handler service req))}}}
   
   ;; NEW: Publish endpoint
   {:path "/posts/:id/publish"
    :methods {:post {:handler (fn [req] (publish-post-handler service req))}}}])

(defn publish-post-handler
  "Handle POST /posts/:id/publish"
  [service request]
  (try
    (let [post-id (java.util.UUID/fromString 
                    (get-in request [:path-params :id]))
          published (.publish-post service post-id)]
      {:status 200
       :body {:post published
              :message "Post published successfully"}})
    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :not-found
        {:status 404
         :body {:error "Post not found"}}
        
        :validation-error
        {:status 400
         :body {:error "Cannot publish post"
                :reasons (:reasons (ex-data e))}}
        
        {:status 500
         :body {:error "Internal server error"}}))))
```

---

## Step 7: Integration and Testing

### Wire Module into System

Create `libs/blog/src/boundary/blog/shell/module_wiring.clj`:

```clojure
(ns boundary.blog.shell.module-wiring
  "Integrant wiring for the blog module."
  (:require [boundary.blog.shell.persistence :as persistence]
            [boundary.blog.shell.service :as service]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :boundary/blog-repository
  [_ {:keys [ctx]}]
  (log/info "Initializing blog repository")
  (persistence/create-repository ctx))

(defmethod ig/halt-key! :boundary/blog-repository
  [_ _repo]
  (log/info "Blog repository halted"))

(defmethod ig/init-key :boundary/blog-service
  [_ {:keys [repository]}]
  (log/info "Initializing blog service")
  (service/create-service repository))

(defmethod ig/halt-key! :boundary/blog-service
  [_ _service]
  (log/info "Blog service halted"))

(defmethod ig/init-key :boundary/blog-routes
  [_ {:keys [service config]}]
  (log/info "Initializing blog routes")
  (require 'boundary.blog.shell.http)
  (let [routes-fn (ns-resolve 'boundary.blog.shell.http 'blog-routes-normalized)]
    (routes-fn service config)))

(defmethod ig/halt-key! :boundary/blog-routes
  [_ _routes]
  (log/info "Blog routes halted"))
```

### Run Database Migration

```bash
# Apply the generated migration
psql -U boundary_dev -d boundary_dev -f migrations/009_create_posts.sql

# Or with SQLite (development)
sqlite3 dev-database.db < migrations/009_create_posts.sql
```

### Restart System

Start your REPL (if not already running):

```bash
clojure -M:repl-clj
```

**Expected Output:**

```
🚀 Boundary Framework REPL
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Clojure 1.12.1
nREPL server started on port 7888 on host localhost - nrepl://localhost:7888

user=>
```

Load the system and start it:

```clojure
user=> (require '[integrant.repl :as ig-repl])
nil

user=> (ig-repl/go)
;; 2026-01-05 20:00:00 INFO  [main] Initializing system...
;; 2026-01-05 20:00:00 INFO  [main] Database connection pool created
;; 2026-01-05 20:00:00 INFO  [main] Initializing user repository
;; 2026-01-05 20:00:00 INFO  [main] Initializing user service
;; 2026-01-05 20:00:00 INFO  [main] Initializing blog repository ✨
;; 2026-01-05 20:00:00 INFO  [main] Initializing blog service ✨
;; 2026-01-05 20:00:00 INFO  [main] Initializing blog routes ✨
;; 2026-01-05 20:00:00 INFO  [main] HTTP server started on port 3000
;; 2026-01-05 20:00:00 INFO  [main] System started successfully
:resumed

user=>
```

✨ **Your blog module is now wired into the running system!**

**Useful REPL Commands:**

```clojure
;; Reload code changes and restart
user=> (ig-repl/reset)

;; Stop the system
user=> (ig-repl/halt)

;; Check system state
user=> (keys integrant.repl.state/system)
(:boundary/db-context 
 :boundary/user-repository 
 :boundary/user-service
 :boundary/blog-repository  ;; ✨ Your new module!
 :boundary/blog-service     ;; ✨
 :boundary/http-server)
```

---

## Step 8: Test End-to-End

### Create a Post

```bash
curl -X POST http://localhost:3000/api/posts \
  -H "Content-Type: application/json" \
  -d '{
    "title": "My First Blog Post",
    "body": "'$(printf 'This is a blog post about Boundary Framework. %.0s' {1..10})'",
    "authorId": "550e8400-e29b-41d4-a716-446655440000",
    "slug": "my-first-post"
  }' | jq
```

**Response:**
```json
{
  "status": "success",
  "post": {
    "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "title": "My First Blog Post",
    "body": "This is a blog post about Boundary Framework...",
    "authorId": "550e8400-e29b-41d4-a716-446655440000",
    "slug": "my-first-post",
    "publishedAt": null,
    "createdAt": "2026-01-05T19:30:00Z",
    "updatedAt": "2026-01-05T19:30:00Z",
    "active": true
  }
}
```

✅ **HTTP 201 Created** - Post created successfully!

### Try Publishing (Should Fail - Body Too Short)

```bash
curl -X POST http://localhost:3000/api/posts/7c9e6679-7425-40de-944b-e07fc1f90ae7/publish \
  -H "Content-Type: application/json" | jq
```

**Response:**
```json
{
  "error": "Cannot publish post",
  "reasons": ["Body must be at least 100 characters"]
}
```

❌ **HTTP 400 Bad Request** - Business logic working! The pure `can-publish?` function prevented publication.

### Create Valid Post and Publish

```bash
# Create post with longer body
POST_ID=$(curl -s -X POST http://localhost:3000/api/posts \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Complete Guide to Boundary",
    "body": "'$(printf 'Boundary is a production-ready Clojure framework. %.0s' {1..20})'",
    "authorId": "550e8400-e29b-41d4-a716-446655440000",
    "slug": "boundary-guide"
  }' | jq -r '.id')

# Publish it
curl -X POST http://localhost:3000/api/posts/$POST_ID/publish | jq
```

**Response:**
```json
{
  "status": "success",
  "post": {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "title": "Complete Guide to Boundary",
    "body": "Boundary is a production-ready Clojure framework...",
    "slug": "boundary-guide",
    "publishedAt": "2026-01-05T19:35:00Z",
    "createdAt": "2026-01-05T19:34:00Z",
    "updatedAt": "2026-01-05T19:35:00Z",
    "active": true
  },
  "message": "Post published successfully"
}
```

✅ **HTTP 200 OK** - Success! Your custom business logic is working end-to-end.

---

## What You've Learned

### ✅ Functional Core / Imperative Shell Pattern

**You experienced:**
- Pure business logic (`can-publish?`, `publish-post`) - testable without mocks
- Shell orchestration (`publish-post-service`) - coordinates I/O around pure functions
- Clear separation of concerns - logic vs infrastructure

### ✅ Module-Centric Architecture

**You built:**
- Complete, self-contained blog module
- Could extract to microservice with zero rewrites
- Protocol-based boundaries for easy testing

### ✅ Production-Ready Patterns

**You implemented:**
- Malli validation at shell boundary
- Error handling with structured `ex-info`
- HTTP routes with proper status codes
- Database migrations with indexes

### ✅ Developer Productivity

**You achieved:**
- 12 production files generated in seconds
- Zero boilerplate code written
- Comprehensive test coverage (15 tests, 42 assertions)
- REPL-driven development workflow

---

## Key Takeaways

### 1. Core Logic Is Pure

```clojure
;; ✅ Pure (no side effects, fast tests)
(defn can-publish? [post]
  {:can-publish? (and (not (:published-at post))
                      (> (count (:body post)) 100))})

;; Test without mocks
(is (:can-publish? (can-publish? valid-post)))
```

### 2. Shell Orchestrates I/O

```clojure
;; Shell coordinates I/O around pure logic
(defn publish-post-service [this post-id]
  (let [post (.find-by-id repo post-id)      ; I/O
        check (core/can-publish? post)       ; Pure
        published (core/publish-post post)]  ; Pure
    (.update-post repo published)))          ; I/O
```

### 3. Protocols Enable Flexibility

```clojure
;; Service depends on interface, not implementation
(defrecord PostService [post-repository]  ; Any IPostRepository works
  IPostService
  (publish-post [this post-id] ...))
```

### 4. Scaffolding Saves Time

- **12 files generated** in seconds
- **Zero linting errors** out of the box
- **100% test coverage** included
- **Production patterns** enforced

---

## Next Steps

### Explore Advanced Features

Now that you understand the basics, explore enterprise features:

1. **[Background Jobs](../guides/background-jobs)** - Process async work (send emails, generate reports)
2. **[Multi-Factor Authentication](../guides/mfa-setup)** - Add TOTP-based MFA to your blog
3. **[Full-Text Search](../guides/search)** - Search posts by title and body
4. **[File Storage](../guides/file-storage)** - Upload and serve post images
5. **[API Pagination](../guides/pagination)** - Paginate post listings

### Understand Architecture

Deepen your architectural knowledge:

- [Functional Core / Imperative Shell](../guides/functional-core-imperative-shell) - Deep dive
- [Ports and Adapters](../guides/ports-and-adapters) - Hexagonal architecture
- [HTTP Interceptors](../architecture/interceptors) - Request/response middleware
- [Modules and Ownership](../guides/modules-and-ownership) - Module boundaries

### Deploy to Production

Ready for production?

- [Production Deployment](deployment) - Docker, environment config, migrations
- [Configuration Management](../guides/configure-db) - Environment-based config
- [Troubleshooting](../guides/troubleshooting) - Common issues and solutions

---

## Completed Module Checklist

✅ **Generated 12 production files** with scaffolder  
✅ **Understood FC/IS separation** (pure core, I/O shell)  
✅ **Added custom business logic** (`can-publish?`, `publish-post`)  
✅ **Wrote pure function tests** (no mocks, fast)  
✅ **Wired service into HTTP** (REST API endpoint)  
✅ **Ran database migration** (created posts table)  
✅ **Tested end-to-end** (API calls working)  
✅ **Experienced FC/IS benefits** (testability, clarity)

**Congratulations!** You've built a production-ready module using Boundary's architectural patterns. 🎉

---

**Time spent:** ~30 minutes  
**Lines of custom code written:** ~80 lines (business logic + tests)  
**Lines of boilerplate avoided:** ~600 lines (generated by scaffolder)  
**Tests passing:** 100%
