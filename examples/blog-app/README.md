# Blog App Example

**A complete blog application with HTMX web UI built with Boundary Framework**

This example demonstrates:
- ✅ Server-side rendered web UI with HTMX
- ✅ Functional Core / Imperative Shell (FC/IS) architecture
- ✅ Full CRUD operations for blog posts
- ✅ SQLite database with migrations
- ✅ Hiccup templating for HTML generation
- ✅ Pico CSS for minimal, elegant styling
- ✅ Comprehensive test coverage

**Complexity:** ⭐⭐ Intermediate  
**Time to complete:** 45 minutes  
**Lines of code:** ~1,500 (production) + ~200 (tests)

---

## What You'll Build

A fully functional blog with:

### Features

- **Home page** - List of published posts with excerpts
- **Post detail** - Full post content view
- **Dashboard** - Manage your posts (create, edit, delete)
- **Post editor** - Create and edit posts with publish/draft support

### Pages & Routes

```
Public:
GET  /                    - Home page (published posts)
GET  /posts/:slug         - Single post view

Dashboard (Author):
GET  /dashboard           - Post management dashboard
GET  /dashboard/posts/new - New post form
POST /dashboard/posts     - Create post
GET  /dashboard/posts/:id/edit - Edit post form
POST /dashboard/posts/:id      - Update post
POST /dashboard/posts/:id/delete - Delete post
```

### Data Model

**Post:**
```clojure
{:id          uuid
 :author-id   uuid
 :title       "string (1-200 chars)"
 :slug        "url-friendly-string"
 :content     "string (post body)"
 :excerpt     "string (optional, max 500 chars)"
 :published   boolean
 :published-at inst?
 :created-at  inst
 :updated-at  inst}
```

---

## Quick Start

### 1. Prerequisites

- Java 11+
- Clojure CLI (`brew install clojure/tools/clojure` on macOS)

### 2. Navigate to Example

```bash
cd examples/blog-app
```

### 3. Start the Application

```bash
# Start with default dev profile
clojure -M:run

# Or start a REPL for interactive development
clojure -M:repl-clj
```

In the REPL:
```clojure
(require '[blog.system :as sys])
(def system (sys/start!))
;; Server running at http://localhost:3001
```

### 4. Visit the Blog

Open http://localhost:3001 in your browser.

### 5. Create Your First Post

1. Go to http://localhost:3001/dashboard
2. Click "New Post"
3. Fill in the form and submit
4. View your post on the home page

---

## Project Structure

```
blog-app/
├── README.md                    # This file
├── deps.edn                     # Dependencies
├── resources/
│   ├── config/
│   │   ├── dev.edn             # Development config
│   │   └── test.edn            # Test config
│   └── public/
│       └── css/
│           └── blog.css        # Blog-specific styles
├── migrations/
│   ├── 001-create-posts.sql    # Posts table
│   └── 002-create-comments.sql # Comments table
├── src/blog/
│   ├── main.clj                # Application entry point
│   ├── system.clj              # Integrant system config
│   ├── post/                   # Post module
│   │   ├── schema.clj          # Malli schemas
│   │   ├── ports.clj           # Protocol definitions
│   │   ├── core/
│   │   │   ├── post.clj        # Pure business logic ⭐
│   │   │   └── ui.clj          # Hiccup templates ⭐
│   │   └── shell/
│   │       ├── persistence.clj # SQLite adapter
│   │       ├── service.clj     # Orchestration
│   │       └── http.clj        # HTTP handlers
│   └── shared/
│       └── ui/
│           └── layout.clj      # Layout components
└── test/blog/
    └── post/
        └── core/
            └── post_test.clj   # Unit tests
```

---

## Architecture: FC/IS in Practice

This example demonstrates the **Functional Core / Imperative Shell** pattern.

### The Core (Pure Functions)

Located in `blog.post.core.post` - these functions:
- Take data, return data
- Have no side effects
- Are easy to test

```clojure
;; Pure function: creates a post data structure
(defn create-post [input author-id now]
  {:id (random-uuid)
   :title (:title input)
   :slug (generate-slug (:title input))
   :content (:content input)
   ;; ... etc
   })

;; Pure function: applies updates
(defn update-post [post updates now]
  (cond-> post
    (:title updates) (assoc :title (:title updates)
                            :slug (generate-slug (:title updates)))
    ;; ... etc
    true (assoc :updated-at now)))
```

### The Shell (I/O Operations)

Located in `blog.post.shell.*` - these handle:
- Database queries
- HTTP request/response
- External services

```clojure
;; Shell: orchestrates core + I/O
(defn create-post [_this author-id input]
  (let [validation (validate-input schema/CreatePostRequest input)]
    (if (:error validation)
      validation
      ;; Call CORE function (pure)
      (let [post (post-core/create-post input author-id (now))
            ;; SHELL: persist to database
            saved (ports/save-post! repository post)]
        {:ok saved}))))
```

### Why This Matters

1. **Testing is easy** - Core functions need no mocks:
   ```clojure
   (deftest create-post-test
     (let [post (post/create-post {:title "Test"} nil test-instant)]
       (is (= "test" (:slug post)))))
   ```

2. **Logic is reusable** - Core doesn't know about HTTP, CLI, etc.

3. **Changes are isolated** - Swap SQLite for PostgreSQL? Only touch `persistence.clj`.

---

## Key Components Explained

### Schema (`schema.clj`)

Uses Malli for data validation:

```clojure
(def CreatePostRequest
  [:map
   [:title [:string {:min 1 :max 200}]]
   [:content [:string {:min 1}]]
   [:excerpt {:optional true} [:string {:max 500}]]
   [:published {:optional true} :boolean]])
```

### Ports (`ports.clj`)

Defines interfaces the shell must implement:

```clojure
(defprotocol IPostRepository
  (find-post-by-id [this post-id])
  (find-post-by-slug [this slug])
  (list-posts [this options])
  (save-post! [this post])
  (delete-post! [this post-id]))
```

### UI Templates (`ui.clj`)

Pure Hiccup templates in the core:

```clojure
(defn post-card [post]
  [:article.post-card
   [:h2 [:a {:href (str "/posts/" (:slug post))} (:title post)]]
   [:p.excerpt (:excerpt post)]
   [:a {:href (str "/posts/" (:slug post))} "Read more →"]])
```

### HTTP Handlers (`http.clj`)

Ring handlers that orchestrate everything:

```clojure
(defn home-handler [post-service blog-config]
  (fn [request]
    (let [result (ports/list-published-posts post-service {:limit 10})
          posts (:ok result)]
      (render-page "Home" (ui/home-page-content posts) opts))))
```

---

## Running Tests

```bash
# Run all tests
clojure -M:test

# Run with verbose output
clojure -M:test --reporter documentation
```

Expected output:
```
17 tests, 42 assertions, 0 failures.
```

---

## Extending the Example

### Add Comments (HTMX)

The migration is already in place. To add comments:

1. Create `comment/` module (schema, ports, core, shell)
2. Add HTMX form to post detail:
   ```clojure
   [:form {:hx-post (str "/posts/" (:slug post) "/comments")
           :hx-target "#comments-list"
           :hx-swap "beforeend"}
    [:textarea {:name "content"}]
    [:button "Add Comment"]]
   ```

### Add User Authentication

1. Add session middleware in `system.clj`
2. Create `auth/` module with login/logout
3. Protect dashboard routes

### Add Markdown Support

1. Add `markdown-clj` dependency
2. Render content in `ui.clj`:
   ```clojure
   [:div.post-content
    (hiccup.util/raw-string (md/md-to-html-string (:content post)))]
   ```

---

## Configuration

### Development (`resources/config/dev.edn`)

```clojure
{:server {:port 3001
          :join? false}
 :database {:dbtype "sqlite"
            :dbname "blog-dev.db"}
 :blog {:name "My Boundary Blog"
        :posts-per-page 10}}
```

### Test (`resources/config/test.edn`)

```clojure
{:database {:dbtype "sqlite"
            :dbname ":memory:"}}
```

---

## Key Learnings

### 1. Pure UI Templates

Hiccup templates are just functions returning data:
```clojure
;; This is pure - easy to test!
(defn post-card [post]
  [:article [:h2 (:title post)]])

;; Test it directly
(is (= [:article [:h2 "Test"]] (post-card {:title "Test"})))
```

### 2. Service Layer Pattern

The service layer (`service.clj`) coordinates:
- Validation
- Core business logic
- Persistence
- Error handling

### 3. HTMX for Interactivity

No JavaScript build step needed:
```clojure
[:form {:hx-post "/posts"
        :hx-target "#post-list"
        :hx-swap "afterbegin"}
  ;; Form submits via AJAX, updates DOM
  ]
```

---

## Resources

- [Boundary Documentation](../../docs-site/)
- [FC/IS Architecture](https://www.destroyallsoftware.com/screencasts/catalog/functional-core-imperative-shell)
- [Hiccup Documentation](https://github.com/weavejester/hiccup)
- [HTMX Documentation](https://htmx.org/)
- [Pico CSS](https://picocss.com/)

---

**Congratulations!** You've built a blog with Boundary. 🎉

**Next:** Try adding comments with HTMX for real-time interactions.
