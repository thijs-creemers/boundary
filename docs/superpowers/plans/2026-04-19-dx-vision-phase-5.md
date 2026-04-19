# DX Vision Phase 5: Advanced REPL — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add time-travel debugging, runtime route/handler manipulation, component hot-swap, and schema-driven module generation to the Boundary REPL.

**Architecture:** Six new files in `libs/devtools/` (3 core, 3 shell) following FC/IS, plus a small handler-atom change in `libs/platform/`. All features share a router rebuild infrastructure for runtime handler swapping.

**Tech Stack:** Clojure 1.12.4, Integrant, Reitit, Ring, Malli, existing scaffolder core

**Spec:** `docs/superpowers/specs/2026-04-19-dx-vision-phase-5-design.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `libs/devtools/src/boundary/devtools/core/recording.clj` | Pure functions: session data structures, entry diffing, table formatting, EDN serialization |
| `libs/devtools/src/boundary/devtools/core/router.clj` | Pure functions: route tree add/remove, tap interceptor inject/remove |
| `libs/devtools/src/boundary/devtools/core/prototype.clj` | Pure functions: map prototype spec to scaffolder context, build migration spec |
| `libs/devtools/src/boundary/devtools/shell/recording.clj` | Stateful: session atom, capture middleware, file I/O, start/stop/replay |
| `libs/devtools/src/boundary/devtools/shell/router.clj` | Stateful: handler-atom swap, dynamic-routes atom, taps atom, rebuild-router! |
| `libs/devtools/src/boundary/devtools/shell/prototype.clj` | Effectful: orchestrate scaffold → migrate → reset → summary |
| `libs/devtools/test/boundary/devtools/core/recording_test.clj` | Unit tests for recording core |
| `libs/devtools/test/boundary/devtools/core/router_test.clj` | Unit tests for router core |
| `libs/devtools/test/boundary/devtools/core/prototype_test.clj` | Unit tests for prototype core |
| `libs/devtools/test/boundary/devtools/shell/recording_test.clj` | Integration tests for recording shell |
| `libs/devtools/test/boundary/devtools/shell/router_test.clj` | Integration tests for router shell |

### Modified Files
| File | Change |
|------|--------|
| `libs/platform/src/boundary/platform/shell/system/wiring.clj:466-483` | Add handler-atom wrapper for dev profile |
| `libs/devtools/src/boundary/devtools/shell/repl.clj:1-12` | Add restart-component, scaffold! functions |
| `dev/repl/user.clj:1-31` | Expose new REPL helpers |
| `libs/devtools/deps.edn` | Add scaffolder dependency |
| `.gitignore` | Add `.boundary/` directory |

---

## Task 1: Platform Handler-Atom Wrapper

**Why first:** Every runtime feature (defroute!, tap-handler!, recording) depends on being able to swap the HTTP handler without restarting Jetty. This is the foundation.

**Files:**
- Modify: `libs/platform/src/boundary/platform/shell/system/wiring.clj:466-483`

- [ ] **Step 1: Read the current HTTP server init-key**

Read `libs/platform/src/boundary/platform/shell/system/wiring.clj` lines 460-490 to understand the exact Jetty setup.

- [ ] **Step 2: Add handler-atom and dispatch-handler**

At the top of the namespace (after line 496's `system-state` defonce), add:

```clojure
(defonce ^:private handler-atom (atom nil))

(defn dispatch-handler
  "Indirection layer: Jetty calls this stable fn, we swap the atom underneath.
   Only used in dev profile — production passes handler directly."
  [request]
  (if-let [handler @handler-atom]
    (handler request)
    {:status 503
     :headers {"Content-Type" "text/plain"}
     :body "Handler not initialized"}))

(defn swap-handler!
  "Replace the live HTTP handler. Called by devtools for router rebuilds."
  [new-handler]
  (reset! handler-atom new-handler))
```

- [ ] **Step 3: Modify the `:boundary/http-server` init-key to use the atom**

In the `init-key` method for `:boundary/http-server` (around line 466-483), change the handler passed to `jetty/run-jetty`:

```clojure
;; Before: (jetty/run-jetty handler {:port allocated-port ...})
;; After:
(reset! handler-atom handler)
(jetty/run-jetty dispatch-handler {:port allocated-port
                                   :host host
                                   :join? (or join? false)})
```

The key insight: `dispatch-handler` is a stable function reference that Jetty holds. We swap `handler-atom` underneath it.

- [ ] **Step 4: Verify the platform tests still pass**

Run: `clojure -M:test:db/h2 :platform`
Expected: All existing tests pass (this change is transparent to existing behavior).

- [ ] **Step 5: Commit**

```bash
git add libs/platform/src/boundary/platform/shell/system/wiring.clj
git commit -m "feat(platform): add handler-atom for runtime handler swapping

Wraps HTTP handler in an atom so devtools can swap the compiled
Reitit router without restarting Jetty. dispatch-handler is a
stable function reference that deferences the atom on each request."
```

---

## Task 2: Core Recording (Pure Functions)

**Why next:** Recording is the most complex feature. Start with the pure core layer — no I/O, fully testable.

**Files:**
- Create: `libs/devtools/src/boundary/devtools/core/recording.clj`
- Create: `libs/devtools/test/boundary/devtools/core/recording_test.clj`

- [ ] **Step 1: Write failing tests for session creation and entry management**

Create `libs/devtools/test/boundary/devtools/core/recording_test.clj`:

```clojure
(ns boundary.devtools.core.recording-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.core.recording :as recording]))

(deftest create-session-test
  (testing "creates empty session with timestamp"
    (let [session (recording/create-session)]
      (is (vector? (:entries session)))
      (is (empty? (:entries session)))
      (is (inst? (:started-at session)))
      (is (nil? (:stopped-at session))))))

(deftest add-entry-test
  (testing "appends entry with auto-incrementing index"
    (let [session (-> (recording/create-session)
                      (recording/add-entry
                        {:method :get :uri "/api/users" :headers {}}
                        {:status 200 :body {:users []} :headers {}}
                        42)
                      (recording/add-entry
                        {:method :post :uri "/api/users" :body {:name "Test"} :headers {}}
                        {:status 201 :body {:id 1} :headers {}}
                        15))]
      (is (= 2 (count (:entries session))))
      (is (= 0 (:idx (first (:entries session)))))
      (is (= 1 (:idx (second (:entries session))))))))

(deftest get-entry-test
  (testing "retrieves entry by index"
    (let [session (-> (recording/create-session)
                      (recording/add-entry
                        {:method :get :uri "/api/users" :headers {}}
                        {:status 200 :body {} :headers {}}
                        10))]
      (is (= :get (get-in (recording/get-entry session 0) [:request :method])))
      (is (nil? (recording/get-entry session 5))))))

(deftest stop-session-test
  (testing "freezes session with stopped-at timestamp"
    (let [session (-> (recording/create-session)
                      (recording/stop-session))]
      (is (inst? (:stopped-at session))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.recording-test`
Expected: FAIL — namespace not found.

- [ ] **Step 3: Implement session management functions**

Create `libs/devtools/src/boundary/devtools/core/recording.clj`:

```clojure
(ns boundary.devtools.core.recording
  "Pure functions for recording session data structures.
   No I/O, no atoms — just data transformations."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

(defn create-session
  "Create an empty recording session."
  []
  {:entries    []
   :started-at (java.util.Date.)
   :stopped-at nil})

(defn add-entry
  "Append a captured request/response pair to the session."
  [session request response duration-ms]
  (let [idx (count (:entries session))]
    (update session :entries conj
            {:idx         idx
             :request     request
             :response    response
             :duration-ms duration-ms
             :timestamp   (java.util.Date.)})))

(defn get-entry
  "Retrieve an entry by index. Returns nil if out of bounds."
  [session idx]
  (get (:entries session) idx))

(defn stop-session
  "Mark session as stopped."
  [session]
  (assoc session :stopped-at (java.util.Date.)))

(defn entry-count
  "Number of entries in the session."
  [session]
  (count (:entries session)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.recording-test`
Expected: PASS — 4 tests.

- [ ] **Step 5: Write failing tests for merge, diff, and formatting**

Add to `recording_test.clj`:

```clojure
(deftest merge-request-modifications-test
  (testing "deep-merges overrides into captured request body"
    (let [request {:method :post :uri "/api/users"
                   :body {:name "Test" :email "old@test.com"}
                   :headers {"content-type" "application/json"}}
          modified (recording/merge-request-modifications
                     request {:email "new@test.com" :role :admin})]
      (is (= "new@test.com" (get-in modified [:body :email])))
      (is (= "Test" (get-in modified [:body :name])))
      (is (= :admin (get-in modified [:body :role]))))))

(deftest diff-entries-test
  (testing "produces structured diff between two entries"
    (let [session (-> (recording/create-session)
                      (recording/add-entry
                        {:method :get :uri "/api/users" :headers {}}
                        {:status 200 :body {:count 5} :headers {}}
                        42)
                      (recording/add-entry
                        {:method :get :uri "/api/users" :headers {}}
                        {:status 200 :body {:count 10} :headers {}}
                        38))
          diff (recording/diff-entries session 0 1)]
      (is (map? diff))
      (is (contains? diff :request-diff))
      (is (contains? diff :response-diff)))))

(deftest format-entry-table-test
  (testing "formats entries as a printable table string"
    (let [session (-> (recording/create-session)
                      (recording/add-entry
                        {:method :get :uri "/api/users" :headers {}}
                        {:status 200 :body {} :headers {}}
                        42))
          table (recording/format-entry-table session)]
      (is (string? table))
      (is (str/includes? table "GET"))
      (is (str/includes? table "/api/users"))
      (is (str/includes? table "200")))))

(deftest serialization-round-trip-test
  (testing "session survives EDN serialization"
    (let [session (-> (recording/create-session)
                      (recording/add-entry
                        {:method :post :uri "/api/users"
                         :body {:name "Test"} :headers {}}
                        {:status 201 :body {:id 1} :headers {}}
                        15)
                      (recording/stop-session))
          serialized (recording/serialize-session session)
          deserialized (recording/deserialize-session serialized)]
      (is (string? serialized))
      (is (= 1 (count (:entries deserialized))))
      (is (= :post (get-in deserialized [:entries 0 :request :method]))))))
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.recording-test`
Expected: FAIL — functions not found.

- [ ] **Step 7: Implement merge, diff, format, and serialization**

Add to `recording.clj` (note: `map-diff` must appear before `diff-entries` since Clojure requires forward declaration):

```clojure
(defn merge-request-modifications
  "Deep-merge user overrides into a captured request's body."
  [request overrides]
  (update request :body merge overrides))

(defn- map-diff
  "Shallow diff of two maps. Returns vector of diff entries."
  [a b]
  (let [all-keys (set (concat (keys a) (keys b)))]
    (reduce
      (fn [acc k]
        (let [va (get a k)
              vb (get b k)]
          (cond
            (and (nil? va) (some? vb))  (conj acc [:added k vb])
            (and (some? va) (nil? vb))  (conj acc [:removed k va])
            (not= va vb)                (conj acc [:changed k va vb])
            :else                       acc)))
      []
      (sort all-keys))))

(defn diff-entries
  "Produce a structured diff between two entries' request and response maps.
   Returns {:request-diff [...] :response-diff [...]} where each diff entry
   is [:added k v], [:removed k v], or [:changed k old new]."
  [session idx-a idx-b]
  (let [a (get-entry session idx-a)
        b (get-entry session idx-b)]
    (when (and a b)
      {:request-diff  (map-diff (:request a) (:request b))
       :response-diff (map-diff (:response a) (:response b))})))

(defn format-entry-table
  "Format session entries as a printable table."
  [session]
  (let [entries (:entries session)
        header  (format "%-5s %-7s %-30s %-8s %-10s" "IDX" "METHOD" "PATH" "STATUS" "DURATION")
        sep     (apply str (repeat (count header) "─"))
        rows    (map (fn [{:keys [idx request response duration-ms]}]
                       (format "%-5d %-7s %-30s %-8d %-10s"
                               idx
                               (str/upper-case (name (:method request)))
                               (:uri request)
                               (:status response)
                               (str duration-ms "ms")))
                     entries)]
    (str/join "\n" (concat [header sep] rows))))

(defn serialize-session
  "Serialize a session to an EDN string."
  [session]
  (pr-str session))

(defn deserialize-session
  "Deserialize a session from an EDN string."
  [s]
  (edn/read-string s))
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.recording-test`
Expected: PASS — all 8 tests.

- [ ] **Step 9: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/core/recording.clj
clj-paren-repair libs/devtools/test/boundary/devtools/core/recording_test.clj
git add libs/devtools/src/boundary/devtools/core/recording.clj \
        libs/devtools/test/boundary/devtools/core/recording_test.clj
git commit -m "feat(devtools): add core recording — session, diff, serialize"
```

---

## Task 3: Core Router (Pure Functions)

**Why next:** Router manipulation is needed by defroute!, tap-handler!, and recording. Pure core first.

**Files:**
- Create: `libs/devtools/src/boundary/devtools/core/router.clj`
- Create: `libs/devtools/test/boundary/devtools/core/router_test.clj`

- [ ] **Step 1: Write failing tests for route addition/removal**

Create `libs/devtools/test/boundary/devtools/core/router_test.clj`:

```clojure
(ns boundary.devtools.core.router-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.core.router :as router]))

(def sample-routes
  [["/api/users"
    {:get  {:handler (fn [_] {:status 200}) :name :list-users}
     :post {:handler (fn [_] {:status 201}) :name :create-user}}]
   ["/api/users/:id"
    {:get {:handler (fn [_] {:status 200}) :name :get-user}}]])

(deftest add-route-test
  (testing "adds a new route to the route tree"
    (let [new-handler (fn [_] {:status 200 :body {:hello "world"}})
          updated (router/add-route sample-routes :get "/api/test" new-handler)]
      (is (some #(= "/api/test" (first %)) updated))))

  (testing "adds method to existing path"
    (let [new-handler (fn [_] {:status 204})
          updated (router/add-route sample-routes :delete "/api/users" new-handler)]
      (is (= 2 (count updated)))
      (let [users-route (first (filter #(= "/api/users" (first %)) updated))]
        (is (contains? (second users-route) :delete))))))

(deftest remove-route-test
  (testing "removes a route by method and path"
    (let [updated (router/remove-route sample-routes :get "/api/users")]
      (let [users-route (first (filter #(= "/api/users" (first %)) updated))]
        (is (not (contains? (second users-route) :get)))
        (is (contains? (second users-route) :post)))))

  (testing "removes entire path entry when last method removed"
    (let [updated (router/remove-route sample-routes :get "/api/users/:id")]
      (is (not (some #(= "/api/users/:id" (first %)) updated))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.router-test`
Expected: FAIL — namespace not found.

- [ ] **Step 3: Implement add-route and remove-route**

Create `libs/devtools/src/boundary/devtools/core/router.clj`:

```clojure
(ns boundary.devtools.core.router
  "Pure functions for manipulating Reitit route data structures.
   All functions take and return route trees (vectors of [path handler-map]).")

(defn add-route
  "Add a route to the route tree. If the path already exists, merges the method."
  [routes method path handler-fn]
  (let [existing (first (filter #(= path (first %)) routes))]
    (if existing
      (mapv (fn [[p data :as route]]
              (if (= p path)
                [p (assoc data method {:handler handler-fn})]
                route))
            routes)
      (conj (vec routes) [path {method {:handler handler-fn}}]))))

(defn remove-route
  "Remove a method from a route. Removes the path entirely if it was the last method."
  [routes method path]
  (let [updated (mapv (fn [[p data :as route]]
                        (if (= p path)
                          [p (dissoc data method)]
                          route))
                      routes)]
    (vec (remove (fn [[_ data]] (empty? data)) updated))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.router-test`
Expected: PASS.

- [ ] **Step 5: Write failing tests for tap interceptor injection**

Add to `router_test.clj`:

```clojure
(deftest inject-tap-interceptor-test
  (testing "injects a tap interceptor into a handler's chain"
    (let [tap-fn (fn [ctx] (assoc ctx ::tapped true))
          updated (router/inject-tap-interceptor sample-routes :create-user tap-fn)]
      (let [users-route (first (filter #(= "/api/users" (first %)) updated))
            post-data (get (second users-route) :post)
            interceptors (:interceptors post-data)]
        (is (some #(= :devtools/tap (:name %)) interceptors))))))

(deftest remove-tap-interceptor-test
  (testing "removes the tap interceptor from a handler's chain"
    (let [tap-fn (fn [ctx] ctx)
          with-tap (router/inject-tap-interceptor sample-routes :create-user tap-fn)
          without-tap (router/remove-tap-interceptor with-tap :create-user)]
      (let [users-route (first (filter #(= "/api/users" (first %)) without-tap))
            post-data (get (second users-route) :post)
            interceptors (:interceptors post-data)]
        (is (not (some #(= :devtools/tap (:name %)) interceptors)))))))
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.router-test`
Expected: FAIL — functions not found.

- [ ] **Step 7: Implement inject/remove tap interceptor**

Add to `router.clj`:

```clojure
(defn- find-handler-in-routes
  "Find the [path method] for a handler by its :name keyword."
  [routes handler-name]
  (first
    (for [[path data] routes
          [method handler-data] data
          :when (= handler-name (:name handler-data))]
      [path method])))

(defn inject-tap-interceptor
  "Add a :devtools/tap interceptor to a handler's interceptor chain."
  [routes handler-name tap-fn]
  (if-let [[target-path target-method] (find-handler-in-routes routes handler-name)]
    (mapv (fn [[path data :as route]]
            (if (= path target-path)
              [path (update data target-method
                            (fn [handler-data]
                              (let [tap-interceptor {:name  :devtools/tap
                                                     :enter (fn [ctx] (tap-fn ctx))}
                                    existing (or (:interceptors handler-data) [])]
                                (assoc handler-data :interceptors
                                       (vec (cons tap-interceptor existing))))))]
              route))
          routes)
    routes))

(defn remove-tap-interceptor
  "Remove the :devtools/tap interceptor from a handler's chain."
  [routes handler-name]
  (if-let [[target-path target-method] (find-handler-in-routes routes handler-name)]
    (mapv (fn [[path data :as route]]
            (if (= path target-path)
              [path (update-in data [target-method :interceptors]
                               (fn [interceptors]
                                 (vec (remove #(= :devtools/tap (:name %)) interceptors))))]
              route))
          routes)
    routes))
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.router-test`
Expected: PASS — all 4 tests.

- [ ] **Step 9: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/core/router.clj
clj-paren-repair libs/devtools/test/boundary/devtools/core/router_test.clj
git add libs/devtools/src/boundary/devtools/core/router.clj \
        libs/devtools/test/boundary/devtools/core/router_test.clj
git commit -m "feat(devtools): add core router — route tree manipulation, tap injection"
```

---

## Task 4: Shell Router (Handler Swapping Infrastructure)

**Why next:** Shell router provides `rebuild-router!` which recording, defroute!, and tap-handler! all need.

**Files:**
- Create: `libs/devtools/src/boundary/devtools/shell/router.clj`
- Create: `libs/devtools/test/boundary/devtools/shell/router_test.clj`

- [ ] **Step 1: Write failing test for dynamic route registration and rebuild**

Create `libs/devtools/test/boundary/devtools/shell/router_test.clj`:

```clojure
(ns boundary.devtools.shell.router-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.devtools.shell.router :as router]))

(use-fixtures :each
  (fn [f]
    (router/clear-dynamic-state!)
    (f)
    (router/clear-dynamic-state!)))

(deftest add-dynamic-route-test
  (testing "registers a dynamic route"
    (router/add-dynamic-route! :get "/api/test"
                               (fn [_] {:status 200 :body {:ok true}}))
    (let [routes (router/list-dynamic-routes)]
      (is (= 1 (count routes)))
      (is (= "/api/test" (:path (first routes))))
      (is (= :get (:method (first routes)))))))

(deftest remove-dynamic-route-test
  (testing "removes a dynamic route"
    (router/add-dynamic-route! :get "/api/test"
                               (fn [_] {:status 200 :body {:ok true}}))
    (router/remove-dynamic-route! :get "/api/test")
    (is (empty? (router/list-dynamic-routes)))))

(deftest add-tap-test
  (testing "registers a tap on a handler"
    (let [tap-fn (fn [ctx] ctx)]
      (router/add-tap! :create-user tap-fn)
      (let [taps (router/list-taps)]
        (is (= 1 (count taps)))
        (is (= :create-user (first taps)))))))

(deftest remove-tap-test
  (testing "removes a tap"
    (router/add-tap! :create-user (fn [ctx] ctx))
    (router/remove-tap! :create-user)
    (is (empty? (router/list-taps)))))

(deftest clear-dynamic-state-test
  (testing "clears all dynamic routes and taps"
    (router/add-dynamic-route! :get "/api/test"
                               (fn [_] {:status 200}))
    (router/add-tap! :create-user (fn [ctx] ctx))
    (router/clear-dynamic-state!)
    (is (empty? (router/list-dynamic-routes)))
    (is (empty? (router/list-taps)))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.router-test`
Expected: FAIL — namespace not found.

- [ ] **Step 3: Implement shell router**

Create `libs/devtools/src/boundary/devtools/shell/router.clj`:

```clojure
(ns boundary.devtools.shell.router
  "Stateful router management for runtime route/tap modifications.
   Tracks dynamic routes and taps in atoms, rebuilds the handler via
   platform's swap-handler!."
  (:require [boundary.devtools.core.router :as core-router]))

;; --- State ---

(defonce ^:private dynamic-routes
  (atom {}))

(defonce ^:private taps
  (atom {}))

(defonce ^:private recording-active?
  (atom false))

;; --- Dynamic routes ---

(defn add-dynamic-route!
  "Register a dynamic route. Does NOT rebuild the router — call rebuild-router! after."
  [method path handler-fn]
  (swap! dynamic-routes assoc [method path] {:handler handler-fn})
  nil)

(defn remove-dynamic-route!
  "Remove a dynamic route."
  [method path]
  (swap! dynamic-routes dissoc [method path])
  nil)

(defn list-dynamic-routes
  "List all dynamic routes as [{:method :path}]."
  []
  (mapv (fn [[[method path] _]]
          {:method method :path path})
        @dynamic-routes))

;; --- Taps ---

(defn add-tap!
  "Register a tap on a handler. Does NOT rebuild — call rebuild-router! after."
  [handler-name tap-fn]
  (swap! taps assoc handler-name tap-fn)
  nil)

(defn remove-tap!
  "Remove a tap from a handler."
  [handler-name]
  (swap! taps dissoc handler-name)
  nil)

(defn list-taps
  "List handler names that have active taps."
  []
  (vec (keys @taps)))

;; --- Recording flag ---

(defn set-recording!
  "Set whether recording middleware should be active."
  [active?]
  (reset! recording-active? active?))

(defn recording-active?*
  "Check if recording is active."
  []
  @recording-active?)

;; --- Router rebuild ---

(defn apply-dynamic-routes
  "Apply all dynamic routes to a base route tree."
  [base-routes]
  (reduce
    (fn [routes [[method path] {:keys [handler]}]]
      (core-router/add-route routes method path handler))
    base-routes
    @dynamic-routes))

(defn apply-taps
  "Apply all taps to a route tree."
  [routes]
  (reduce
    (fn [routes [handler-name tap-fn]]
      (core-router/inject-tap-interceptor routes handler-name tap-fn))
    routes
    @taps))

(defn rebuild-router!
  "Rebuild the HTTP handler with current dynamic routes and taps applied.
   Requires the compile-routes fn and base routes from the system,
   and calls swap-handler! to install the new handler.

   Arguments:
   - base-routes: the original route tree from system startup
   - compile-fn: function that compiles routes into a Ring handler
   - swap-fn: function to swap the live handler (platform/swap-handler!)"
  [base-routes compile-fn swap-fn]
  (let [modified-routes (-> base-routes
                            apply-dynamic-routes
                            apply-taps)
        new-handler (compile-fn modified-routes)]
    (swap-fn new-handler)))

;; --- Cleanup ---

(defn clear-dynamic-state!
  "Clear all dynamic routes, taps, and recording flag. Called on (reset)."
  []
  (reset! dynamic-routes {})
  (reset! taps {})
  (reset! recording-active? false)
  nil)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.router-test`
Expected: PASS — all 5 tests.

- [ ] **Step 5: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/router.clj
clj-paren-repair libs/devtools/test/boundary/devtools/shell/router_test.clj
git add libs/devtools/src/boundary/devtools/shell/router.clj \
        libs/devtools/test/boundary/devtools/shell/router_test.clj
git commit -m "feat(devtools): add shell router — dynamic route/tap state, rebuild-router!"
```

---

## Task 5: Shell Recording (Stateful Layer)

**Files:**
- Create: `libs/devtools/src/boundary/devtools/shell/recording.clj`
- Create: `libs/devtools/test/boundary/devtools/shell/recording_test.clj`
- Modify: `.gitignore`

- [ ] **Step 1: Add `.boundary/` to `.gitignore`**

Add after line 53 of `.gitignore`:

```
.boundary/
```

- [ ] **Step 2: Write failing tests for start/stop and file I/O**

Create `libs/devtools/test/boundary/devtools/shell/recording_test.clj`:

```clojure
(ns boundary.devtools.shell.recording-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [boundary.devtools.shell.recording :as recording])
  (:import [java.io File]))

(def ^:private test-dir ".boundary/recordings-test")

(use-fixtures :each
  (fn [f]
    (recording/reset-session!)
    (f)
    (recording/reset-session!)
    ;; Cleanup test files
    (let [dir (io/file test-dir)]
      (when (.exists dir)
        (doseq [f (.listFiles dir)] (.delete f))
        (.delete dir)))))

(deftest start-stop-session-test
  (testing "start creates a session, stop freezes it"
    (recording/start-recording!)
    (is (some? (recording/active-session)))
    (is (nil? (:stopped-at (recording/active-session))))
    (recording/stop-recording!)
    (is (inst? (:stopped-at (recording/active-session))))))

(deftest capture-middleware-test
  (testing "middleware captures request/response pairs"
    (recording/start-recording!)
    (let [handler (fn [req] {:status 200 :body {:ok true}})
          wrapped ((recording/capture-middleware) handler)
          response (wrapped {:request-method :get :uri "/api/test" :headers {}})]
      (is (= 200 (:status response)))
      (is (= 1 (count (:entries (recording/active-session))))))))

(deftest save-load-session-test
  (testing "saves and loads session from file"
    (recording/start-recording!)
    (let [handler (fn [req] {:status 200 :body {:ok true}})
          wrapped ((recording/capture-middleware) handler)]
      (wrapped {:request-method :get :uri "/api/test" :headers {}})
      (recording/stop-recording!)
      (recording/save-session! "test-flow" test-dir)
      (recording/reset-session!)
      (is (nil? (recording/active-session)))
      (recording/load-session! "test-flow" test-dir)
      (is (= 1 (count (:entries (recording/active-session))))))))

(deftest load-missing-session-test
  (testing "loading a non-existent session returns error info"
    (let [result (recording/load-session! "nonexistent" test-dir)]
      (is (= :not-found (:error result))))))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.recording-test`
Expected: FAIL — namespace not found.

- [ ] **Step 4: Implement shell recording**

Create `libs/devtools/src/boundary/devtools/shell/recording.clj`:

```clojure
(ns boundary.devtools.shell.recording
  "Stateful recording management: session atom, capture middleware, file I/O."
  (:require [boundary.devtools.core.recording :as core]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def ^:private default-dir ".boundary/recordings")

;; --- State ---

(defonce ^:private session-atom (atom nil))

(defn active-session
  "Return the current recording session, or nil."
  []
  @session-atom)

(defn reset-session!
  "Clear the active session."
  []
  (reset! session-atom nil))

;; --- Start / Stop ---

(defn start-recording!
  "Start a new recording session."
  []
  (reset! session-atom (core/create-session))
  (println "Recording started. Requests will be captured.")
  nil)

(defn stop-recording!
  "Stop the active recording session."
  []
  (when @session-atom
    (swap! session-atom core/stop-session)
    (let [count (core/entry-count @session-atom)]
      (println (format "Recording stopped. %d request(s) captured." count))))
  nil)

;; --- Capture middleware ---

(defn capture-middleware
  "Returns a Ring middleware that captures request/response pairs into the session atom."
  []
  (fn [handler]
    (fn [request]
      (let [start    (System/nanoTime)
            response (handler request)
            duration (/ (- (System/nanoTime) start) 1e6)]
        (when @session-atom
          (let [req-data (-> (select-keys request [:uri :headers :body :params])
                             (assoc :method (:request-method request)))]
            (swap! session-atom core/add-entry
                   req-data
                   (select-keys response [:status :headers :body])
                   (long duration))))
        response))))

;; --- Replay ---

(defn replay-entry!
  "Replay a recorded entry through simulate-request.
   simulate-fn should be the repl/simulate-request function."
  [idx simulate-fn & [overrides]]
  (if-let [session @session-atom]
    (if-let [entry (core/get-entry session idx)]
      (let [request (if overrides
                      (core/merge-request-modifications (:request entry) overrides)
                      (:request entry))]
        (simulate-fn (:method request) (:uri request) {:body (:body request)}))
      (println (format "Entry %d not found. Session has %d entries (0 to %d)."
                       idx (core/entry-count session) (dec (core/entry-count session)))))
    (println "No active recording session. Use (recording :start) or (recording :load \"name\").")))

;; --- File I/O ---

(defn save-session!
  "Save the active session to disk as EDN."
  ([name] (save-session! name default-dir))
  ([name dir]
   (if-let [session @session-atom]
     (let [file (io/file dir (str name ".edn"))]
       (io/make-parents file)
       (spit file (core/serialize-session session))
       (println (format "Recording saved to %s" (.getPath file))))
     (println "No active recording session."))))

(defn load-session!
  "Load a saved session from disk."
  ([name] (load-session! name default-dir))
  ([name dir]
   (let [file (io/file dir (str name ".edn"))]
     (if (.exists file)
       (do
         (reset! session-atom (core/deserialize-session (slurp file)))
         (println (format "Loaded recording '%s' (%d entries)."
                          name (core/entry-count @session-atom)))
         nil)
       (let [available (when (.exists (io/file dir))
                         (->> (.listFiles (io/file dir))
                              (filter #(.endsWith (.getName %) ".edn"))
                              (map #(subs (.getName %) 0 (- (count (.getName %)) 4)))))]
         (if (seq available)
           (println (format "Recording '%s' not found. Available: %s"
                            name (clojure.string/join ", " available)))
           (println (format "Recording '%s' not found. No saved recordings in %s."
                            name dir)))
         {:error :not-found})))))

;; --- List / Diff ---

(defn list-entries
  "Print the entry table for the active session."
  []
  (if-let [session @session-atom]
    (println (core/format-entry-table session))
    (println "No active recording session.")))

(defn diff-entries
  "Diff two entries in the active session."
  [idx-a idx-b]
  (if-let [session @session-atom]
    (if-let [diff (core/diff-entries session idx-a idx-b)]
      diff
      (println "One or both entry indices are out of bounds."))
    (println "No active recording session.")))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.recording-test`
Expected: PASS — all 4 tests.

- [ ] **Step 6: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/recording.clj
clj-paren-repair libs/devtools/test/boundary/devtools/shell/recording_test.clj
git add libs/devtools/src/boundary/devtools/shell/recording.clj \
        libs/devtools/test/boundary/devtools/shell/recording_test.clj \
        .gitignore
git commit -m "feat(devtools): add shell recording — session state, capture middleware, file I/O"
```

---

## Task 6: Core Prototype (Pure Functions)

**Files:**
- Create: `libs/devtools/src/boundary/devtools/core/prototype.clj`
- Create: `libs/devtools/test/boundary/devtools/core/prototype_test.clj`
- Modify: `libs/devtools/deps.edn` — add scaffolder dependency
- Modify: `deps.edn:134-141` — add scaffolder to dev paths

- [ ] **Step 1: Add scaffolder dependency**

Add to `libs/devtools/deps.edn` under `:deps`:
```clojure
boundary/scaffolder {:local/root "../scaffolder"}
```

Note: No change needed in root `deps.edn` — the `:dev` alias already includes `libs/devtools/src`, and devtools' own `deps.edn` declares the scaffolder as a local dependency, so its sources are transitively available on the classpath.

- [ ] **Step 2: Write failing tests for prototype context building**

Create `libs/devtools/test/boundary/devtools/core/prototype_test.clj`:

```clojure
(ns boundary.devtools.core.prototype-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.core.prototype :as prototype]))

(def sample-spec
  {:fields {:customer [:string {:min 1}]
            :amount   [:decimal {:min 0}]
            :status   [:enum [:draft :sent :paid]]
            :due-date :date}
   :endpoints [:crud :list]})

(deftest build-scaffold-context-test
  (testing "maps prototype spec to scaffolder-compatible context"
    (let [ctx (prototype/build-scaffold-context "invoice" sample-spec)]
      (is (= "invoice" (:module-name ctx)))
      (is (vector? (:entities ctx)))
      (is (= 1 (count (:entities ctx))))
      (let [entity (first (:entities ctx))]
        (is (= "invoice" (:entity-name entity)))
        (is (= 4 (count (:fields entity))))
        ;; Verify fields have scaffolder-expected keys (from template/build-field-context)
        (let [first-field (first (:fields entity))]
          (is (contains? first-field :field-name-kebab))
          (is (contains? first-field :malli-type)))))))

(deftest endpoints-to-generators-test
  (testing "maps endpoint keywords to generator function names"
    (let [generators (prototype/endpoints-to-generators [:crud :list :search])]
      (is (contains? (set generators) :schema))
      (is (contains? (set generators) :ports))
      (is (contains? (set generators) :core))
      (is (contains? (set generators) :persistence))
      (is (contains? (set generators) :service))
      (is (contains? (set generators) :http)))))

(deftest build-migration-spec-test
  (testing "converts field spec to migration column definitions"
    (let [columns (prototype/build-migration-spec "invoice" (:fields sample-spec))]
      (is (vector? columns))
      (is (>= (count columns) 4))
      ;; Should include id and timestamps beyond the user fields
      (is (some #(= :id (:name %)) columns)))))

(deftest field-type-mapping-test
  (testing "maps Malli types to SQL types"
    (is (= "VARCHAR(255)" (prototype/malli->sql-type [:string {:min 1}])))
    (is (= "DECIMAL" (prototype/malli->sql-type [:decimal {:min 0}])))
    (is (= "DATE" (prototype/malli->sql-type :date)))
    (is (= "VARCHAR(50)" (prototype/malli->sql-type [:enum [:draft :sent :paid]])))))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.prototype-test`
Expected: FAIL — namespace not found.

- [ ] **Step 4: Implement core prototype**

Create `libs/devtools/src/boundary/devtools/core/prototype.clj`:

```clojure
(ns boundary.devtools.core.prototype
  "Pure functions for mapping prototype specs to scaffolder contexts
   and migration definitions.
   Delegates to scaffolder's template functions for context building
   to ensure generated output matches what generators expect."
  (:require [clojure.string :as str]
            [boundary.scaffolder.core.template :as tmpl]))

(defn malli->sql-type
  "Map a Malli type spec to a SQL column type."
  [malli-spec]
  (let [type-kw (if (vector? malli-spec) (first malli-spec) malli-spec)]
    (case type-kw
      :string     "VARCHAR(255)"
      :int        "INTEGER"
      :integer    "INTEGER"
      :decimal    "DECIMAL"
      :double     "DOUBLE"
      :float      "FLOAT"
      :boolean    "BOOLEAN"
      :date       "DATE"
      :instant    "TIMESTAMP"
      :timestamp  "TIMESTAMP"
      :uuid       "UUID"
      :enum       "VARCHAR(50)"
      "VARCHAR(255)")))

(defn- malli-spec->field-type-str
  "Map a Malli spec to the field type string the scaffolder expects."
  [malli-spec]
  (let [type-kw (if (vector? malli-spec) (first malli-spec) malli-spec)]
    (name type-kw)))

(defn build-scaffold-context
  "Map a prototype spec to a scaffolder-compatible context.
   Delegates to template/build-module-context to produce the exact
   format that generators expect (including :field-name-kebab, :malli-type, etc.)."
  [module-name spec]
  (let [field-defs (mapv (fn [[field-name malli-spec]]
                           {:name     (name field-name)
                            :type     (malli-spec->field-type-str malli-spec)
                            :required true})
                         (:fields spec))
        request {:module-name module-name
                 :entities    [{:name   module-name
                                :fields field-defs}]}]
    (tmpl/build-module-context request)))

(defn endpoints-to-generators
  "Map endpoint keywords to the set of generators needed."
  [endpoints]
  (let [endpoint-set (set endpoints)
        ;; All endpoint types need these base generators
        base #{:schema :ports :core :service :persistence}
        ;; CRUD and list/search need HTTP handlers
        needs-http (some #{:crud :list :search} endpoint-set)]
    (vec (cond-> base
           needs-http (conj :http)))))

(defn build-migration-spec
  "Convert a field spec to migration column definitions."
  [module-name fields]
  (let [user-columns (mapv (fn [[field-name malli-spec]]
                             {:name     field-name
                              :sql-type (malli->sql-type malli-spec)
                              :not-null true})
                           fields)]
    (vec (concat
           [{:name :id :sql-type "UUID" :primary-key true}]
           user-columns
           [{:name :created-at :sql-type "TIMESTAMP" :not-null true :default "CURRENT_TIMESTAMP"}
            {:name :updated-at :sql-type "TIMESTAMP" :not-null true :default "CURRENT_TIMESTAMP"}]))))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.prototype-test`
Expected: PASS — all 4 tests.

- [ ] **Step 6: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/core/prototype.clj
clj-paren-repair libs/devtools/test/boundary/devtools/core/prototype_test.clj
git add libs/devtools/src/boundary/devtools/core/prototype.clj \
        libs/devtools/test/boundary/devtools/core/prototype_test.clj \
        libs/devtools/deps.edn
git commit -m "feat(devtools): add core prototype — spec-to-context mapping, SQL type mapping"
```

---

## Task 7: Shell Prototype and Scaffold

**Files:**
- Create: `libs/devtools/src/boundary/devtools/shell/prototype.clj`
- Modify: `libs/devtools/src/boundary/devtools/shell/repl.clj`

- [ ] **Step 1: Read the scaffolder generators to understand the context format**

Read `libs/scaffolder/src/boundary/scaffolder/core/generators.clj` lines 31-50 and `libs/scaffolder/src/boundary/scaffolder/core/template.clj` lines 128-193 to understand exactly what fields the generators expect.

- [ ] **Step 2: Implement shell prototype**

Create `libs/devtools/src/boundary/devtools/shell/prototype.clj`:

```clojure
(ns boundary.devtools.shell.prototype
  "Orchestrates module generation: scaffold → migrate → reset → summary."
  (:require [boundary.devtools.core.prototype :as core]
            [boundary.scaffolder.core.generators :as gen]
            [boundary.scaffolder.core.template :as tmpl]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- write-file!
  "Write content to a file, creating parent directories."
  [path content]
  (let [f (io/file path)]
    (io/make-parents f)
    (spit f content)
    path))

(defn- generate-module-files!
  "Generate all files for a module using scaffolder generators."
  [module-name ctx generators]
  (let [base-dir (str "libs/" module-name)
        src-dir  (str base-dir "/src/boundary/" module-name)
        files    (atom [])]
    ;; Schema
    (when (contains? (set generators) :schema)
      (swap! files conj
             (write-file! (str src-dir "/schema.clj")
                          (gen/generate-schema-file ctx))))
    ;; Ports
    (when (contains? (set generators) :ports)
      (swap! files conj
             (write-file! (str src-dir "/ports.clj")
                          (gen/generate-ports-file ctx))))
    ;; Core
    (when (contains? (set generators) :core)
      (swap! files conj
             (write-file! (str src-dir "/core/validation.clj")
                          (gen/generate-core-file ctx))))
    ;; Service
    (when (contains? (set generators) :service)
      (swap! files conj
             (write-file! (str src-dir "/shell/service.clj")
                          (gen/generate-service-file ctx))))
    ;; Persistence
    (when (contains? (set generators) :persistence)
      (swap! files conj
             (write-file! (str src-dir "/shell/persistence.clj")
                          (gen/generate-persistence-file ctx))))
    ;; HTTP
    (when (contains? (set generators) :http)
      (swap! files conj
             (write-file! (str src-dir "/shell/http.clj")
                          (gen/generate-http-file ctx))))
    ;; deps.edn
    (swap! files conj
           (write-file! (str base-dir "/deps.edn")
                        (gen/generate-project-deps module-name)))
    @files))

(defn scaffold!
  "Generate module files from a name and field spec.
   Does NOT integrate, migrate, or reset."
  [module-name opts]
  (let [spec {:fields (:fields opts) :endpoints (or (:endpoints opts) [:crud])}
        ctx  (core/build-scaffold-context module-name spec)
        generators (core/endpoints-to-generators (:endpoints spec))
        files (generate-module-files! module-name ctx generators)]
    (println (format "\n✓ Module '%s' generated at libs/%s/" module-name module-name))
    (println "\nGenerated files:")
    (doseq [f files]
      (println (str "  " f)))
    (println "\nNext steps:")
    (println (format "  1. Review schema:  libs/%s/src/boundary/%s/schema.clj"
                     module-name module-name))
    (println (format "  2. Wire module:    bb scaffold integrate %s" module-name))
    (println (format "  3. Add migration:  bb migrate create add-%s-table" module-name))
    (println (format "  4. Run tests:      clojure -M:test:db/h2 :%s" module-name))
    files))

(defn prototype!
  "Generate a complete working module: scaffold + migrate + reset.
   reset-fn should be the REPL's reset function."
  [module-name spec reset-fn]
  (let [generators (core/endpoints-to-generators
                     (or (:endpoints spec) [:crud]))
        ctx (core/build-scaffold-context module-name spec)
        files (generate-module-files! module-name ctx generators)
        migration-spec (core/build-migration-spec module-name (:fields spec))]
    ;; Generate migration
    (let [migration-num (System/currentTimeMillis)
          migration-content (gen/generate-migration-file ctx migration-num)
          migration-path (format "resources/migrations/%d-add-%s-table.sql"
                                 migration-num module-name)]
      (write-file! migration-path migration-content)
      (println (format "\n✓ Module '%s' prototyped:" module-name))
      (println "\nGenerated files:")
      (doseq [f (conj files migration-path)]
        (println (str "  " f)))
      ;; Run migration
      (println "\nRunning migration...")
      (let [result (clojure.java.shell/sh "bb" "migrate" "up")]
        (if (zero? (:exit result))
          (println "  ✓ Migration applied")
          (println (str "  ⚠ Migration failed: " (:err result)))))
      ;; Reset system
      (println "\nResetting system to load new module...")
      (reset-fn)
      (println (format "\n✓ Module '%s' is live!" module-name))
      (println (format "  Try: (simulate :get \"/api/%s\")" module-name)))))
```

- [ ] **Step 3: Add restart-component to repl.clj**

Read `libs/devtools/src/boundary/devtools/shell/repl.clj` to find the right location, then add:

```clojure
(defn restart-component
  "Halt and reinitialize a single Integrant component.
   system-var: the var holding the running system (e.g. #'integrant.repl.state/system)
   config: the Integrant config map
   component-key: the key to restart

   Note: integrant.repl.state/system is a plain def, not an atom.
   We use alter-var-root to update it atomically."
  [system-var config component-key]
  (let [system (var-get system-var)]
    (if-not (contains? system component-key)
      (do
        (println (format "Component %s not found in system." component-key))
        (println "Available components:")
        (doseq [k (sort (keys system))]
          (println (str "  " k)))
        nil)
      (do
        (println (format "Restarting %s..." component-key))
        (alter-var-root system-var
          (fn [sys]
            (integrant.core/halt-key! component-key (get sys component-key))
            (let [resolved-config (get (integrant.core/prep config) component-key)
                  new-val (integrant.core/init-key component-key resolved-config)]
              (assoc sys component-key new-val))))
        (println (format "✓ %s restarted." component-key))
        (get (var-get system-var) component-key)))))
```

Add `integrant.core` to the requires in `repl.clj` (line 1-12).

- [ ] **Step 4: Run all devtools tests**

Run: `clojure -M:test:db/h2 :devtools`
Expected: All tests pass.

- [ ] **Step 5: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/prototype.clj
clj-paren-repair libs/devtools/src/boundary/devtools/shell/repl.clj
git add libs/devtools/src/boundary/devtools/shell/prototype.clj \
        libs/devtools/src/boundary/devtools/shell/repl.clj
git commit -m "feat(devtools): add scaffold!, prototype!, restart-component"
```

---

## Task 8: Wire REPL Helpers into user.clj

**Why last:** All the underlying functions exist. Now expose them as the top-level API.

**Files:**
- Modify: `dev/repl/user.clj`

- [ ] **Step 1: Read current user.clj to understand the pattern**

Read `dev/repl/user.clj` fully to see exactly how existing helpers are exposed.

- [ ] **Step 2: Add requires for new namespaces**

Add to the `:require` block (lines 1-31):

```clojure
[boundary.devtools.shell.recording :as rec]
[boundary.devtools.shell.router :as dev-router]
[boundary.devtools.shell.prototype :as prototype]
```

**Important:** The alias is `:as rec` (not `:as recording`) to avoid conflicting with the `recording` function defined in this namespace.

- [ ] **Step 3: Add the recording dispatcher function**

Add the `recording` multimethod-style dispatcher. This is the main entry point — a single function that dispatches on the first argument:

```clojure
(defn recording
  "Time-travel debugging: capture, replay, and diff HTTP requests.
   (recording :start)              — start capturing
   (recording :stop)               — stop capturing
   (recording :list)               — show captured requests
   (recording :replay N)           — replay entry N
   (recording :replay N overrides) — replay with modified body
   (recording :diff M N)           — diff two entries
   (recording :save \"name\")      — save to disk
   (recording :load \"name\")      — load from disk"
  [command & args]
  (case command
    :start  (rec/start-recording!)
    :stop   (rec/stop-recording!)
    :list   (rec/list-entries)
    :replay (let [idx (first args)
                  overrides (second args)
                  simulate-fn (fn [method path opts] (simulate method path opts))]
              (rec/replay-entry! idx simulate-fn overrides))
    :diff   (rec/diff-entries (first args) (second args))
    :save   (rec/save-session! (first args))
    :load   (rec/load-session! (first args))
    (println (str "Unknown recording command: " command
                  ". Use :start, :stop, :list, :replay, :diff, :save, :load"))))
```

- [ ] **Step 4: Add defroute!, remove-route!, dynamic-routes**

```clojure
(defn defroute!
  "Add a route at runtime for rapid prototyping.
   (defroute! :get \"/api/test\" (fn [req] {:status 200 :body {:hello \"world\"}}))"
  [method path handler-fn]
  (dev-router/add-dynamic-route! method path handler-fn)
  ;; TODO: rebuild router when system wiring is available
  (println (format "✓ Route added: %s %s" (name method) path)))

(defn remove-route!
  "Remove a dynamically added route."
  [method path]
  (dev-router/remove-dynamic-route! method path)
  (println (format "✓ Route removed: %s %s" (name method) path)))

(defn dynamic-routes
  "List all dynamically added routes."
  []
  (let [routes (dev-router/list-dynamic-routes)]
    (if (empty? routes)
      (println "No dynamic routes.")
      (doseq [{:keys [method path]} routes]
        (println (format "  %s %s" (name method) path))))))
```

- [ ] **Step 5: Add tap-handler!, untap-handler!, taps**

```clojure
(defn tap-handler!
  "Intercept requests to a handler with a callback function.
   (tap-handler! :create-user (fn [ctx] (println (:request ctx)) ctx))"
  [handler-kw callback-fn]
  (dev-router/add-tap! handler-kw callback-fn)
  (println (format "✓ Tap installed on %s" handler-kw)))

(defn untap-handler!
  "Remove a tap from a handler."
  [handler-kw]
  (dev-router/remove-tap! handler-kw)
  (println (format "✓ Tap removed from %s" handler-kw)))

(defn taps
  "List active handler taps."
  []
  (let [tap-list (dev-router/list-taps)]
    (if (empty? tap-list)
      (println "No active taps.")
      (doseq [t tap-list]
        (println (str "  " t))))))
```

- [ ] **Step 6: Add restart-component**

```clojure
(defn restart-component
  "Hot-swap a single Integrant component without full system reset.
   (restart-component :boundary/http-server)"
  [component-key]
  (require 'boundary.devtools.shell.repl)
  (let [restart-fn (resolve 'boundary.devtools.shell.repl/restart-component)]
    (restart-fn #'integrant.repl.state/system
                (integrant.repl.state/config)
                component-key)))
```

**Important:** `integrant.repl.state/system` is a plain `def` (not an atom). We pass the var itself (`#'integrant.repl.state/system`) so the implementation can use `alter-var-root` to update it atomically.

- [ ] **Step 7: Add scaffold! and prototype!**

```clojure
(defn scaffold!
  "Generate a module from the REPL.
   (scaffold! \"invoice\" {:fields {:customer [:string {:min 1}]
                                    :amount [:decimal {:min 0}]}})"
  [module-name opts]
  (prototype/scaffold! module-name opts))

(defn prototype!
  "Generate a complete working module: scaffold + migrate + reset.
   (prototype! :invoice
     {:fields {:customer [:string {:min 1}]
               :amount [:decimal {:min 0}]
               :status [:enum [:draft :sent :paid]]}
      :endpoints [:crud :list]})"
  [module-name spec]
  (let [name-str (if (keyword? module-name) (name module-name) module-name)]
    (prototype/prototype! name-str spec reset)))
```

- [ ] **Step 8: Update the commands palette**

Find the existing `commands` function in `user.clj` and add the new commands to the appropriate groups. Look for where `(commands)` is defined and add:

```clojure
;; In the commands map, add to relevant groups:
;; Debug group:
;;   (recording)    — time-travel debugging
;;   (tap-handler!) — intercept handler requests
;;   (taps)         — list active taps
;; System group:
;;   (restart-component) — hot-swap single component
;;   (defroute!)    — add route at runtime
;;   (remove-route!) — remove dynamic route
;;   (dynamic-routes) — list dynamic routes
;; Generate group:
;;   (scaffold!)    — generate module from REPL
;;   (prototype!)   — generate + migrate + reset
```

- [ ] **Step 9: Wire clear-dynamic-state! into reset**

Find the `reset` function in `user.clj` (lines 62-73) and add `dev-router/clear-dynamic-state!` call before `ig-repl/reset`:

```clojure
;; In the reset function, before calling ig-repl/reset:
(dev-router/clear-dynamic-state!)
```

This ensures dynamic routes/taps are cleared on every system reset.

- [ ] **Step 10: Run all devtools tests**

Run: `clojure -M:test:db/h2 :devtools`
Expected: All tests pass.

- [ ] **Step 11: Run paren repair and commit**

```bash
clj-paren-repair dev/repl/user.clj
git add dev/repl/user.clj
git commit -m "feat(devtools): wire Phase 5 REPL helpers into user.clj

Exposes: recording, defroute!, remove-route!, dynamic-routes,
tap-handler!, untap-handler!, taps, restart-component,
scaffold!, prototype!. Clears dynamic state on reset."
```

---

## Task 9: Integration Verification

**Why:** End-to-end verification that everything works together.

**Files:** None (verification only)

- [ ] **Step 1: Run full devtools test suite**

Run: `clojure -M:test:db/h2 :devtools`
Expected: All tests pass.

- [ ] **Step 2: Run platform test suite (handler-atom change)**

Run: `clojure -M:test:db/h2 :platform`
Expected: All tests pass.

- [ ] **Step 3: Run quality checks**

Run: `bb check`
Expected: All checks pass (fcis, deps, placeholder, kondo, doctor).

- [ ] **Step 4: Run linting**

Run: `clojure -M:clj-kondo --lint libs/devtools/src libs/devtools/test`
Expected: No errors (warnings OK).

- [ ] **Step 5: Verify the commands palette includes new entries**

Manually verify by reading the updated `commands` function in `user.clj` — it should list all new Phase 5 functions.

- [ ] **Step 6: Final commit if any fixes were needed**

```bash
git add -u
git commit -m "fix(devtools): address Phase 5 integration issues"
```

---

## Task Dependencies

```
Task 1 (Platform handler-atom)
  ↓
Task 2 (Core recording)  ─── Task 3 (Core router)  ─── Task 6 (Core prototype)
  ↓                            ↓
Task 5 (Shell recording) ─── Task 4 (Shell router)
  ↓                            ↓
  └────── Task 7 (Shell prototype + scaffold + restart-component) ──┘
                               ↓
                    Task 8 (Wire into user.clj)
                               ↓
                    Task 9 (Integration verification)
```

Tasks 2, 3, and 6 are independent pure-core tasks and can run in parallel.
Tasks 4 and 5 depend on 2 and 3 respectively.
Task 7 depends on 4 and 6.
Task 8 depends on all shell tasks.
Task 9 is final verification.
