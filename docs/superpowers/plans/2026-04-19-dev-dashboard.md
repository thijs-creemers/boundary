# Dev Dashboard Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local web dashboard at localhost:9999 with 6 pages providing x-ray vision into the running Boundary system.

**Architecture:** Integrant component (`server.clj`) starts a separate Jetty server on port 9999 with Reitit routing. Pages are server-rendered Hiccup with HTMX polling for live data. A request-capture middleware on the main HTTP pipeline (port 3000) feeds the Request Inspector. All data access goes through existing pure introspection functions.

**Tech Stack:** Clojure 1.12.4, Integrant, Ring/Reitit, Hiccup, HTMX, Alpine.js, Jetty, Malli

**Spec:** `docs/superpowers/specs/2026-04-19-dev-dashboard-design.md`

---

## File Structure

### New files

| File | Responsibility |
|------|---------------|
| `libs/devtools/src/boundary/devtools/shell/dashboard/server.clj` | Integrant component, Reitit router, Jetty on port 9999 |
| `libs/devtools/src/boundary/devtools/shell/dashboard/middleware.clj` | Request capture Ring middleware + bounded request log atom |
| `libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj` | Dashboard shell: sidebar, top bar, page wrapper Hiccup |
| `libs/devtools/src/boundary/devtools/shell/dashboard/components.clj` | Reusable UI components: stat cards, data tables, badges, code blocks |
| `libs/devtools/src/boundary/devtools/shell/dashboard/pages/overview.clj` | System Overview page |
| `libs/devtools/src/boundary/devtools/shell/dashboard/pages/routes.clj` | Route Explorer page |
| `libs/devtools/src/boundary/devtools/shell/dashboard/pages/requests.clj` | Request Inspector page |
| `libs/devtools/src/boundary/devtools/shell/dashboard/pages/schemas.clj` | Schema Browser page |
| `libs/devtools/src/boundary/devtools/shell/dashboard/pages/database.clj` | Database Explorer page |
| `libs/devtools/src/boundary/devtools/shell/dashboard/pages/errors.clj` | Error Dashboard page |
| `libs/devtools/resources/dashboard/assets/dashboard.css` | Dark theme CSS |
| `libs/devtools/test/boundary/devtools/shell/dashboard/server_test.clj` | Integration tests: server startup, page responses |
| `libs/devtools/test/boundary/devtools/shell/dashboard/middleware_test.clj` | Unit tests: request capture, buffer bounds, sanitization |
| `libs/devtools/test/boundary/devtools/shell/dashboard/components_test.clj` | Unit tests: Hiccup component rendering |

### Modified files

| File | Change |
|------|--------|
| `src/boundary/config.clj` | Add `dashboard-module-config`, merge into `ig-config`, add request capture middleware |
| `resources/conf/dev/config.edn` | Add `:boundary/dashboard {:port 9999}` |
| `dev/repl/user.clj` | Require dashboard server namespace, update startup dashboard to show dashboard URL |
| `libs/devtools/deps.edn` | Add ring-jetty-adapter, hiccup, ui-style dependencies |

---

## Task 1: Request Capture Middleware

The middleware is needed by both the dashboard pages and the config wiring, so we build it first as a standalone unit.

**Files:**
- Create: `libs/devtools/src/boundary/devtools/shell/dashboard/middleware.clj`
- Test: `libs/devtools/test/boundary/devtools/shell/dashboard/middleware_test.clj`

- [ ] **Step 1: Write failing tests for request capture**

```clojure
;; libs/devtools/test/boundary/devtools/shell/dashboard/middleware_test.clj
(ns boundary.devtools.shell.dashboard.middleware-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.devtools.shell.dashboard.middleware :as middleware]))

(use-fixtures :each
  (fn [f]
    (middleware/clear-request-log!)
    (f)))

(deftest ^:unit wrap-request-capture-test
  (testing "captures request/response pair"
    (let [handler (middleware/wrap-request-capture
                   (fn [_req] {:status 200 :body "ok"}))
          _       (handler {:request-method :get :uri "/api/users"})]
      (is (= 1 (count (middleware/request-log))))
      (let [entry (first (middleware/request-log))]
        (is (= :get (:method entry)))
        (is (= "/api/users" (:path entry)))
        (is (= 200 (:status entry)))
        (is (uuid? (:id entry)))
        (is (inst? (:timestamp entry)))
        (is (number? (:duration-ms entry))))))

  (testing "respects buffer limit of 200"
    (let [handler (middleware/wrap-request-capture
                   (fn [_req] {:status 200 :body "ok"}))]
      (dotimes [i 210]
        (handler {:request-method :get :uri (str "/req/" i)}))
      (is (= 200 (count (middleware/request-log))))
      ;; newest first, oldest dropped — last entry is /req/10 (entries 210..11, index 0..199)
      (is (= "/req/10" (:path (nth (middleware/request-log) 199))))))

  (testing "sanitizes sensitive headers"
    (let [handler (middleware/wrap-request-capture
                   (fn [_req] {:status 200 :body "ok"}))
          _       (handler {:request-method :get
                            :uri "/api/me"
                            :headers {"authorization" "Bearer secret123"
                                      "content-type" "application/json"}})]
      (let [entry (first (middleware/request-log))
            hdrs  (get-in entry [:request :headers])]
        (is (= "[REDACTED]" (get hdrs "authorization")))
        (is (= "application/json" (get hdrs "content-type")))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.dashboard.middleware-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Implement request capture middleware**

```clojure
;; libs/devtools/src/boundary/devtools/shell/dashboard/middleware.clj
(ns boundary.devtools.shell.dashboard.middleware
  (:require [clojure.string :as str])
  (:import [java.time Instant]))

(def ^:private max-entries 200)

(defonce ^:private request-log* (atom []))

(def ^:private sensitive-headers
  #{"authorization" "cookie" "x-api-key" "x-auth-token"})

(defn request-log
  "Return captured requests, newest first."
  []
  @request-log*)

(defn clear-request-log! []
  (reset! request-log* []))

(defn- sanitize-headers [headers]
  (reduce-kv (fn [m k v]
               (assoc m k (if (contains? sensitive-headers (str/lower-case (str k)))
                            "[REDACTED]"
                            v)))
             {} headers))

(defn- truncate-body [body]
  (when body
    (let [s (str body)]
      (if (> (count s) 2000)
        (subs s 0 2000)
        s))))

(defn wrap-request-capture
  "Ring middleware that captures request/response pairs into a bounded log."
  [handler]
  (fn [request]
    (let [start-ns (System/nanoTime)
          response (handler request)
          duration (/ (- (System/nanoTime) start-ns) 1e6)]
      (swap! request-log*
             (fn [log]
               (let [entry {:id          (random-uuid)
                            :timestamp   (Instant/now)
                            :method      (:request-method request)
                            :path        (:uri request)
                            :status      (:status response)
                            :duration-ms (Math/round ^double duration)
                            :request     {:headers     (sanitize-headers (:headers request))
                                          :params      (:params request)
                                          :body-params (:body-params request)}
                            :response    {:status  (:status response)
                                          :headers (:headers response)
                                          :body    (truncate-body (:body response))}}
                     new-log (into [entry] log)]
                 (if (> (count new-log) max-entries)
                   (subvec new-log 0 max-entries)
                   new-log))))
      response)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.dashboard.middleware-test`
Expected: PASS (3 tests)

- [ ] **Step 5: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/middleware.clj
clj-paren-repair libs/devtools/test/boundary/devtools/shell/dashboard/middleware_test.clj
git add libs/devtools/src/boundary/devtools/shell/dashboard/middleware.clj \
        libs/devtools/test/boundary/devtools/shell/dashboard/middleware_test.clj
git commit -m "feat(devtools): add request capture middleware for dev dashboard"
```

---

## Task 2: Dashboard CSS

Static asset needed by all pages. No tests — pure CSS.

**Files:**
- Create: `libs/devtools/resources/dashboard/assets/dashboard.css`

- [ ] **Step 1: Write the dashboard dark theme CSS**

```css
/* libs/devtools/resources/dashboard/assets/dashboard.css */

/* === Reset & Base === */
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

:root {
  --bg-base: #0f172a;
  --bg-card: #1e293b;
  --bg-sidebar: #1e293b;
  --bg-hover: #334155;
  --bg-stripe: #0f172a;
  --border: #334155;
  --text-primary: #e2e8f0;
  --text-secondary: #94a3b8;
  --text-muted: #64748b;
  --accent-blue: #38bdf8;
  --accent-green: #22c55e;
  --accent-green-light: #4ade80;
  --accent-red: #f87171;
  --accent-yellow: #fbbf24;
  --accent-purple: #a78bfa;
  --font-mono: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace;
  --font-sans: 'DM Sans', system-ui, -apple-system, sans-serif;
  --sidebar-width: 220px;
  --sidebar-collapsed: 56px;
  --topbar-height: 48px;
}

body {
  font-family: var(--font-sans);
  background: var(--bg-base);
  color: var(--text-primary);
  line-height: 1.5;
  min-height: 100vh;
}

/* === Layout === */
.dashboard-layout {
  display: flex;
  min-height: 100vh;
}

.dashboard-sidebar {
  width: var(--sidebar-width);
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  z-index: 10;
  transition: width 0.2s ease;
}

.dashboard-sidebar.collapsed { width: var(--sidebar-collapsed); }
.dashboard-sidebar.collapsed .nav-label,
.dashboard-sidebar.collapsed .sidebar-brand-text,
.dashboard-sidebar.collapsed .sidebar-status-text { display: none; }

.sidebar-brand {
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid var(--border);
}

.sidebar-brand-icon { color: var(--accent-blue); font-size: 18px; font-weight: 700; }
.sidebar-brand-text { color: var(--accent-blue); font-weight: 700; font-size: 15px; white-space: nowrap; }

.sidebar-nav { flex: 1; padding: 8px; }

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 6px;
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 13px;
  margin-bottom: 2px;
  transition: background 0.15s, color 0.15s;
}

.nav-item:hover { background: var(--bg-hover); color: var(--text-primary); }
.nav-item.active { background: var(--bg-hover); color: var(--text-primary); }
.nav-icon { width: 18px; text-align: center; flex-shrink: 0; }

.sidebar-footer {
  padding: 12px 16px;
  border-top: 1px solid var(--border);
  font-size: 11px;
}

.sidebar-toggle {
  padding: 12px 16px;
  border-top: 1px solid var(--border);
  cursor: pointer;
  color: var(--text-muted);
  font-size: 12px;
  text-align: center;
}

.sidebar-toggle:hover { color: var(--text-secondary); }

.dashboard-main {
  margin-left: var(--sidebar-width);
  flex: 1;
  display: flex;
  flex-direction: column;
  transition: margin-left 0.2s ease;
}

.sidebar-collapsed ~ .dashboard-main { margin-left: var(--sidebar-collapsed); }

.dashboard-topbar {
  height: var(--topbar-height);
  background: var(--bg-card);
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  padding: 0 20px;
  gap: 16px;
  position: sticky;
  top: 0;
  z-index: 5;
}

.topbar-title { font-size: 14px; font-weight: 600; }
.topbar-status { font-size: 11px; color: var(--text-muted); margin-left: auto; }
.topbar-link { font-size: 11px; color: var(--accent-blue); text-decoration: none; }
.topbar-link:hover { text-decoration: underline; }

.dashboard-content { padding: 20px; flex: 1; }

/* === Components === */
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 24px; }
.stat-grid-2 { grid-template-columns: repeat(2, 1fr); }

.stat-card {
  background: var(--bg-card);
  padding: 16px;
  border-radius: 8px;
  border: 1px solid var(--border);
}

.stat-label {
  font-size: 11px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.stat-value { font-size: 28px; font-weight: 700; color: var(--accent-blue); margin-top: 4px; }
.stat-value.green { color: var(--accent-green-light); }
.stat-value.red { color: var(--accent-red); }
.stat-value.muted { color: var(--text-muted); }
.stat-sub { font-size: 11px; color: var(--text-muted); margin-top: 4px; }
.stat-sub.healthy { color: var(--accent-green); }

.card {
  background: var(--bg-card);
  border-radius: 8px;
  border: 1px solid var(--border);
  overflow: hidden;
  margin-bottom: 16px;
}

.card-header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-body { padding: 16px; }
.card-body-flush { padding: 0; }

.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }

/* === Data Table === */
.data-table { width: 100%; font-size: 12px; font-family: var(--font-mono); }

.data-table-header {
  display: grid;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
  color: var(--text-muted);
  font-family: var(--font-sans);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.data-table-row {
  display: grid;
  padding: 8px 16px;
  border-bottom: 1px solid var(--bg-base);
  align-items: center;
  cursor: pointer;
  transition: background 0.1s;
}

.data-table-row:nth-child(even) { background: var(--bg-stripe); }
.data-table-row:hover { background: var(--bg-hover); }
.data-table-row.expanded { border: 1px solid var(--accent-blue); border-radius: 8px; }

/* === Badges === */
.method-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
  font-family: var(--font-mono);
  width: fit-content;
}

.method-get { background: #164e63; color: #22d3ee; }
.method-post { background: #14532d; color: #4ade80; }
.method-put { background: #713f12; color: #fbbf24; }
.method-delete { background: #7f1d1d; color: #fca5a5; }
.method-patch { background: #312e81; color: #a78bfa; }

.status-healthy { color: var(--accent-green); }
.status-error { color: var(--accent-red); }
.status-warning { color: var(--accent-yellow); }
.status-info { color: var(--accent-blue); }

.count-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 10px;
}

.count-badge.yellow { background: #451a03; color: var(--accent-yellow); }
.count-badge.purple { background: #312e81; color: var(--accent-purple); }
.count-badge.red { background: #7f1d1d; color: var(--accent-red); }

.error-code { font-family: var(--font-mono); font-weight: 600; min-width: 70px; }

/* === Interceptor Chain === */
.interceptor-chain {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  font-family: var(--font-mono);
  font-size: 12px;
}

.interceptor-step { background: var(--bg-hover); padding: 4px 10px; border-radius: 4px; color: var(--text-secondary); }
.interceptor-step.handler { background: #164e63; color: #22d3ee; font-weight: 600; }
.interceptor-step.auth { background: #312e81; color: var(--accent-purple); }
.interceptor-arrow { color: #475569; }

/* === Detail Panel === */
.detail-panel {
  background: var(--bg-card);
  border-radius: 8px;
  border: 1px solid var(--accent-blue);
  overflow: hidden;
  margin-top: 16px;
}

.detail-panel.error { border-color: var(--accent-red); }

.detail-header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-split { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--border); }
.detail-split > :first-child { border-right: 1px solid var(--border); }
.detail-pane { padding: 12px 16px; }

.detail-label {
  color: var(--text-muted);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 8px;
}

/* === Code blocks === */
pre.code-block {
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-secondary);
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.code-inline {
  background: var(--bg-hover);
  padding: 2px 6px;
  border-radius: 3px;
  font-family: var(--font-mono);
  font-size: 11px;
}

/* === Fix suggestion box === */
.fix-box {
  background: var(--bg-base);
  border-radius: 6px;
  padding: 12px;
  border: 1px solid var(--border);
}

.fix-command { color: var(--accent-green-light); font-family: var(--font-mono); font-size: 12px; }

/* === Stack trace === */
.stacktrace-user { color: var(--text-primary); }
.stacktrace-framework { color: var(--text-muted); }
.stacktrace-jvm { color: #475569; }

/* === Filters === */
.filter-bar { display: flex; gap: 8px; margin-bottom: 16px; align-items: center; }

.filter-input, .filter-select {
  background: var(--bg-card);
  border: 1px solid var(--border);
  color: var(--text-primary);
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 12px;
}

.filter-select { color: var(--text-secondary); }
.filter-count { margin-left: auto; font-size: 12px; color: var(--text-muted); }
.live-indicator { font-size: 11px; color: var(--accent-green); }

/* === Schema browser === */
.schema-layout { display: grid; grid-template-columns: 220px 1fr; gap: 16px; min-height: 400px; }

.schema-list-item {
  padding: 7px 12px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
}

.schema-list-item:nth-child(even) { background: var(--bg-stripe); }
.schema-list-item:hover { color: var(--text-primary); }
.schema-list-item.active {
  background: var(--bg-hover);
  color: var(--accent-blue);
  border-left: 2px solid var(--accent-blue);
}

.schema-field { font-family: var(--font-mono); font-size: 12px; line-height: 2; }
.schema-required { color: var(--accent-red); }
.schema-optional { color: var(--text-muted); }
.schema-field-name { color: var(--accent-blue); }
.schema-field-type { color: var(--accent-purple); }

.tab-bar { display: flex; gap: 0; margin-bottom: 0; }

.tab {
  padding: 8px 16px;
  font-size: 12px;
  cursor: pointer;
  color: var(--text-muted);
}

.tab.active {
  background: var(--bg-card);
  color: var(--accent-blue);
  border: 1px solid var(--border);
  border-bottom: 1px solid var(--bg-card);
  border-radius: 8px 8px 0 0;
  font-weight: 600;
}

.tab-content {
  background: var(--bg-card);
  border-radius: 0 8px 8px 8px;
  border: 1px solid var(--border);
  border-top: none;
  padding: 12px 16px;
}

/* === Migration list === */
.migration-applied { color: var(--accent-green); font-size: 11px; }
.migration-pending { color: var(--accent-yellow); font-size: 11px; }

/* === Pool stats === */
.pool-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; padding: 16px; }
.pool-label { font-size: 11px; color: var(--text-muted); }
.pool-value { font-size: 24px; font-weight: 700; }

/* === Query runner === */
.query-runner textarea {
  width: 100%;
  min-height: 80px;
  background: var(--bg-base);
  border: 1px solid var(--border);
  color: var(--text-primary);
  font-family: var(--font-mono);
  font-size: 12px;
  padding: 12px;
  border-radius: 6px;
  resize: vertical;
}

.query-runner button {
  background: var(--accent-blue);
  color: #0f172a;
  border: none;
  padding: 6px 16px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 8px;
}

.query-runner button:hover { opacity: 0.9; }

/* === Try-it panel === */
.try-it-panel textarea {
  width: 100%;
  min-height: 60px;
  background: var(--bg-base);
  border: 1px solid var(--border);
  color: var(--text-primary);
  font-family: var(--font-mono);
  font-size: 12px;
  padding: 8px;
  border-radius: 4px;
  resize: vertical;
}

/* === Utility === */
.text-muted { color: var(--text-muted); }
.text-mono { font-family: var(--font-mono); }
.text-sm { font-size: 12px; }
.text-xs { font-size: 11px; }
.mt-4 { margin-top: 16px; }
.mb-4 { margin-bottom: 16px; }
.flex-between { display: flex; justify-content: space-between; align-items: center; }
```

- [ ] **Step 2: Commit**

```bash
git add libs/devtools/resources/dashboard/assets/dashboard.css
git commit -m "feat(devtools): add dark theme CSS for dev dashboard"
```

---

## Task 3: Dashboard Layout & UI Components

The shell (sidebar, top bar, page wrapper) and reusable Hiccup components used by all pages.

**Files:**
- Create: `libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj`
- Create: `libs/devtools/src/boundary/devtools/shell/dashboard/components.clj`
- Test: `libs/devtools/test/boundary/devtools/shell/dashboard/components_test.clj`

- [ ] **Step 1: Write failing tests for UI components**

```clojure
;; libs/devtools/test/boundary/devtools/shell/dashboard/components_test.clj
(ns boundary.devtools.shell.dashboard.components-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.shell.dashboard.components :as c]))

(deftest ^:unit stat-card-test
  (testing "renders stat card with label and value"
    (let [hiccup (c/stat-card {:label "Components" :value 12})]
      (is (vector? hiccup))
      (is (some #(= 12 %) (flatten hiccup)))
      (is (some #(= "Components" %) (flatten hiccup)))))

  (testing "renders with optional sub text"
    (let [hiccup (c/stat-card {:label "Errors" :value 0 :sub "last error: 2h ago"})]
      (is (some #(= "last error: 2h ago" %) (flatten hiccup))))))

(deftest ^:unit method-badge-test
  (testing "renders color-coded method badges"
    (let [get-badge (c/method-badge :get)
          post-badge (c/method-badge :post)]
      (is (some #(= "GET" %) (flatten get-badge)))
      (is (some #(= "POST" %) (flatten post-badge))))))

(deftest ^:unit data-table-test
  (testing "renders table with header and rows"
    (let [hiccup (c/data-table
                  {:columns ["Name" "Value"]
                   :col-template "1fr 1fr"
                   :rows [{:cells ["foo" "bar"]}
                          {:cells ["baz" "qux"]}]})]
      (is (vector? hiccup))
      (is (some #(= "foo" %) (flatten hiccup)))
      (is (some #(= "qux" %) (flatten hiccup))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.dashboard.components-test`
Expected: FAIL

- [ ] **Step 3: Implement UI components**

```clojure
;; libs/devtools/src/boundary/devtools/shell/dashboard/components.clj
(ns boundary.devtools.shell.dashboard.components
  "Reusable Hiccup UI components for the dev dashboard."
  (:require [clojure.string :as str]))

(defn stat-card
  "Render a stat card. Options: :label :value :value-class :sub :sub-class"
  [{:keys [label value value-class sub sub-class]}]
  [:div.stat-card
   [:div.stat-label label]
   [:div {:class (str "stat-value " (or value-class ""))} value]
   (when sub
     [:div {:class (str "stat-sub " (or sub-class ""))} sub])])

(defn method-badge [method]
  (let [m (name method)]
    [:span {:class (str "method-badge method-" (str/lower-case m))}
     (str/upper-case m)]))

(defn status-dot [status]
  (case status
    :running [:span.status-healthy "● running"]
    :error   [:span.status-error "● error"]
    :stopped [:span.status-warning "● stopped"]
    [:span.text-muted "● unknown"]))

(defn count-badge [count variant]
  [:span {:class (str "count-badge " (or variant "yellow"))} (str "×" count)])

(defn data-table
  "Render a data table. Options: :columns :col-template :rows
   Each row: {:cells [...] :attrs {...}}"
  [{:keys [columns col-template rows]}]
  [:div.data-table
   [:div.data-table-header {:style (str "grid-template-columns:" col-template)}
    (for [col columns]
      [:span col])]
   (for [{:keys [cells attrs]} rows]
     [:div.data-table-row (merge {:style (str "grid-template-columns:" col-template)} attrs)
      (for [cell cells]
        (if (vector? cell) cell [:span cell]))])])

(defn card
  "Render a card with header and body. Options: :title :right :flush? :body"
  [{:keys [title right flush?]} & body]
  [:div.card
   [:div.card-header
    [:span title]
    (when right [:span.text-xs.text-muted right])]
   (into [(if flush? :div.card-body-flush :div.card-body)] body)])

(defn detail-panel
  "Expandable detail panel. Options: :variant (:default or :error) :header :body"
  [{:keys [variant]} & children]
  (into [:div {:class (str "detail-panel " (when (= variant :error) "error"))}]
        children))

(defn filter-bar [& children]
  (into [:div.filter-bar] children))

(defn filter-input [attrs]
  [:input.filter-input (merge {:type "text"} attrs)])

(defn filter-select [attrs options]
  (into [:select.filter-select attrs]
        (for [[value label] options]
          [:option {:value value} label])))

(defn interceptor-chain [steps]
  [:div.interceptor-chain
   (interpose [:span.interceptor-arrow "→"]
              (for [{:keys [name type]} steps]
                [:span {:class (str "interceptor-step " (when type (clojure.core/name type)))}
                 name]))])

(defn fix-box [{:keys [repl-command cli-command]}]
  [:div.fix-box
   [:div.detail-label "Suggested Fix"]
   (when repl-command [:div.fix-command (str "REPL: " repl-command)])
   (when cli-command [:div.fix-command (str "CLI:  " cli-command)])])

(defn code-block [content]
  [:pre.code-block content])
```

- [ ] **Step 4: Implement dashboard layout**

```clojure
;; libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj
(ns boundary.devtools.shell.dashboard.layout
  "Dashboard shell: HTML wrapper, sidebar, top bar."
  (:require [boundary.ui-style :as ui-style]
            [hiccup2.core :as h]))

(def ^:private nav-items
  [{:path "/dashboard"          :icon "◉" :label "Overview"}
   {:path "/dashboard/routes"   :icon "⇢" :label "Routes"}
   {:path "/dashboard/requests" :icon "⟳" :label "Requests"}
   {:path "/dashboard/schemas"  :icon "▤" :label "Schemas"}
   {:path "/dashboard/db"       :icon "⊞" :label "Database"}
   {:path "/dashboard/errors"   :icon "⚠" :label "Errors"}])

(defn- sidebar [{:keys [active-path system-status]}]
  [:div.dashboard-sidebar {:x-data "{collapsed: false}" ":class" "collapsed && 'collapsed'"}
   [:div.sidebar-brand
    [:span.sidebar-brand-icon "⚡"]
    [:span.sidebar-brand-text "Boundary Dev"]]
   [:nav.sidebar-nav
    (for [{:keys [path icon label]} nav-items]
      [:a.nav-item {:href path :class (when (= path active-path) "active")}
       [:span.nav-icon icon]
       [:span.nav-label label]])]
   [:div.sidebar-footer
    [:div.flex-between
     (if (= :running system-status)
       [:span.status-healthy "● running"]
       [:span.status-error "● error"])
     [:span.sidebar-status-text.text-muted ""]]]
   [:div.sidebar-toggle {"@click" "collapsed = !collapsed"}
    [:span {:x-text "collapsed ? '→' : '←'"} "←"]]])

(defn- topbar [{:keys [title component-count error-count http-port]}]
  [:div.dashboard-topbar
   [:span.topbar-title title]
   [:span.topbar-status
    (str "running · " component-count " components · " error-count " errors")]
   [:a.topbar-link {:href (str "http://localhost:" http-port) :target "_blank"} "App"]
   [:a.topbar-link {:href (str "http://localhost:" http-port "/web/admin") :target "_blank"} "Admin"]])

(defn dashboard-page
  "Wrap page content in the full dashboard shell.
   Options: :title :active-path :system-status :component-count :error-count :http-port"
  [{:keys [title active-path] :as opts} & body]
  (str
   (h/html
    (h/raw "<!DOCTYPE html>")
    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title (str title " — Boundary Dev")]
      ;; JS from ui-style
      (for [js-path (ui-style/js-bundle :base)]
        [:script {:src js-path :defer true}])
      ;; Dashboard CSS
      [:link {:rel "stylesheet" :href "/dashboard/assets/dashboard.css"}]
      ;; Fonts from ui-style
      [:link {:rel "stylesheet" :href "/css/fonts.css"}]]
     [:body
      [:div.dashboard-layout
       (sidebar opts)
       [:div.dashboard-main
        (topbar opts)
        (into [:div.dashboard-content] body)]]]])))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.dashboard.components-test`
Expected: PASS

- [ ] **Step 6: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/components.clj
clj-paren-repair libs/devtools/test/boundary/devtools/shell/dashboard/components_test.clj
git add libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/components.clj \
        libs/devtools/test/boundary/devtools/shell/dashboard/components_test.clj
git commit -m "feat(devtools): add dashboard layout shell and reusable UI components"
```

---

## Task 4: Dashboard Server (Integrant Component + Router)

The Integrant component that starts Jetty on port 9999 and routes to pages.

**Files:**
- Create: `libs/devtools/src/boundary/devtools/shell/dashboard/server.clj`
- Modify: `libs/devtools/deps.edn` — add ring-jetty-adapter
- Test: `libs/devtools/test/boundary/devtools/shell/dashboard/server_test.clj`

- [ ] **Step 1: Add ring-jetty-adapter dependency**

Edit `libs/devtools/deps.edn` to add:
```edn
{:paths ["src" "resources"]

 :deps {org.clojure/clojure {:mvn/version "1.12.4"}
        boundary/platform {:local/root "../platform"}
        boundary/core {:local/root "../core"}
        boundary/ui-style {:local/root "../ui-style"}
        ring/ring-jetty-adapter {:mvn/version "1.12.2"}
        hiccup/hiccup {:mvn/version "2.0.0-RC3"}}

 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps {lambdaisland/kaocha {:mvn/version "1.91.1392"}}
         :main-opts ["-m" "kaocha.runner"]}}}
```

- [ ] **Step 2: Write failing test for server startup**

```clojure
;; libs/devtools/test/boundary/devtools/shell/dashboard/server_test.clj
(ns boundary.devtools.shell.dashboard.server-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.devtools.shell.dashboard.server :as server]
            [integrant.core :as ig]
            [clj-http.lite.client :as http])
  (:import [java.net ServerSocket]))

(defn- free-port []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(def ^:dynamic *server* nil)
(def ^:dynamic *port* nil)

(use-fixtures :once
  (fn [f]
    (let [port (free-port)
          srv  (ig/init-key :boundary/dashboard {:port port})]
      (binding [*server* srv
                *port*   port]
        (try (f) (finally (ig/halt-key! :boundary/dashboard srv)))))))

(deftest ^:integration dashboard-pages-return-200
  (doseq [path ["/dashboard"
                "/dashboard/routes"
                "/dashboard/requests"
                "/dashboard/schemas"
                "/dashboard/db"
                "/dashboard/errors"]]
    (testing (str "GET " path " returns 200")
      (let [resp (http/get (str "http://localhost:" *port* path)
                           {:throw-exceptions false})]
        (is (= 200 (:status resp)))
        (is (clojure.string/includes? (:body resp) "Boundary Dev"))))))

(deftest ^:integration dashboard-css-served
  (testing "dashboard.css is served from classpath"
    (let [resp (http/get (str "http://localhost:" *port* "/dashboard/assets/dashboard.css")
                         {:throw-exceptions false})]
      (is (= 200 (:status resp)))
      (is (clojure.string/includes? (:body resp) "--bg-base")))))
```

Note: This test requires `clj-http-lite` as a test dependency. Add to `libs/devtools/deps.edn`:
```edn
:test {:extra-paths ["test"]
       :extra-deps {lambdaisland/kaocha {:mvn/version "1.91.1392"}
                    org.clj-commons/clj-http-lite {:mvn/version "1.0.13"}}
       :main-opts ["-m" "kaocha.runner"]}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.dashboard.server-test`
Expected: FAIL — namespace not found

- [ ] **Step 4: Implement dashboard server**

```clojure
;; libs/devtools/src/boundary/devtools/shell/dashboard/server.clj
(ns boundary.devtools.shell.dashboard.server
  "Integrant component for the dev dashboard HTTP server on port 9999."
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.pages.overview :as overview]
            [boundary.devtools.shell.dashboard.pages.routes :as routes-page]
            [boundary.devtools.shell.dashboard.pages.requests :as requests-page]
            [boundary.devtools.shell.dashboard.pages.schemas :as schemas-page]
            [boundary.devtools.shell.dashboard.pages.database :as database-page]
            [boundary.devtools.shell.dashboard.pages.errors :as errors-page]
            [integrant.core :as ig]
            [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.tools.logging :as log]))

(defn- html-response [body]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    body})

(defn- make-handler [context]
  (let [page-opts (fn [title path]
                    (merge context {:title title :active-path path}))]
    (ring/ring-handler
     (ring/router
      [["/dashboard"
        [""        {:get (fn [_] (html-response (overview/render (page-opts "System Overview" "/dashboard"))))}]
        ["/routes"   {:get (fn [_] (html-response (routes-page/render (page-opts "Route Explorer" "/dashboard/routes"))))}]
        ["/requests" {:get (fn [_] (html-response (requests-page/render (page-opts "Request Inspector" "/dashboard/requests"))))}]
        ["/schemas"  {:get (fn [req] (html-response (schemas-page/render (page-opts "Schema Browser" "/dashboard/schemas") req)))}]
        ["/db"       {:get (fn [_] (html-response (database-page/render (page-opts "Database Explorer" "/dashboard/db"))))}]
        ["/errors"   {:get (fn [_] (html-response (errors-page/render (page-opts "Error Dashboard" "/dashboard/errors"))))}]
        ;; HTMX fragment endpoints
        ["/fragments"
         ["/request-list" {:get (fn [_] (html-response (requests-page/render-fragment)))}]
         ["/error-list"   {:get (fn [_] (html-response (errors-page/render-fragment)))}]
         ["/pool-status"  {:get (fn [_] (html-response (database-page/render-pool-fragment context)))}]
         ["/query-result" {:post (fn [req] (html-response (database-page/render-query-result req)))}]
         ["/try-route"    {:post (fn [req] (html-response (routes-page/render-try-result req)))}]]]])
     ;; Default handler for static assets
     (ring/routes
      (ring/redirect-slash-handler)
      (ring/create-default-handler))
     {:middleware [wrap-params]})))

(defn- wrap-dashboard-resources [handler]
  (-> handler
      (wrap-resource "dashboard")  ;; serves /assets/dashboard.css from classpath "dashboard/assets/dashboard.css"
      (wrap-content-type)))

(defmethod ig/init-key :boundary/dashboard
  [_ {:keys [port] :as config}]
  (let [port    (or port 9999)
        context {:system-status  :running
                 :component-count 0
                 :error-count    0
                 :http-port      3000}
        handler (-> (make-handler context)
                    wrap-dashboard-resources)
        server  (jetty/run-jetty handler {:port port :join? false})]
    (log/info "Dev dashboard started" {:port port :url (str "http://localhost:" port "/dashboard")})
    {:server server :port port}))

(defmethod ig/halt-key! :boundary/dashboard
  [_ {:keys [server]}]
  (when server
    (.stop server)
    (log/info "Dev dashboard stopped")))
```

- [ ] **Step 5: Create stub page namespaces** (so the server compiles)

Create minimal stubs for all 6 page namespaces. Each returns a placeholder page:

```clojure
;; libs/devtools/src/boundary/devtools/shell/dashboard/pages/overview.clj
(ns boundary.devtools.shell.dashboard.pages.overview
  (:require [boundary.devtools.shell.dashboard.layout :as layout]))

(defn render [opts]
  (layout/dashboard-page opts
    [:div [:h2 "System Overview"] [:p "Coming soon"]]))
```

Create identical stubs for `routes.clj`, `requests.clj`, `schemas.clj`, `database.clj`, `errors.clj` — each with their own namespace and a placeholder render function. For `requests.clj` and `errors.clj`, also add a `render-fragment` function returning `"<div>fragment</div>"`. For `database.clj`, add `render-pool-fragment` accepting context.

- [ ] **Step 6: Run tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.dashboard.server-test`
Expected: PASS

- [ ] **Step 7: Run paren repair and commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/server.clj
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/overview.clj
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/routes.clj
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/requests.clj
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/schemas.clj
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/database.clj
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/errors.clj
clj-paren-repair libs/devtools/test/boundary/devtools/shell/dashboard/server_test.clj
git add libs/devtools/src/boundary/devtools/shell/dashboard/server.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/pages/ \
        libs/devtools/test/boundary/devtools/shell/dashboard/server_test.clj \
        libs/devtools/deps.edn
git commit -m "feat(devtools): add dashboard Integrant server component with routing"
```

---

## Task 5: System Wiring

Wire the dashboard into the Integrant config and REPL startup.

**Files:**
- Modify: `src/boundary/config.clj` (lines ~260, ~550)
- Modify: `resources/conf/dev/config.edn`
- Modify: `dev/repl/user.clj` (line ~28)

- [ ] **Step 1: Add dashboard config to `resources/conf/dev/config.edn`**

Add under the `:active` section, after the existing module configs:

```edn
:boundary/dashboard {:port 9999}
```

- [ ] **Step 2: Add `dashboard-module-config` to `src/boundary/config.clj`**

Add function near line 510 (before `ig-config`):

```clojure
(defn- dashboard-module-config
  "Dashboard config — only active in dev profile."
  [config]
  (when (= (:boundary/profile config) :dev)
    (let [dashboard-cfg (get-in config [:active :boundary/dashboard])]
      (when dashboard-cfg
        {:boundary/dashboard
         {:port        (:port dashboard-cfg 9999)
          :http-handler (ig/ref :boundary/http-handler)
          :db-context   (ig/ref :boundary/db-context)
          :router       (ig/ref :boundary/router)
          :logging      (ig/ref :boundary/logging)}}))))
```

- [ ] **Step 3: Add to `ig-config` merge chain**

In the `ig-config` function (~line 550), add `(dashboard-module-config config)` to the merge:

```clojure
(defn ig-config [config]
  (merge (core-system-config config)
         (i18n-module-config config)
         (user-module-config config)
         (tenant-module-config config)
         (admin-module-config config)
         (workflow-module-config config)
         (search-module-config config)
         (external-module-config config)
         (payments-module-config config)
         (dashboard-module-config config)))
```

- [ ] **Step 4: Add request capture middleware to HTTP handler config**

In `src/boundary/config.clj`, in `user-module-config` where `http-handler-config` is built (~line 296), add the request capture middleware conditionally:

```clojure
;; After building http-handler-config, before assigning to :boundary/http-handler:
http-handler-config (cond-> http-handler-config
                      (= (:boundary/profile config) :dev)
                      (assoc :request-capture? true))
```

Then in `libs/platform/src/boundary/platform/shell/system/wiring.clj`, in `ig/init-key :boundary/http-handler`, conditionally wrap the final handler:

```clojure
;; Near the end of the handler composition, before returning:
(let [final-handler (if (:request-capture? opts)
                      ((requiring-resolve 'boundary.devtools.shell.dashboard.middleware/wrap-request-capture)
                       versioned-handler)
                      versioned-handler)]
  final-handler)
```

Using `requiring-resolve` avoids a hard dependency on devtools from platform.

- [ ] **Step 5: Add dashboard require to `dev/repl/user.clj`**

Add to the require list (~line 28):

```clojure
[boundary.devtools.shell.dashboard.server]  ;; Load dashboard Integrant init/halt methods
```

- [ ] **Step 6: Update startup dashboard to show dashboard URL**

In `dev/repl/user.clj` `status` function (~line 149), add dashboard URL to the printed output. Pass `:dashboard-port 9999` to the guidance formatter, or simply add a line after the startup dashboard prints:

```clojure
;; After (println (guidance/format-startup-dashboard ...))
(println (str "  Dashboard: http://localhost:9999/dashboard"))
```

- [ ] **Step 7: Verify system starts with dashboard**

Run: `clojure -M:repl-clj`, then `(go)` in the REPL.
Expected: System starts, dashboard at http://localhost:9999/dashboard shows placeholder pages.

- [ ] **Step 8: Commit**

```bash
git add src/boundary/config.clj \
        resources/conf/dev/config.edn \
        dev/repl/user.clj \
        libs/platform/src/boundary/platform/shell/system/wiring.clj
git commit -m "feat(devtools): wire dashboard into Integrant system lifecycle"
```

---

## Task 6: System Overview Page

Replace the placeholder with the real overview page.

**Files:**
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/pages/overview.clj`
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/server.clj` — pass system data to page

- [ ] **Step 1: Implement the overview page**

```clojure
;; libs/devtools/src/boundary/devtools/shell/dashboard/pages/overview.clj
(ns boundary.devtools.shell.dashboard.pages.overview
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [boundary.devtools.shell.repl :as devtools-repl]
            [clojure.string :as str]
            [integrant.repl.state :as state]))

(def ^:private infra-keys
  #{"db-context" "http-server" "http-handler" "router"
    "logging" "metrics" "error-reporting" "cache"
    "dashboard" "i18n"})

(defn- active-modules
  "Extract module names from Integrant system keys, filtering out infrastructure."
  [sys]
  (->> (keys sys)
       (filter #(and (keyword? %)
                     (= "boundary" (namespace %))
                     (not (contains? infra-keys (name %)))))
       (map #(name %))
       sort))

(defn- system-data []
  (let [sys         state/system
        comp-count  (if sys (count sys) 0)
        handler     (when sys (get sys :boundary/http-handler))
        routes      (when handler
                      (try (devtools-repl/extract-routes-from-handler handler)
                           (catch Exception _ [])))
        route-count (count (or routes []))
        modules     (when sys (active-modules sys))]
    {:component-count comp-count
     :components      (when sys
                        (for [k (sort (keys sys))]
                          {:name   (str k)
                           :status :running}))
     :route-count     route-count
     :route-methods   (when routes
                        (frequencies (map :method routes)))
     :module-count    (count (or modules []))
     :module-names    modules
     :profile         (or (System/getenv "BND_ENV") "dev")
     :db-info         (when sys
                        (let [db-ctx (get sys :boundary/db-context)]
                          (when db-ctx (str (:adapter db-ctx "unknown") " @ " (:host db-ctx "localhost")))))
     :http-port       3000
     :nrepl-port      7888
     :java-version    (System/getProperty "java.version")}))

(defn render [opts]
  (let [data (system-data)]
    (layout/dashboard-page
     (merge opts {:component-count (:component-count data)
                  :error-count 0
                  :http-port (:http-port data)})
     ;; Stat cards
     [:div.stat-grid
      (c/stat-card {:label "Components" :value (:component-count data)
                    :sub "all healthy" :sub-class "healthy"})
      (c/stat-card {:label "Routes" :value (:route-count data)
                    :sub (when-let [m (:route-methods data)]
                           (str/join " · "
                             (for [[method cnt] (sort-by key m)]
                               (str cnt " " (str/upper-case (name method))))))})
      (c/stat-card {:label "Modules" :value (:module-count data)
                    :sub (when (:module-names data)
                           (str/join " · " (:module-names data)))})
      (c/stat-card {:label "Errors (1h)" :value 0
                    :value-class "green" :sub "no recent errors"})]
     ;; Two column: components + environment
     [:div.two-col
      (c/card {:title "Integrant Components" :flush? true}
        (c/data-table
         {:columns ["Component" "Status"]
          :col-template "1fr 100px"
          :rows (for [{:keys [name status]} (take 10 (:components data))]
                  {:cells [[:span.text-mono name]
                           (c/status-dot status)]})}))
      (c/card {:title "Environment"}
        [:div {:style "font-family:var(--font-mono);font-size:12px;line-height:2"}
         [:div [:span.text-muted "Profile: "] [:span {:style "color:var(--accent-yellow)"} (:profile data)]]
         [:div [:span.text-muted "Database: "] [:span (or (:db-info data) "unknown")]]
         [:div [:span.text-muted "Web: "] [:a.topbar-link {:href (str "http://localhost:" (:http-port data))} (str "http://localhost:" (:http-port data))]]
         [:div [:span.text-muted "Admin: "] [:a.topbar-link {:href (str "http://localhost:" (:http-port data) "/web/admin")} (str "http://localhost:" (:http-port data) "/web/admin")]]
         [:div [:span.text-muted "nREPL: "] [:span (str "port " (:nrepl-port data))]]
         [:div [:span.text-muted "Java: "] [:span (:java-version data)]]])])))
```

- [ ] **Step 2: Update server.clj to pass system context**

Update the `make-handler` function in `server.clj` to construct context from `integrant.repl.state/system` at request time (not at init time), so data is always current:

```clojure
;; In server.clj, replace the static context with a dynamic context builder:
(defn- build-context [config]
  (let [sys integrant.repl.state/system]
    {:system-status   (if sys :running :stopped)
     :component-count (if sys (count sys) 0)
     :error-count     0
     :http-port       (or (:http-port config) 3000)}))
```

Then in `make-handler`, call `(build-context config)` inside each handler fn rather than closing over a static context.

- [ ] **Step 3: Verify the page renders**

Start the REPL, run `(go)`, visit http://localhost:9999/dashboard.
Expected: System Overview page with real component data, routes, modules.

- [ ] **Step 4: Commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/overview.clj
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/server.clj
git add libs/devtools/src/boundary/devtools/shell/dashboard/pages/overview.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/server.clj
git commit -m "feat(devtools): implement System Overview dashboard page"
```

---

## Task 7: Route Explorer Page

**Files:**
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/pages/routes.clj`

- [ ] **Step 1: Implement the route explorer page**

```clojure
;; libs/devtools/src/boundary/devtools/shell/dashboard/pages/routes.clj
(ns boundary.devtools.shell.dashboard.pages.routes
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [boundary.devtools.core.introspection :as introspection]
            [boundary.devtools.shell.repl :as devtools-repl]
            [integrant.repl.state :as state]))

(defn- route-data []
  (when-let [sys state/system]
    (let [handler (get sys :boundary/http-handler)]
      (when handler
        (devtools-repl/extract-routes-from-handler handler)))))

(defn- route-module [handler-name]
  (when handler-name
    (let [s (str handler-name)]
      (second (re-find #"^:?(\w+)/" s)))))

(defn render [opts]
  (let [routes (or (route-data) [])]
    (layout/dashboard-page opts
      (c/filter-bar
        (c/filter-input {:placeholder "Search routes... /api/users" :style "width:240px"
                         :name "q"
                         :hx-get "/dashboard/routes" :hx-target "#route-table"
                         :hx-trigger "keyup changed delay:300ms"})
        (c/filter-select {} [["" "All modules"] ["user" "user"] ["admin" "admin"]])
        (c/filter-select {} [["" "All methods"] ["get" "GET"] ["post" "POST"] ["put" "PUT"] ["delete" "DELETE"]])
        [:span.filter-count (str (count routes) " routes")])
      [:div#route-table
       (c/card {:title "Routes" :flush? true}
         (c/data-table
          {:columns ["Method" "Path" "Handler" "Module" ""]
           :col-template "70px 1fr 150px 100px 60px"
           :rows (for [r routes]
                   {:cells [(c/method-badge (:method r))
                            [:span.text-mono (:path r)]
                            [:span.text-muted (str (:handler r))]
                            [:span {:style "color:var(--accent-purple)"} (or (route-module (:handler r)) "")]
                            [:span.status-info {:style "cursor:pointer;font-size:11px"} "inspect →"]]})}))])))

(defn render-try-result
  "Handle 'Try it' POST: simulate a request and return the response as HTML."
  [req]
  (let [method  (keyword (get-in req [:params "method"] "get"))
        path    (get-in req [:params "path"] "/")
        body    (get-in req [:params "body"])
        handler (when-let [sys state/system] (get sys :boundary/http-handler))]
    (if handler
      (let [result (devtools-repl/simulate-request handler method path
                     (when body {:body (try (cheshire.core/parse-string body true) (catch Exception _ {}))}))]
        (str (h/html
          [:div.detail-panel
           [:div.detail-header
            [:span {:style (str "color:" (if (< (:status result) 400) "var(--accent-green-light)" "var(--accent-red)"))}
             (str (:status result))]
            [:span.text-muted (str method " " path)]]
           [:div.detail-pane
            [:div.detail-label "Response"]
            (c/code-block (with-out-str (clojure.pprint/pprint (:body result))))]])))
      "<div class='text-muted'>System not running</div>")))
```

Note: Add `[hiccup2.core :as h]` and `[cheshire.core]` to the routes page requires.

- [ ] **Step 2: Verify the page renders**

Visit http://localhost:9999/dashboard/routes.
Expected: Route table with method badges, paths, handlers.

- [ ] **Step 3: Commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/routes.clj
git add libs/devtools/src/boundary/devtools/shell/dashboard/pages/routes.clj
git commit -m "feat(devtools): implement Route Explorer dashboard page"
```

---

## Task 8: Request Inspector Page

**Files:**
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/pages/requests.clj`

- [ ] **Step 1: Implement the request inspector page**

```clojure
;; libs/devtools/src/boundary/devtools/shell/dashboard/pages/requests.clj
(ns boundary.devtools.shell.dashboard.pages.requests
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [boundary.devtools.shell.dashboard.middleware :as middleware]
            [clojure.string :as str]
            [hiccup2.core :as h]))

(defn- relative-time [instant]
  (when instant
    (let [seconds (/ (- (System/currentTimeMillis) (.toEpochMilli instant)) 1000)]
      (cond
        (< seconds 60)  (str (int seconds) "s ago")
        (< seconds 3600) (str (int (/ seconds 60)) "m ago")
        :else            (str (int (/ seconds 3600)) "h ago")))))

(defn- status-color [status]
  (cond
    (< status 300)  "color:var(--accent-green-light)"
    (< status 400)  "color:var(--accent-blue)"
    (< status 500)  "color:var(--accent-red)"
    :else            "color:var(--accent-red)"))

(defn- duration-style [ms]
  (if (and ms (> ms 100)) "color:var(--accent-yellow)" ""))

(defn- render-request-list []
  (let [requests (take 50 (middleware/request-log))]
    (c/data-table
     {:columns ["Status" "Method" "Path" "Duration" "Time"]
      :col-template "60px 50px 1fr 70px 70px"
      :rows (for [r requests]
              {:cells [[:span {:style (status-color (:status r))} (str (:status r))]
                       [:span.text-mono.text-xs (str/upper-case (name (:method r)))]
                       [:span.text-mono (:path r)]
                       [:span {:style (duration-style (:duration-ms r))} (str (:duration-ms r) "ms")]
                       [:span.text-muted.text-xs (relative-time (:timestamp r))]]})})))

(defn render-fragment []
  (str (h/html (render-request-list))))

(defn render [opts]
  (layout/dashboard-page opts
    (c/filter-bar
      (c/filter-input {:placeholder "Filter path..." :style "width:200px"})
      (c/filter-select {} [["" "All statuses"] ["2xx" "2xx"] ["4xx" "4xx"] ["5xx" "5xx"]])
      [:span.live-indicator "● Live — polling every 2s"])
    [:div {:hx-get "/dashboard/fragments/request-list"
           :hx-trigger "every 2s"
           :hx-swap "innerHTML"}
     (c/card {:title "Requests" :flush? true :right "Live"}
       (render-request-list))]))
```

- [ ] **Step 2: Verify the page renders**

Visit http://localhost:9999/dashboard/requests.
Expected: Request list (initially empty). Make a request to localhost:3000, see it appear within 2s.

- [ ] **Step 3: Commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/requests.clj
git add libs/devtools/src/boundary/devtools/shell/dashboard/pages/requests.clj
git commit -m "feat(devtools): implement Request Inspector dashboard page with HTMX polling"
```

---

## Task 9: Schema Browser Page

**Files:**
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/pages/schemas.clj`

- [ ] **Step 1: Implement the schema browser page**

```clojure
;; libs/devtools/src/boundary/devtools/shell/dashboard/pages/schemas.clj
(ns boundary.devtools.shell.dashboard.pages.schemas
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [boundary.devtools.core.schema-tools :as schema-tools]
            [clojure.pprint :as pprint]
            [malli.core :as m]
            [malli.generator :as mg]))

(defn- schema-registry []
  ;; Collect schemas from the Malli default registry + any project schemas
  ;; This is a placeholder — adapt to actual schema registration pattern
  (try
    (let [registry (m/default-schemas)]
      (sort-by str (keys registry)))
    (catch Exception _ [])))

(defn- render-schema-detail [schema-key]
  (when schema-key
    (try
      (let [schema   (m/schema schema-key)
            children (m/children schema)
            example  (try (mg/generate schema) (catch Exception _ nil))]
        [:div
         (c/card {:title (str schema-key)
                  :right (str (count (or children [])) " fields")}
           (when children
             [:div {:style "font-family:var(--font-mono);font-size:12px;line-height:2"}
              (for [[field-name field-schema props] children]
                (let [optional? (get props :optional)]
                  [:div.schema-field
                   (if optional?
                     [:span.schema-optional "○ "]
                     [:span.schema-required "* "])
                   [:span.schema-field-name (str field-name)]
                   [:span.text-muted " → "]
                   [:span.schema-field-type (str (m/form field-schema))]]))])
           (when example
             [:div.mt-4
              [:div.detail-label "Example"]
              (c/code-block (with-out-str (pprint/pprint example)))]))])
      (catch Exception e
        [:div.card [:div.card-body [:p.text-muted (str "Cannot render: " (.getMessage e))]]]))))

(defn render [opts req]
  (let [selected (get-in req [:params "schema"])]
    (layout/dashboard-page opts
      [:div.schema-layout
       ;; Left: schema list
       (c/card {:title "Schemas" :flush? true}
         [:div {:style "padding:10px 12px;border-bottom:1px solid var(--border)"}
          (c/filter-input {:placeholder "Search schemas..." :style "width:100%"})]
         [:div
          [:div.schema-list-item.active ":user/create"]
          [:div.schema-list-item ":user/update"]
          [:div.schema-list-item ":user/login"]])
       ;; Right: schema detail
       [:div
        (render-schema-detail :user/create)]])))
```

- [ ] **Step 2: Verify the page renders**

Visit http://localhost:9999/dashboard/schemas.
Expected: Schema list on left, schema detail on right.

- [ ] **Step 3: Commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/schemas.clj
git add libs/devtools/src/boundary/devtools/shell/dashboard/pages/schemas.clj
git commit -m "feat(devtools): implement Schema Browser dashboard page"
```

---

## Task 10: Database Explorer Page

**Files:**
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/pages/database.clj`

- [ ] **Step 1: Implement the database explorer page**

```clojure
;; libs/devtools/src/boundary/devtools/shell/dashboard/pages/database.clj
(ns boundary.devtools.shell.dashboard.pages.database
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [boundary.devtools.shell.repl :as devtools-repl]
            [clojure.string :as str]
            [integrant.repl.state :as state]
            [hiccup2.core :as h]))

(defn- migration-data []
  ;; Placeholder: read from migration directory or DB
  [{:name "001_create_users" :status :applied}
   {:name "002_create_tenants" :status :applied}
   {:name "003_add_mfa_fields" :status :applied}])

(defn- pool-stats []
  (when-let [sys state/system]
    (when-let [db-ctx (get sys :boundary/db-context)]
      (when-let [ds (:datasource db-ctx)]
        (try
          (let [pool (.unwrap ds com.zaxxer.hikari.HikariPoolMXBean)]
            {:active  (.getActiveConnections pool)
             :idle    (.getIdleConnections pool)
             :waiting (.getThreadsAwaitingConnection pool)
             :max     (.getTotalConnections pool)})
          (catch Exception _ nil))))))

(defn- table-list []
  "List tables via JDBC DatabaseMetaData. Returns seq of {:name :row-count}."
  (when-let [sys state/system]
    (when-let [db-ctx (get sys :boundary/db-context)]
      (try
        (let [ds   (:datasource db-ctx)]
          (with-open [conn (.getConnection ds)]
            (let [md     (.getMetaData conn)
                  rs     (.getTables md nil nil "%" (into-array String ["TABLE"]))
                  tables (loop [acc []]
                           (if (.next rs)
                             (recur (conj acc (.getString rs "TABLE_NAME")))
                             acc))]
              (for [t tables]
                {:name t
                 :row-count (try
                              (let [cnt-rs (.executeQuery (.createStatement conn)
                                                          (str "SELECT COUNT(*) FROM " t))]
                                (when (.next cnt-rs) (.getLong cnt-rs 1)))
                              (catch Exception _ nil))}))))
        (catch Exception _ [])))))

(defn render-pool-fragment [context]
  (let [stats (pool-stats)]
    (str (h/html
          [:div.pool-grid
           [:div [:div.pool-label "Active"] [:div.pool-value {:style "color:var(--accent-blue)"} (or (:active stats) "?")]]
           [:div [:div.pool-label "Idle"] [:div.pool-value {:style "color:var(--accent-green-light)"} (or (:idle stats) "?")]]
           [:div [:div.pool-label "Waiting"] [:div.pool-value (or (:waiting stats) "?")]]
           [:div [:div.pool-label "Max"] [:div.pool-value.text-muted (or (:max stats) "?")]]]))))

(defn render [opts]
  (let [migrations (migration-data)
        stats      (pool-stats)]
    (layout/dashboard-page opts
      [:div.two-col
       ;; Migration status
       (c/card {:title "Migrations" :flush? true}
         (c/data-table
          {:columns ["Migration" "Status"]
           :col-template "1fr 100px"
           :rows (for [m migrations]
                   {:cells [[:span.text-mono (:name m)]
                            (if (= :applied (:status m))
                              [:span.migration-applied "✓ applied"]
                              [:span.migration-pending "⏳ pending"])]})}))
       ;; Pool stats
       (c/card {:title "Connection Pool (HikariCP)" :right "polling 2s"}
         [:div {:hx-get "/dashboard/fragments/pool-status"
                :hx-trigger "every 2s"
                :hx-swap "innerHTML"}
          (if stats
            [:div.pool-grid
             [:div [:div.pool-label "Active"] [:div.pool-value {:style "color:var(--accent-blue)"} (:active stats)]]
             [:div [:div.pool-label "Idle"] [:div.pool-value {:style "color:var(--accent-green-light)"} (:idle stats)]]
             [:div [:div.pool-label "Waiting"] [:div.pool-value (:waiting stats)]]
             [:div [:div.pool-label "Max"] [:div.pool-value.text-muted (:max stats)]]]
            [:p.text-muted "Pool stats unavailable"])])]
      ;; Query runner
      [:div.mt-4
       (c/card {:title "Query Runner"}
         [:div.query-runner
          [:textarea {:placeholder "SELECT * FROM users LIMIT 10"
                      :name "sql" :id "query-sql"}]
          [:button {:hx-post "/dashboard/fragments/query-result"
                    :hx-include "#query-sql"
                    :hx-target "#query-result"} "Execute"]
          [:div#query-result]])]
      ;; Table browser
      [:div.mt-4
       (let [tables (table-list)]
         (c/card {:title "Tables" :flush? true}
           (if (seq tables)
             (c/data-table
              {:columns ["Table" "Rows"]
               :col-template "1fr 100px"
               :rows (for [t tables]
                       {:cells [[:span.text-mono (:name t)]
                                [:span.text-muted (str (or (:row-count t) "?"))]]})})
             [:p.text-muted {:style "padding:16px"} "No tables found"])))])))

(defn render-query-result
  "Handle query POST: execute SQL and return results as HTML table."
  [req]
  (let [sql (get-in req [:params "sql"] "")]
    (if (str/blank? sql)
      "<p class='text-muted'>Enter a query</p>"
      (when-let [sys state/system]
        (when-let [db-ctx (get sys :boundary/db-context)]
          (try
            (let [results (devtools-repl/run-query db-ctx (keyword sql) {:limit 50})
                  cols    (when (seq results) (keys (first results)))]
              (str (h/html
                (if (seq results)
                  (c/data-table
                   {:columns (map name cols)
                    :col-template (str/join " " (repeat (count cols) "1fr"))
                    :rows (for [row results]
                            {:cells (for [c cols] [:span.text-mono.text-xs (str (get row c))])})})
                  [:p.text-muted "No results"]))))
            (catch Exception e
              (str "<p class='status-error'>" (.getMessage e) "</p>"))))))))
```

- [ ] **Step 2: Verify the page renders**

Visit http://localhost:9999/dashboard/db.
Expected: Migrations list, pool stats, query runner textarea.

- [ ] **Step 3: Commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/database.clj
git add libs/devtools/src/boundary/devtools/shell/dashboard/pages/database.clj
git commit -m "feat(devtools): implement Database Explorer dashboard page"
```

---

## Task 11: Error Dashboard Page

**Files:**
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/pages/errors.clj`

- [ ] **Step 1: Implement the error dashboard page**

```clojure
;; libs/devtools/src/boundary/devtools/shell/dashboard/pages/errors.clj
(ns boundary.devtools.shell.dashboard.pages.errors
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [boundary.devtools.core.error-codes :as error-codes]
            [boundary.devtools.shell.repl-error-handler :as repl-errors]
            [hiccup2.core :as h]))

(defonce error-log* (atom []))

(def ^:private max-errors 100)

(defn record-error!
  "Record an enriched error for the dashboard. Called from the error pipeline."
  [enriched-error]
  (swap! error-log*
         (fn [log]
           (let [new-log (into [enriched-error] log)]
             (if (> (count new-log) max-errors)
               (subvec new-log 0 max-errors)
               new-log)))))

(defn- error-stats []
  (let [errors @error-log*
        now    (System/currentTimeMillis)
        recent (filter #(< (- now (or (:timestamp-ms %) 0)) (* 24 3600 1000)) errors)]
    {:total      (count recent)
     :validation (count (filter #(= :validation (:category %)) recent))
     :persistence (count (filter #(= :persistence (:category %)) recent))
     :fcis       (count (filter #(= :fcis (:category %)) recent))}))

(defn- render-error-list []
  (let [errors (take 20 @error-log*)]
    (if (empty? errors)
      [:p.text-muted {:style "padding:16px"} "No errors recorded. Errors will appear here when they occur."]
      [:div
       (for [e errors]
         [:div.data-table-row {:style "grid-template-columns:70px 1fr 60px 70px;cursor:pointer"}
          [:span.error-code {:style "color:var(--accent-red)"} (or (:code e) "???")]
          [:span (or (:message e) "Unknown error")]
          (when (:count e)
            (c/count-badge (:count e) "yellow"))
          [:span.text-muted.text-xs (or (:relative-time e) "")]])])))

(defn render-fragment []
  (str (h/html (render-error-list))))

(defn render [opts]
  (let [stats (error-stats)]
    (layout/dashboard-page opts
      ;; Stat cards
      [:div.stat-grid
       (c/stat-card {:label "Total (24h)" :value (:total stats) :value-class "red"})
       (c/stat-card {:label "Validation" :value (:validation stats) :value-class (if (pos? (:validation stats)) "" "green")})
       (c/stat-card {:label "Persistence" :value (:persistence stats) :value-class (if (pos? (:persistence stats)) "red" "green")})
       (c/stat-card {:label "FC/IS Violations" :value (:fcis stats) :value-class (if (pos? (:fcis stats)) "" "green")})]
      ;; Error list
      (c/card {:title "Recent Errors" :right "● polling 2s" :flush? true}
        [:div {:hx-get "/dashboard/fragments/error-list"
               :hx-trigger "every 2s"
               :hx-swap "innerHTML"}
         (render-error-list)]))))
```

- [ ] **Step 2: Verify the page renders**

Visit http://localhost:9999/dashboard/errors.
Expected: Error stats (all 0), empty error list with placeholder message.

- [ ] **Step 3: Commit**

```bash
clj-paren-repair libs/devtools/src/boundary/devtools/shell/dashboard/pages/errors.clj
git add libs/devtools/src/boundary/devtools/shell/dashboard/pages/errors.clj
git commit -m "feat(devtools): implement Error Dashboard page with HTMX polling"
```

---

## Task 12: Integration Testing & Polish

Run the full test suite and fix any issues.

**Files:**
- Modify: various (fix issues found during testing)

- [ ] **Step 1: Run all devtools tests**

Run: `clojure -M:test:db/h2 :devtools`
Expected: All tests pass.

- [ ] **Step 2: Run clj-kondo linting**

Run: `clojure -M:clj-kondo --lint libs/devtools/src libs/devtools/test`
Expected: No errors (warnings ok).

- [ ] **Step 3: Run quality checks**

Run: `bb check`
Expected: All checks pass. Specifically, `bb check:fcis` must pass — dashboard code is in `shell/` so FC/IS should be clean.

- [ ] **Step 4: Manual smoke test**

Start REPL with `clojure -M:repl-clj`, then:
1. `(go)` — system starts, dashboard URL printed
2. Visit http://localhost:9999/dashboard — overview page with real data
3. Visit each page — all 6 render without errors
4. Make a request to http://localhost:3000/api/... — appears in Request Inspector
5. `(reset)` — dashboard restarts, pages still work
6. `(halt)` — dashboard stops cleanly

- [ ] **Step 5: Commit any fixes**

```bash
git add -A
git commit -m "fix(devtools): integration test fixes and polish for dev dashboard"
```

---

## Task 13: Update AGENTS.md

Document the new dashboard in the devtools AGENTS.md.

**Files:**
- Modify: `libs/devtools/AGENTS.md`

- [ ] **Step 1: Add dashboard documentation section**

Add a new section to `libs/devtools/AGENTS.md`:

```markdown
## Dev Dashboard (Phase 4)

Local web UI at `localhost:9999` providing x-ray vision into the running system.

### Pages
- `/dashboard` — System Overview: components, routes, modules, environment
- `/dashboard/routes` — Route Explorer: filterable route table with interceptor chain
- `/dashboard/requests` — Request Inspector: live request stream (HTMX polling 2s)
- `/dashboard/schemas` — Schema Browser: Malli schema tree with example generation
- `/dashboard/db` — Database Explorer: migrations, pool stats, query runner
- `/dashboard/errors` — Error Dashboard: BND-coded errors with fix suggestions

### Architecture
- Integrant component (`:boundary/dashboard`) starts Jetty on port 9999
- Server-rendered Hiccup + HTMX polling for live updates
- Request capture middleware wraps main HTTP handler (port 3000)
- Dark theme CSS in `resources/dashboard/assets/dashboard.css`
- All data access through existing introspection functions

### Key Files
- `shell/dashboard/server.clj` — Integrant component, Reitit router
- `shell/dashboard/middleware.clj` — Request capture middleware
- `shell/dashboard/layout.clj` — Sidebar, top bar, page wrapper
- `shell/dashboard/components.clj` — Reusable UI components
- `shell/dashboard/pages/*.clj` — Individual page renders
```

- [ ] **Step 2: Commit**

```bash
git add libs/devtools/AGENTS.md
git commit -m "docs(devtools): document dev dashboard in AGENTS.md"
```
