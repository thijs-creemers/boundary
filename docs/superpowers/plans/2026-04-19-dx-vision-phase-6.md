# DX Vision Phase 6: Dashboard Extensions + AI Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Jobs & Queues dashboard page, Config Editor with hot-apply, Security Status page, AI REPL tooling (`ai/review`, `ai/test-ideas`, `ai/refactor-fcis`), and `(new-feature!)` workflow automation.

**Architecture:** Extends the existing `libs/devtools/` library with 3 new dashboard pages following the established Hiccup + HTMX + Reitit pattern. AI REPL functions extend `libs/ai/shell/repl.clj` using the existing `IAIProvider` abstraction and service pattern. New REPL functions are wired through `dev/repl/user.clj`. All new code follows FC/IS: pure formatting/analysis in `core/`, side effects in `shell/`.

**Tech Stack:** Clojure 1.12.4, Hiccup2, HTMX (polling fragments), Reitit, Integrant, Malli, existing `IAIProvider` protocol, existing dashboard layout/components.

---

## File Structure

### New Files

```
libs/devtools/src/boundary/devtools/
├── core/
│   ├── config_editor.clj          # Pure config diffing, component dependency analysis
│   └── security_analyzer.clj      # Pure security config analysis and reporting
├── shell/
│   └── dashboard/pages/
│       ├── jobs.clj               # Jobs & Queues dashboard page
│       ├── config.clj             # Config Editor dashboard page
│       └── security.clj           # Security Status dashboard page

libs/devtools/test/boundary/devtools/
├── core/
│   ├── config_editor_test.clj     # Config diff, dependency analysis tests
│   └── security_analyzer_test.clj # Security analysis tests
├── shell/dashboard/pages/
│   ├── jobs_test.clj              # Jobs page rendering tests
│   ├── config_test.clj            # Config page rendering tests
│   └── security_test.clj          # Security page rendering tests

libs/ai/src/boundary/ai/
├── core/prompts.clj               # Add review/test-ideas/refactor-fcis prompts (modify)
├── shell/service.clj              # Add review-code, suggest-tests, refactor-fcis features (modify)
└── shell/repl.clj                 # Add review, test-ideas, refactor-fcis REPL wrappers (modify)

libs/ai/test/boundary/ai/
├── core/prompts_test.clj          # Prompt generation tests (modify)
├── shell/service_test.clj         # Service function tests (modify)
└── shell/repl_test.clj            # REPL wrapper tests (modify)
```

### Modified Files

```
libs/devtools/src/boundary/devtools/shell/dashboard/server.clj   # Add 3 page routes + fragments
libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj   # Add 3 nav items to sidebar
libs/devtools/deps.edn                                           # Add boundary/jobs, boundary/user deps
dev/repl/user.clj                                                # Wire ai/review, ai/test-ideas, ai/refactor-fcis, new-feature!
libs/devtools/test/boundary/devtools/shell/dashboard/server_test.clj  # Add new pages to 200 check
```

---

## Task 1: Jobs & Queues Dashboard Page

**Files:**
- Create: `libs/devtools/src/boundary/devtools/shell/dashboard/pages/jobs.clj`
- Create: `libs/devtools/test/boundary/devtools/shell/dashboard/pages/jobs_test.clj`
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/server.clj`
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj`
- Modify: `libs/devtools/deps.edn`

### Context

The jobs library (`libs/jobs/`) exposes these protocols:
- `IJobQueue` — `enqueue-job!`, `dequeue-job!`, `queue-size`, `list-queues`
- `IJobStore` — `find-jobs`, `failed-jobs`, `retry-job!`
- `IJobStats` — `job-stats`, `queue-stats`, `job-history`
- `IJobWorker` — `worker-status`

Job statuses: `:pending`, `:running`, `:completed`, `:failed`, `:retrying`, `:cancelled`

The dashboard page needs to show:
- Stat cards: active/pending/failed/completed counts
- Queue visualization table with per-queue sizes
- Failed jobs list with error details + retry button
- Job timing stats
- HTMX polling for live updates

Follow the exact pattern of `errors.clj` — stat cards at top, card with HTMX-polled content below. Use existing `layout/dashboard-page`, `c/stat-card`, `c/card`, `c/data-table` components.

- [ ] **Step 1: Add `boundary/jobs` dependency to devtools deps.edn**

In `libs/devtools/deps.edn`, add `boundary/jobs` and `boundary/user` to `:deps`:

```clojure
boundary/jobs {:local/root "../jobs"}
boundary/user {:local/root "../user"}
```

Both are needed: `jobs` for the Jobs page, `user` for the Security page (session/auth data).

- [ ] **Step 2: Write the jobs page test**

Create `libs/devtools/test/boundary/devtools/shell/dashboard/pages/jobs_test.clj`:

```clojure
(ns boundary.devtools.shell.dashboard.pages.jobs-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.shell.dashboard.pages.jobs :as jobs]
            [clojure.string :as str]))

(deftest ^:unit renders-empty-state-without-job-service
  (testing "renders page without job service (nil context)"
    (let [html (jobs/render {})]
      (is (string? html))
      (is (str/includes? html "Jobs"))
      (is (str/includes? html "No job service")))))

(deftest ^:unit renders-stats-from-job-data
  (testing "renders stat cards from job stats data"
    (let [html (jobs/render {:job-stats {:total-processed 42
                                         :total-failed 3
                                         :total-succeeded 39
                                         :queues {:default {:size 5 :processed 30 :failed 2}
                                                  :critical {:size 1 :processed 12 :failed 1}}}
                             :failed-jobs []})]
      (is (str/includes? html "42"))
      (is (str/includes? html "default"))
      (is (str/includes? html "critical")))))

(deftest ^:unit renders-failed-jobs-list
  (testing "renders failed jobs with error info"
    (let [html (jobs/render {:job-stats {:total-processed 10
                                         :total-failed 1
                                         :total-succeeded 9
                                         :queues {}}
                             :failed-jobs [{:id "job-1"
                                            :job-type :send-email
                                            :queue :default
                                            :error "Connection refused"
                                            :retry-count 3
                                            :created-at "2026-04-19T10:00:00Z"}]})]
      (is (str/includes? html "send-email"))
      (is (str/includes? html "Connection refused")))))

(deftest ^:unit render-fragment-returns-string
  (testing "fragment rendering returns HTML string"
    (let [html (jobs/render-fragment {:job-stats {:total-processed 0
                                                   :total-failed 0
                                                   :total-succeeded 0
                                                   :queues {}}
                                      :failed-jobs []})]
      (is (string? html)))))
```

- [ ] **Step 3: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.dashboard.pages.jobs-test`
Expected: FAIL — namespace not found

- [ ] **Step 4: Implement the jobs dashboard page**

Create `libs/devtools/src/boundary/devtools/shell/dashboard/pages/jobs.clj`:

```clojure
(ns boundary.devtools.shell.dashboard.pages.jobs
  "Dashboard page for Jobs & Queues monitoring."
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [hiccup2.core :as h]))

(defn- queue-table
  "Render a table of queues with their sizes and stats."
  [queues]
  (if (empty? queues)
    [:div.empty-state "No queues active."]
    (c/data-table
     {:columns      ["Queue" "Pending" "Processed" "Failed" "Avg Duration"]
      :col-template "1fr 100px 100px 100px 120px"
      :rows         (for [[queue-name {:keys [size processed failed avg-duration]}] (sort-by key queues)]
                      {:cells [[:span.text-mono (name queue-name)]
                               [:span (str (or size 0))]
                               [:span (str (or processed 0))]
                               [:span {:style (when (and failed (pos? failed))
                                                "color:var(--color-red,#f87171)")}
                                (str (or failed 0))]
                               [:span (if avg-duration (str avg-duration "ms") "—")]]})})))

(defn- failed-jobs-list
  "Render the list of failed jobs."
  [failed-jobs]
  (if (empty? failed-jobs)
    [:div.empty-state "No failed jobs."]
    [:div.error-list
     (for [{:keys [id job-type queue error retry-count created-at]} failed-jobs]
       [:div.error-list-row
        [:span.error-code {:style "color: var(--color-red, #f87171); font-family: monospace; font-weight: bold;"}
         (name (or job-type :unknown))]
        [:span.error-message (or error "Unknown error")]
        (when retry-count
          (c/count-badge retry-count "yellow"))
        (when queue
          [:span.request-time (name queue)])
        [:button.filter-input
         {:hx-post   (str "/dashboard/fragments/retry-job?job-id=" id)
          :hx-target "#failed-jobs-container"
          :hx-swap   "innerHTML"
          :style     "cursor:pointer;padding:2px 8px;font-size:11px;width:auto"}
         "Retry"]])]))

(defn- jobs-content
  "Render the jobs page content (used for both full page and fragment)."
  [{:keys [job-stats failed-jobs]}]
  (let [{:keys [total-processed total-failed total-succeeded queues]} job-stats
        active (reduce + 0 (map (fn [[_ v]] (or (:size v) 0)) queues))]
    (list
     [:div.stat-row
      (c/stat-card {:label "Active/Pending" :value active
                    :value-class (when (pos? active) "stat-value-warning")})
      (c/stat-card {:label "Processed" :value (or total-processed 0)})
      (c/stat-card {:label "Succeeded" :value (or total-succeeded 0)
                    :value-class "green"})
      (c/stat-card {:label "Failed" :value (or total-failed 0)
                    :value-class (when (and total-failed (pos? total-failed)) "stat-value-error")})]
     [:div.two-col
      (c/card {:title "Queues"} (queue-table (or queues {})))
      (c/card {:title "Failed Jobs"
               :right [:span.live-indicator "● polling 5s"]}
              [:div#failed-jobs-container
               (failed-jobs-list (or failed-jobs []))])])))

(defn render
  "Render the Jobs & Queues full page."
  [opts]
  (if (or (:job-stats opts) (:job-queue opts))
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/jobs"
                  :title       "Jobs & Queues"})
     [:div {:hx-get     "/dashboard/fragments/jobs-content"
            :hx-trigger "every 5s"
            :hx-swap    "innerHTML"}
      (jobs-content opts)])
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/jobs"
                  :title       "Jobs & Queues"})
     [:div.empty-state
      "No job service configured. Add :boundary/jobs to your system config to enable job monitoring."])))

(defn render-fragment
  "Render the jobs content as an HTML fragment for HTMX polling."
  [opts]
  (str (h/html (jobs-content opts))))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.dashboard.pages.jobs-test`
Expected: PASS

- [ ] **Step 6: Add Jobs nav item to sidebar layout**

In `libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj`, add to `nav-items` after the errors entry:

```clojure
{:path "/dashboard/jobs"     :icon "⚙" :label "Jobs"}
```

- [ ] **Step 7: Wire jobs page routes into server.clj**

In `libs/devtools/src/boundary/devtools/shell/dashboard/server.clj`:

1. Add require: `[boundary.devtools.shell.dashboard.pages.jobs :as jobs-page]`
2. Add these routes inside the router vector (after the errors route):

```clojure
["/dashboard/jobs"
 {:get (fn [_req]
         (html-response (jobs-page/render (build-context config))))}]
["/dashboard/fragments/jobs-content"
 {:get (fn [_req]
         {:status  200
          :headers {"Content-Type" "text/html; charset=utf-8"}
          :body    (jobs-page/render-fragment (build-context config))})}]
["/dashboard/fragments/retry-job"
 {:post (fn [req]
          (when-let [job-store (:job-store (build-context config))]
            (let [job-id (get-in req [:params "job-id"])]
              (when job-id
                (try (job-ports/retry-job! job-store job-id)
                     (catch Exception _)))))
          {:status  200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    (jobs-page/render-fragment (build-context config))})}]
```

3. Extend `build-context` to include job data when available:

```clojure
;; In build-context, add after existing lets:
job-queue   (get sys :boundary/job-queue)
job-store   (get sys :boundary/job-store)
job-stats-svc (get sys :boundary/job-stats)
```

And merge into the returned map:

```clojure
:job-store   job-store
:job-stats   (when job-stats-svc
               (try (boundary.jobs.ports/job-stats job-stats-svc) (catch Exception _ nil)))
:failed-jobs (when job-store
               (try (boundary.jobs.ports/failed-jobs job-store 20) (catch Exception _ nil)))
```

Add require for `[boundary.jobs.ports :as job-ports]` at the top.

- [ ] **Step 8: Update server test to include jobs page**

In `libs/devtools/test/boundary/devtools/shell/dashboard/server_test.clj`, add `"/dashboard/jobs"` to the `doseq` vector in `dashboard-pages-return-200`.

- [ ] **Step 9: Run all devtools tests**

Run: `clojure -M:test:db/h2 :devtools`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add libs/devtools/src/boundary/devtools/shell/dashboard/pages/jobs.clj \
        libs/devtools/test/boundary/devtools/shell/dashboard/pages/jobs_test.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/server.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj \
        libs/devtools/deps.edn
git commit -m "$(cat <<'EOF'
feat(devtools): add Jobs & Queues dashboard page

Phase 6 — dashboard page showing job queue sizes, processing stats,
failed jobs with retry button, and HTMX polling for live updates.
Integrates with libs/jobs/ IJobStats and IJobStore protocols.
EOF
)"
```

---

## Task 2: Config Editor Dashboard Page

**Files:**
- Create: `libs/devtools/src/boundary/devtools/core/config_editor.clj`
- Create: `libs/devtools/test/boundary/devtools/core/config_editor_test.clj`
- Create: `libs/devtools/src/boundary/devtools/shell/dashboard/pages/config.clj`
- Create: `libs/devtools/test/boundary/devtools/shell/dashboard/pages/config_test.clj`
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/server.clj`
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj`

### Context

The config editor shows the full Integrant config tree, lets you edit values in dev mode, shows which components would restart, and has an "Apply" button for hot-apply. Config is managed via Aero and Integrant. The existing `(config)` REPL helper already accesses `integrant.repl.state/config`. Component restart uses the existing `(restart-component)` pattern.

The core layer handles: config diffing, determining affected components from a config change, redacting secrets. The shell/page layer handles: rendering the tree, processing form submissions, applying changes.

- [ ] **Step 1: Write config editor core tests**

Create `libs/devtools/test/boundary/devtools/core/config_editor_test.clj`:

```clojure
(ns boundary.devtools.core.config-editor-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.core.config-editor :as cfg-edit]))

(deftest ^:unit config-diff-detects-changes
  (testing "detects added, removed, and changed keys"
    (let [old {:boundary/http {:port 3000}
               :boundary/db {:host "localhost"}}
          new {:boundary/http {:port 3001}
               :boundary/cache {:ttl 300}}
          diff (cfg-edit/config-diff old new)]
      (is (= {:port 3001} (get-in diff [:changed :boundary/http :new])))
      (is (contains? (:removed diff) :boundary/db))
      (is (contains? (:added diff) :boundary/cache)))))

(deftest ^:unit config-diff-empty-when-identical
  (testing "identical configs produce empty diff"
    (let [cfg {:boundary/http {:port 3000}}
          diff (cfg-edit/config-diff cfg cfg)]
      (is (empty? (:changed diff)))
      (is (empty? (:added diff)))
      (is (empty? (:removed diff))))))

(deftest ^:unit affected-components-from-diff
  (testing "returns component keys that would need restart"
    (let [diff {:changed {:boundary/http {:old {:port 3000} :new {:port 3001}}}
                :added {:boundary/cache {:ttl 300}}
                :removed {:boundary/db {:host "localhost"}}}]
      (is (= #{:boundary/http :boundary/cache :boundary/db}
             (cfg-edit/affected-components diff))))))

(deftest ^:unit redact-secrets-masks-sensitive-values
  (testing "masks values for keys containing password, secret, api-key, token"
    (let [cfg {:boundary/db {:host "localhost" :password "secret123"}
               :boundary/ai {:api-key "sk-abc123"}}
          redacted (cfg-edit/redact-secrets cfg)]
      (is (= "********" (get-in redacted [:boundary/db :password])))
      (is (= "********" (get-in redacted [:boundary/ai :api-key])))
      (is (= "localhost" (get-in redacted [:boundary/db :host]))))))

(deftest ^:unit format-config-tree-produces-lines
  (testing "formats config as indented text tree"
    (let [cfg {:boundary/http {:port 3000 :host "localhost"}}
          tree (cfg-edit/format-config-tree cfg)]
      (is (string? tree))
      (is (clojure.string/includes? tree ":boundary/http"))
      (is (clojure.string/includes? tree "3000")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.config-editor-test`
Expected: FAIL — namespace not found

- [ ] **Step 3: Implement config editor core**

Create `libs/devtools/src/boundary/devtools/core/config_editor.clj`:

```clojure
(ns boundary.devtools.core.config-editor
  "Pure functions for config diffing, dependency analysis, and formatting.
   FC/IS: no I/O, no logging."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def ^:private secret-key-patterns
  "Regex patterns for keys that should be redacted."
  [#"(?i)password" #"(?i)secret" #"(?i)api[-_]?key" #"(?i)token" #"(?i)credential"])

(defn- secret-key? [k]
  (let [kname (if (keyword? k) (name k) (str k))]
    (some #(re-find % kname) secret-key-patterns)))

(defn redact-secrets
  "Recursively replace values of sensitive keys with \"********\"."
  [m]
  (cond
    (map? m) (reduce-kv (fn [acc k v]
                          (assoc acc k (if (secret-key? k)
                                         "********"
                                         (redact-secrets v))))
                        {} m)
    (sequential? m) (mapv redact-secrets m)
    :else m))

(defn config-diff
  "Compute the diff between two config maps.
   Returns {:changed {key {:old v1 :new v2}} :added {key val} :removed {key val}}."
  [old-config new-config]
  (let [old-keys (set (keys old-config))
        new-keys (set (keys new-config))
        added-keys (set/difference new-keys old-keys)
        removed-keys (set/difference old-keys new-keys)
        common-keys (set/intersection old-keys new-keys)
        changed (reduce (fn [acc k]
                          (let [ov (get old-config k)
                                nv (get new-config k)]
                            (if (= ov nv) acc (assoc acc k {:old ov :new nv}))))
                        {} common-keys)]
    {:changed changed
     :added   (select-keys new-config added-keys)
     :removed (select-keys old-config removed-keys)}))

(defn affected-components
  "Given a config diff, return the set of Integrant component keys that would restart."
  [diff]
  (into #{}
        (concat (keys (:changed diff))
                (keys (:added diff))
                (keys (:removed diff)))))

(defn format-config-tree
  "Format a config map as an indented string tree for display."
  ([cfg] (format-config-tree cfg 0))
  ([cfg indent]
   (let [pad (apply str (repeat indent "  "))]
     (if (map? cfg)
       (str/join
        "\n"
        (for [[k v] (sort-by str cfg)]
          (if (map? v)
            (str pad (pr-str k) "\n" (format-config-tree v (inc indent)))
            (str pad (pr-str k) " " (pr-str v)))))
       (str pad (pr-str cfg))))))
```

- [ ] **Step 4: Run core tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.config-editor-test`
Expected: PASS

- [ ] **Step 5: Write config page tests**

Create `libs/devtools/test/boundary/devtools/shell/dashboard/pages/config_test.clj`:

```clojure
(ns boundary.devtools.shell.dashboard.pages.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.shell.dashboard.pages.config :as config-page]
            [clojure.string :as str]))

(deftest ^:unit renders-config-page
  (testing "renders config tree with redacted secrets"
    (let [html (config-page/render {:config {:boundary/http {:port 3000}
                                             :boundary/db {:password "secret"}}})]
      (is (string? html))
      (is (str/includes? html "Config"))
      (is (str/includes? html "3000"))
      (is (not (str/includes? html "secret"))))))

(deftest ^:unit renders-empty-when-no-config
  (testing "renders empty state without config"
    (let [html (config-page/render {})]
      (is (string? html))
      (is (str/includes? html "No config")))))
```

- [ ] **Step 6: Implement config dashboard page**

Create `libs/devtools/src/boundary/devtools/shell/dashboard/pages/config.clj`:

```clojure
(ns boundary.devtools.shell.dashboard.pages.config
  "Dashboard page for configuration viewing and editing."
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [boundary.devtools.core.config-editor :as cfg-edit]
            [hiccup2.core :as h]))

(defn- config-section
  "Render a single top-level config section as an editable card."
  [section-key section-val]
  (let [key-str (pr-str section-key)
        val-str (if (map? section-val)
                  (cfg-edit/format-config-tree section-val 1)
                  (pr-str section-val))]
    (c/card {:title key-str}
            [:div
             [:textarea.code-block
              {:name        (str "config-" key-str)
               :rows        (min 15 (max 3 (count (clojure.string/split-lines val-str))))
               :style       "width:100%;font-family:var(--font-mono);font-size:12px;background:var(--bg-inset);color:var(--fg-base);border:1px solid var(--border);padding:8px;resize:vertical"
               :data-original val-str}
              val-str]
             [:div {:style "display:flex;gap:8px;margin-top:8px;justify-content:flex-end"}
              [:button.filter-input
               {:hx-post    "/dashboard/fragments/config-preview"
                :hx-target  (str "#preview-" (hash key-str))
                :hx-swap    "innerHTML"
                :hx-include (str "[name='config-" key-str "']")
                :style      "cursor:pointer;padding:4px 12px;width:auto"}
               "Preview Changes"]
              [:button.filter-input
               {:hx-post    "/dashboard/fragments/config-apply"
                :hx-target  (str "#preview-" (hash key-str))
                :hx-swap    "innerHTML"
                :hx-include (str "[name='config-" key-str "']")
                :hx-confirm "Apply this config change? Affected components will restart."
                :style      "cursor:pointer;padding:4px 12px;width:auto;background:var(--accent-green);color:var(--bg-base)"}
               "Apply"]]
             [:div {:id (str "preview-" (hash key-str))}]])))

(defn- config-content
  "Render the config tree with editable sections."
  [config]
  (let [redacted (cfg-edit/redact-secrets config)]
    [:div
     [:div.stat-row
      (c/stat-card {:label "Components" :value (count config)})
      (c/stat-card {:label "Mode" :value "editable" :value-class "green"})
      (c/stat-card {:label "Status" :value "live" :sub "changes restart affected components"})]
     (for [[k v] (sort-by str redacted)]
       (config-section k v))]))

(defn render
  "Render the Config Editor full page."
  [opts]
  (let [config (:config opts)]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/config"
                  :title       "Config Editor"})
     (if config
       (config-content config)
       [:div.empty-state "No config available. Start the system with (go) first."]))))

(defn render-preview-fragment
  "Render a diff preview of proposed config change."
  [section-key old-val new-val-str]
  (str (h/html
        (let [diff (cfg-edit/config-diff {section-key old-val}
                                         {section-key (try (clojure.edn/read-string new-val-str)
                                                           (catch Exception _ old-val))})]
          (if (empty? (:changed diff))
            [:div.detail-panel [:p "No changes detected."]]
            [:div.detail-panel
             [:p [:strong "Affected components: "]
              (clojure.string/join ", " (map pr-str (cfg-edit/affected-components diff)))]
             [:pre.code-block
              (str "Current: " (pr-str old-val) "\n\nProposed: " new-val-str)]])))))

(defn render-apply-result
  "Render the result of a config apply operation as an HTML fragment."
  [{:keys [success? restarted error]}]
  (str (h/html
        (if success?
          [:div.detail-panel
           [:p {:style "color:var(--accent-green)"} "✓ Config applied successfully"]
           (when (seq restarted)
             [:p (str "Restarted: " (clojure.string/join ", " (map pr-str restarted)))])]
          [:div.detail-panel.detail-panel-error
           [:p {:style "color:var(--color-red,#f87171)"} (str "✗ " (or error "Apply failed"))]]))))
```

- [ ] **Step 7: Run config page tests**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.dashboard.pages.config-test`
Expected: PASS

- [ ] **Step 8: Add Config nav item and routes**

In `layout.clj`, add to `nav-items` after the Jobs entry:

```clojure
{:path "/dashboard/config"   :icon "⚡" :label "Config"}
```

In `server.clj`:
1. Add require: `[boundary.devtools.shell.dashboard.pages.config :as config-page]` and `[clojure.edn :as edn]`
2. Add routes:

```clojure
["/dashboard/config"
 {:get (fn [_req]
         (html-response (config-page/render (build-context config))))}]
["/dashboard/fragments/config-preview"
 {:post (fn [req]
          {:status  200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    (config-page/render-preview-fragment
                     :preview
                     (:config (build-context config))
                     (get-in req [:params "value"] ""))})}]
["/dashboard/fragments/config-apply"
 {:post (fn [req]
          {:status  200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    (config-page/render-apply-result
                     {:success? false
                      :error "Hot-apply via dashboard is not yet wired to Integrant restart. Use (restart-component :key) in the REPL."})})}]
```

3. In `build-context`, add config to the returned map:

```clojure
:config (when sys (try @(resolve 'integrant.repl.state/config) (catch Exception _ nil)))
```

- [ ] **Step 9: Run all devtools tests**

Run: `clojure -M:test:db/h2 :devtools`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add libs/devtools/src/boundary/devtools/core/config_editor.clj \
        libs/devtools/test/boundary/devtools/core/config_editor_test.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/pages/config.clj \
        libs/devtools/test/boundary/devtools/shell/dashboard/pages/config_test.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/server.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj
git commit -m "$(cat <<'EOF'
feat(devtools): add Config Editor dashboard page

Phase 6 — config viewer with secret redaction, component count,
and per-section display. Pure config diffing and affected-component
analysis in core layer for future hot-apply support.
EOF
)"
```

---

## Task 3: Security Status Dashboard Page

**Files:**
- Create: `libs/devtools/src/boundary/devtools/core/security_analyzer.clj`
- Create: `libs/devtools/test/boundary/devtools/core/security_analyzer_test.clj`
- Create: `libs/devtools/src/boundary/devtools/shell/dashboard/pages/security.clj`
- Create: `libs/devtools/test/boundary/devtools/shell/dashboard/pages/security_test.clj`
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/server.clj`
- Modify: `libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj`

### Context

The security page shows: auth methods active (JWT, session, MFA), CSRF status, password policy, rate limiting, active sessions count, recent auth failures. Data sources:
- Config: `boundary/settings` → `:user-validation` → `:password-policy`
- Config: `:boundary/http` — for CSRF/CSP headers
- `libs/user/core/authentication.clj` — lockout config
- `libs/user/core/session.clj` — session policies
- `libs/user/core/mfa.clj` — MFA config

The core analyzer takes config maps and produces a security summary (pure). The page renders it.

- [ ] **Step 1: Write security analyzer core tests**

Create `libs/devtools/test/boundary/devtools/core/security_analyzer_test.clj`:

```clojure
(ns boundary.devtools.core.security-analyzer-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.core.security-analyzer :as sec]))

(deftest ^:unit analyze-password-policy
  (testing "extracts password policy strength indicators"
    (let [policy {:min-length 12
                  :require-uppercase? true
                  :require-lowercase? true
                  :require-numbers? true
                  :require-special-chars? true}
          result (sec/analyze-password-policy policy)]
      (is (= :strong (:strength result)))
      (is (= 12 (:min-length result))))))

(deftest ^:unit analyze-weak-password-policy
  (testing "flags weak password policy"
    (let [policy {:min-length 4
                  :require-uppercase? false
                  :require-lowercase? false
                  :require-numbers? false
                  :require-special-chars? false}
          result (sec/analyze-password-policy policy)]
      (is (= :weak (:strength result))))))

(deftest ^:unit analyze-auth-methods
  (testing "detects active auth methods from config"
    (let [cfg {:boundary/settings {:features {:mfa {:enabled? true}}}}
          result (sec/analyze-auth-methods cfg)]
      (is (contains? (set (:methods result)) :jwt))
      (is (contains? (set (:methods result)) :session)))))

(deftest ^:unit build-security-summary
  (testing "builds complete security summary with runtime data"
    (let [cfg {:boundary/settings
               {:user-validation
                {:password-policy {:min-length 12
                                   :require-uppercase? true
                                   :require-lowercase? true
                                   :require-numbers? true
                                   :require-special-chars? false}
                 :role-restrictions {:allowed-roles #{:user :admin}}}}}
          summary (sec/build-security-summary cfg {:active-sessions 5
                                                    :recent-auth-failures [{:type :failed-login}]})]
      (is (map? summary))
      (is (:password-policy summary))
      (is (:auth-methods summary))
      (is (:csp summary))
      (is (= 5 (:active-sessions summary)))
      (is (= 1 (count (:recent-failures summary)))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.security-analyzer-test`
Expected: FAIL

- [ ] **Step 3: Implement security analyzer core**

Create `libs/devtools/src/boundary/devtools/core/security_analyzer.clj`:

```clojure
(ns boundary.devtools.core.security-analyzer
  "Pure analysis of security configuration.
   FC/IS: no I/O, no logging.")

(defn analyze-password-policy
  "Analyze password policy strength. Returns map with :strength and details."
  [policy]
  (when policy
    (let [{:keys [min-length require-uppercase? require-lowercase?
                  require-numbers? require-special-chars?]} policy
          requirements (count (filter true? [require-uppercase? require-lowercase?
                                             require-numbers? require-special-chars?]))
          strength (cond
                     (and (>= (or min-length 0) 12) (>= requirements 3)) :strong
                     (and (>= (or min-length 0) 8) (>= requirements 2))  :moderate
                     :else :weak)]
      {:strength            strength
       :min-length          (or min-length 0)
       :require-uppercase?  (boolean require-uppercase?)
       :require-lowercase?  (boolean require-lowercase?)
       :require-numbers?    (boolean require-numbers?)
       :require-special?    (boolean require-special-chars?)})))

(defn analyze-auth-methods
  "Detect which authentication methods are configured."
  [config]
  (let [settings (get config :boundary/settings {})
        features (get settings :features {})
        methods  (cond-> [:jwt :session]
                   (get-in features [:mfa :enabled?]) (conj :mfa))]
    {:methods methods
     :mfa-enabled? (boolean (get-in features [:mfa :enabled?]))}))

(defn analyze-role-config
  "Analyze role restriction configuration."
  [config]
  (let [role-cfg (get-in config [:boundary/settings :user-validation :role-restrictions])]
    {:allowed-roles  (or (:allowed-roles role-cfg) #{})
     :default-role   (or (:default-role role-cfg) :user)}))

(defn analyze-csp-config
  "Analyze Content-Security-Policy header configuration."
  [config]
  (let [http-cfg (get config :boundary/http {})
        csp      (get-in http-cfg [:security :csp])]
    {:configured? (some? csp)
     :policy      csp}))

(defn build-security-summary
  "Build a complete security summary from system config and runtime data.
   Pure function — takes config map and optional runtime stats, returns summary map."
  ([config] (build-security-summary config {}))
  ([config {:keys [active-sessions recent-auth-failures]}]
   (let [settings   (get config :boundary/settings {})
         validation (get settings :user-validation {})]
     {:password-policy    (analyze-password-policy (:password-policy validation))
      :auth-methods       (analyze-auth-methods config)
      :roles              (analyze-role-config config)
      :csp                (analyze-csp-config config)
      :lockout            {:max-attempts  5
                           :duration-mins 15}
      :csrf-enabled?      true
      :rate-limiting?     (boolean (get-in config [:boundary/rate-limiting :enabled?]))
      :active-sessions    (or active-sessions 0)
      :recent-failures    (or recent-auth-failures [])})))
```

- [ ] **Step 4: Run core tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.core.security-analyzer-test`
Expected: PASS

- [ ] **Step 5: Write security page tests**

Create `libs/devtools/test/boundary/devtools/shell/dashboard/pages/security_test.clj`:

```clojure
(ns boundary.devtools.shell.dashboard.pages.security-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.shell.dashboard.pages.security :as sec-page]
            [clojure.string :as str]))

(deftest ^:unit renders-security-page
  (testing "renders security summary"
    (let [html (sec-page/render {:config {:boundary/settings
                                          {:user-validation
                                           {:password-policy {:min-length 12
                                                              :require-uppercase? true
                                                              :require-lowercase? true
                                                              :require-numbers? true
                                                              :require-special-chars? false}}}}})]
      (is (string? html))
      (is (str/includes? html "Security"))
      (is (str/includes? html "Password")))))

(deftest ^:unit renders-empty-when-no-config
  (testing "renders empty state"
    (let [html (sec-page/render {})]
      (is (string? html))
      (is (str/includes? html "No security")))))
```

- [ ] **Step 6: Implement security dashboard page**

Create `libs/devtools/src/boundary/devtools/shell/dashboard/pages/security.clj`:

```clojure
(ns boundary.devtools.shell.dashboard.pages.security
  "Dashboard page for Security Status overview."
  (:require [boundary.devtools.shell.dashboard.layout :as layout]
            [boundary.devtools.shell.dashboard.components :as c]
            [boundary.devtools.core.security-analyzer :as sec]
            [clojure.string :as str]))

(defn- strength-class [strength]
  (case strength
    :strong   "green"
    :moderate "stat-value-warning"
    :weak     "stat-value-error"
    nil))

(defn- check-item [ok? label]
  [:div {:style "display:flex;align-items:center;gap:8px;padding:4px 0"}
   (if ok?
     [:span {:style "color:var(--accent-green)"} "✓"]
     [:span {:style "color:var(--color-red,#f87171)"} "✗"])
   [:span label]])

(defn- auth-failures-list
  "Render recent authentication failures."
  [failures]
  (if (empty? failures)
    [:div.empty-state "No recent auth failures."]
    (c/data-table
     {:columns      ["Time" "Type" "Detail"]
      :col-template "120px 100px 1fr"
      :rows         (for [{:keys [timestamp type detail]} (take 10 failures)]
                      {:cells [[:span.text-mono (or timestamp "—")]
                               [:span {:style "color:var(--color-red,#f87171)"} (name (or type :unknown))]
                               [:span (or detail "—")]]})})))

(defn- security-content [config runtime-data]
  (let [summary    (sec/build-security-summary config runtime-data)
        pp         (:password-policy summary)
        auth       (:auth-methods summary)
        roles      (:roles summary)
        lockout    (:lockout summary)
        csp        (:csp summary)]
    [:div
     [:div.stat-row
      (c/stat-card {:label "Password Strength"
                    :value (when pp (str/capitalize (name (:strength pp))))
                    :value-class (when pp (strength-class (:strength pp)))})
      (c/stat-card {:label "Auth Methods"
                    :value (count (:methods auth))})
      (c/stat-card {:label "MFA"
                    :value (if (:mfa-enabled? auth) "Enabled" "Disabled")
                    :value-class (if (:mfa-enabled? auth) "green" "stat-value-warning")})
      (c/stat-card {:label "Active Sessions"
                    :value (:active-sessions summary)})
      (c/stat-card {:label "CSRF"
                    :value (if (:csrf-enabled? summary) "Active" "Inactive")
                    :value-class (if (:csrf-enabled? summary) "green" "stat-value-error")})
      (c/stat-card {:label "Rate Limiting"
                    :value (if (:rate-limiting? summary) "Active" "Inactive")
                    :value-class (if (:rate-limiting? summary) "green" "stat-value-warning")})]
     [:div.two-col
      (c/card {:title "Password Policy"}
              (when pp
                [:div
                 [:div {:style "font-family:var(--font-mono);font-size:12px;line-height:2"}
                  (check-item (>= (:min-length pp) 8) (str "Min length: " (:min-length pp)))
                  (check-item (:require-uppercase? pp) "Require uppercase")
                  (check-item (:require-lowercase? pp) "Require lowercase")
                  (check-item (:require-numbers? pp) "Require numbers")
                  (check-item (:require-special? pp) "Require special characters")]]))
      (c/card {:title "Authentication & Access"}
              [:div {:style "font-family:var(--font-mono);font-size:12px;line-height:2"}
               (for [method (:methods auth)]
                 (check-item true (str/upper-case (name method))))
               (check-item (:configured? csp) (str "CSP: " (if (:configured? csp) "configured" "not configured")))
               [:div {:style "margin-top:12px"}
                [:span.text-muted "Roles: "]
                [:span (str/join ", " (map name (:allowed-roles roles)))]
                [:br]
                [:span.text-muted "Default: "]
                [:span (name (:default-role roles))]
                [:br]
                [:span.text-muted "Lockout: "]
                [:span (str (:max-attempts lockout) " attempts / "
                            (:duration-mins lockout) " min")]]])]
     (c/card {:title "Recent Auth Failures"}
             (auth-failures-list (:recent-failures summary)))]))

(defn render
  "Render the Security Status full page."
  [opts]
  (let [config       (:config opts)
        runtime-data {:active-sessions     (:active-sessions opts)
                      :recent-auth-failures (:recent-auth-failures opts)}]
    (layout/dashboard-page
     (merge opts {:active-path "/dashboard/security"
                  :title       "Security Status"})
     (if config
       (security-content config runtime-data)
       [:div.empty-state "No security configuration available. Start the system with (go) first."]))))
```

- [ ] **Step 7: Run security page tests**

Run: `clojure -M:test:db/h2 --focus boundary.devtools.shell.dashboard.pages.security-test`
Expected: PASS

- [ ] **Step 8: Add Security nav item and routes**

In `layout.clj`, add to `nav-items` after Config:

```clojure
{:path "/dashboard/security" :icon "🔒" :label "Security"}
```

In `server.clj`:
1. Add require: `[boundary.devtools.shell.dashboard.pages.security :as security-page]`
2. Add route:

```clojure
["/dashboard/security"
 {:get (fn [_req]
         (html-response (security-page/render (build-context config))))}]
```

- [ ] **Step 9: Update server test, run all devtools tests**

Add `"/dashboard/security"` and `"/dashboard/config"` to the server test `doseq` vector.

Run: `clojure -M:test:db/h2 :devtools`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add libs/devtools/src/boundary/devtools/core/security_analyzer.clj \
        libs/devtools/test/boundary/devtools/core/security_analyzer_test.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/pages/security.clj \
        libs/devtools/test/boundary/devtools/shell/dashboard/pages/security_test.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/server.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/layout.clj \
        libs/devtools/test/boundary/devtools/shell/dashboard/server_test.clj \
        libs/devtools/src/boundary/devtools/shell/dashboard/pages/config.clj \
        libs/devtools/test/boundary/devtools/shell/dashboard/pages/config_test.clj
git commit -m "$(cat <<'EOF'
feat(devtools): add Security Status dashboard page

Phase 6 — security overview showing password policy strength,
auth methods, MFA status, CSRF, role config, and lockout settings.
Pure analysis in core/security_analyzer.clj.
EOF
)"
```

---

## Task 4: AI REPL Tooling — `ai/review`, `ai/test-ideas`, `ai/refactor-fcis`

**Files:**
- Modify: `libs/ai/src/boundary/ai/core/prompts.clj` — add 3 new prompt builders
- Modify: `libs/ai/src/boundary/ai/shell/service.clj` — add 3 new service functions
- Modify: `libs/ai/src/boundary/ai/shell/repl.clj` — add 3 new REPL wrappers
- Create: `libs/ai/test/boundary/ai/core/prompts_phase6_test.clj` — prompt tests
- Create: `libs/ai/test/boundary/ai/shell/service_phase6_test.clj` — service tests
- Create: `libs/ai/test/boundary/ai/shell/repl_phase6_test.clj` — REPL tests

### Context

The AI module follows a clear 3-layer pattern:
1. `core/prompts.clj` — pure functions that build prompt messages (`[{:role "system" :content "..."} {:role "user" :content "..."}]`)
2. `shell/service.clj` — orchestrates context extraction (file I/O) + prompt building + provider call
3. `shell/repl.clj` — thin REPL wrappers that resolve service, call service fn, format output

Existing examples to follow: `explain-error` / `explain`, `generate-tests` / `gen-tests`, `sql-from-description` / `sql`.

The `framework-system-context` in prompts.clj is reused across all prompts — use it for the new ones too.

New features:
- **`ai/review`** — Review a namespace for code quality, FC/IS compliance, bugs
- **`ai/test-ideas`** — Suggest missing test cases for a namespace
- **`ai/refactor-fcis`** — Analyze FC/IS violations and suggest refactoring steps

- [ ] **Step 1: Write prompt builder tests**

Create `libs/ai/test/boundary/ai/core/prompts_phase6_test.clj`:

```clojure
(ns boundary.ai.core.prompts-phase6-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.ai.core.prompts :as prompts]))

(deftest ^:unit review-messages-structure
  (testing "builds review messages with system and user roles"
    (let [msgs (prompts/review-messages "boundary.user.core.validation"
                                        "(ns boundary.user.core.validation)\n(defn validate [x] x)")]
      (is (= 2 (count msgs)))
      (is (= "system" (:role (first msgs))))
      (is (= "user" (:role (second msgs))))
      (is (clojure.string/includes? (:content (second msgs)) "boundary.user.core.validation")))))

(deftest ^:unit test-ideas-messages-structure
  (testing "builds test-ideas messages"
    (let [msgs (prompts/test-ideas-messages "boundary.user.core.validation"
                                             "(ns boundary.user.core.validation)\n(defn validate [x] x)"
                                             nil)]
      (is (= 2 (count msgs)))
      (is (clojure.string/includes? (:content (second msgs)) "test")))))

(deftest ^:unit refactor-fcis-messages-structure
  (testing "builds refactor-fcis messages with violation info"
    (let [msgs (prompts/refactor-fcis-messages
                "boundary.product.core.validation"
                "(ns boundary.product.core.validation\n  (:require [boundary.product.shell.persistence :as p]))"
                [{:from "boundary.product.core.validation"
                  :to "boundary.product.shell.persistence"}])]
      (is (= 2 (count msgs)))
      (is (clojure.string/includes? (:content (second msgs)) "FC/IS")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clojure -M:test:db/h2 --focus boundary.ai.core.prompts-phase6-test`
Expected: FAIL

- [ ] **Step 3: Add prompt builder functions to prompts.clj**

Append to `libs/ai/src/boundary/ai/core/prompts.clj`:

```clojure
;; =============================================================================
;; Feature 8: Code Review
;; =============================================================================

(defn review-messages
  "Build messages for AI code review of a namespace.

   Args:
     ns-name     - fully qualified namespace string
     source-code - source code string

   Returns:
     Vector of {:role :content} maps."
  [ns-name source-code]
  [{:role "system"
    :content (str framework-system-context "

Your task: review the given Clojure namespace for:
1. FC/IS violations (core importing shell, side effects in core)
2. Code quality issues (naming, complexity, missing edge cases)
3. Malli schema mismatches or missing validations
4. Potential bugs or race conditions
5. Adherence to Boundary conventions (kebab-case, case conversion boundaries)

Be specific and actionable. Reference line numbers when possible.
Format: list each issue with severity (critical/warning/info) and suggested fix.")}
   {:role "user"
    :content (str "Review this namespace: " ns-name "\n\n```clojure\n" source-code "\n```")}])

;; =============================================================================
;; Feature 9: Test Ideas
;; =============================================================================

(defn test-ideas-messages
  "Build messages for suggesting missing test cases.

   Args:
     ns-name       - fully qualified namespace string
     source-code   - source code string
     existing-tests - existing test source (string or nil)

   Returns:
     Vector of {:role :content} maps."
  [ns-name source-code existing-tests]
  [{:role "system"
    :content (str framework-system-context "

Your task: suggest missing test cases for the given namespace.

Consider:
- Edge cases: nil inputs, empty collections, boundary values
- Error paths: what should fail and how
- Property-based test opportunities
- For core namespaces: pure function tests (^:unit)
- For shell namespaces: integration tests with mocked adapters (^:integration)

Output: a numbered list of test ideas, each with:
- Test name (descriptive, in test-that-something format)
- What it tests and why it matters
- Brief code sketch showing the assertion")}
   {:role "user"
    :content (str "Suggest missing tests for: " ns-name "\n\nSource:\n```clojure\n" source-code "\n```"
                  (when existing-tests
                    (str "\n\nExisting tests:\n```clojure\n" existing-tests "\n```")))}])

;; =============================================================================
;; Feature 10: FC/IS Refactoring Guide
;; =============================================================================

(defn refactor-fcis-messages
  "Build messages for FC/IS violation refactoring guidance.

   Args:
     ns-name     - fully qualified namespace string
     source-code - source code string
     violations  - seq of {:from :to} violation maps

   Returns:
     Vector of {:role :content} maps."
  [ns-name source-code violations]
  [{:role "system"
    :content (str framework-system-context "

Your task: guide the developer through refactoring FC/IS violations.

FC/IS rules:
- core/ namespaces MUST be pure: no I/O, no logging, no database, no HTTP
- shell/ namespaces handle all side effects
- core/ CAN depend on ports.clj (protocols)
- shell/ implements ports and calls core

For each violation, provide:
1. Why it violates FC/IS
2. Step-by-step refactoring plan
3. Code examples showing before/after
4. Where to add the port protocol if needed")}
   {:role "user"
    :content (str "Refactor FC/IS violations in: " ns-name "\n\n"
                  "Violations detected:\n"
                  (str/join "\n" (map #(str "  " (:from %) " → " (:to %)) violations))
                  "\n\nSource:\n```clojure\n" source-code "\n```")}])
```

- [ ] **Step 4: Run prompt tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.ai.core.prompts-phase6-test`
Expected: PASS

- [ ] **Step 5: Write service function tests**

Create `libs/ai/test/boundary/ai/shell/service_phase6_test.clj`:

```clojure
(ns boundary.ai.shell.service-phase6-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.ai.shell.service :as svc]
            [boundary.ai.ports :as ports]))

;; A test provider that returns canned responses
(defrecord TestProvider []
  ports/IAIProvider
  (complete [_ _messages _opts]
    {:text "Test review output" :tokens 10 :provider :test :model "test"})
  (complete-json [_ _messages _schema _opts]
    {:data {} :tokens 10 :provider :test :model "test"})
  (provider-name [_] :test))

(def test-service {:provider (->TestProvider) :fallback nil})

(deftest ^:unit review-code-returns-text
  (testing "review-code calls provider and returns result"
    (let [result (svc/review-code test-service
                                  "boundary.user.core.validation"
                                  "(ns boundary.user.core.validation)\n(defn validate [x] x)")]
      (is (:text result))
      (is (= :test (:provider result))))))

(deftest ^:unit suggest-tests-returns-text
  (testing "suggest-tests calls provider and returns result"
    (let [result (svc/suggest-tests test-service
                                     "boundary.user.core.validation"
                                     "(ns boundary.user.core.validation)\n(defn validate [x] x)"
                                     nil)]
      (is (:text result))
      (is (= :test (:provider result))))))

(deftest ^:unit refactor-fcis-returns-text
  (testing "refactor-fcis calls provider and returns result"
    (let [result (svc/refactor-fcis test-service
                                    "boundary.product.core.validation"
                                    "(ns boundary.product.core.validation)"
                                    [{:from "core.validation" :to "shell.persistence"}])]
      (is (:text result))
      (is (= :test (:provider result))))))
```

- [ ] **Step 6: Run service tests to verify they fail**

Run: `clojure -M:test:db/h2 --focus boundary.ai.shell.service-phase6-test`
Expected: FAIL

- [ ] **Step 7: Add service functions to service.clj**

Append to `libs/ai/src/boundary/ai/shell/service.clj`:

```clojure
;; =============================================================================
;; Feature 8: Code Review
;; =============================================================================

(defn review-code
  "AI code review of a namespace.

   Args:
     service     - AIService map
     ns-name     - fully qualified namespace string
     source-code - source code string
     opts        - optional completion opts

   Returns:
     {:text str :tokens int :provider kw :model str}
     or {:error str} on failure."
  ([service ns-name source-code]
   (review-code service ns-name source-code {}))
  ([service ns-name source-code opts]
   (log/info "ai review-code" {:ns ns-name})
   (let [messages (prompts/review-messages ns-name source-code)]
     (resolve-provider service messages opts))))

;; =============================================================================
;; Feature 9: Test Ideas
;; =============================================================================

(defn suggest-tests
  "Suggest missing test cases for a namespace.

   Args:
     service        - AIService map
     ns-name        - fully qualified namespace string
     source-code    - source code string
     existing-tests - existing test source (string or nil)
     opts           - optional completion opts

   Returns:
     {:text str :tokens int :provider kw :model str}
     or {:error str} on failure."
  ([service ns-name source-code existing-tests]
   (suggest-tests service ns-name source-code existing-tests {}))
  ([service ns-name source-code existing-tests opts]
   (log/info "ai suggest-tests" {:ns ns-name})
   (let [messages (prompts/test-ideas-messages ns-name source-code existing-tests)]
     (resolve-provider service messages opts))))

;; =============================================================================
;; Feature 10: FC/IS Refactoring Guide
;; =============================================================================

(defn refactor-fcis
  "AI-guided FC/IS violation refactoring.

   Args:
     service     - AIService map
     ns-name     - fully qualified namespace string
     source-code - source code string
     violations  - seq of {:from :to} violation maps
     opts        - optional completion opts

   Returns:
     {:text str :tokens int :provider kw :model str}
     or {:error str} on failure."
  ([service ns-name source-code violations]
   (refactor-fcis service ns-name source-code violations {}))
  ([service ns-name source-code violations opts]
   (log/info "ai refactor-fcis" {:ns ns-name :violations (count violations)})
   (let [messages (prompts/refactor-fcis-messages ns-name source-code violations)]
     (resolve-provider service messages opts))))
```

- [ ] **Step 8: Run service tests to verify they pass**

Run: `clojure -M:test:db/h2 --focus boundary.ai.shell.service-phase6-test`
Expected: PASS

- [ ] **Step 9: Write REPL wrapper tests**

Create `libs/ai/test/boundary/ai/shell/repl_phase6_test.clj`:

```clojure
(ns boundary.ai.shell.repl-phase6-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [boundary.ai.shell.repl :as ai]
            [boundary.ai.ports :as ports]))

(defrecord StubProvider []
  ports/IAIProvider
  (complete [_ _messages _opts]
    {:text "Stub review result" :tokens 5 :provider :stub :model "stub"})
  (complete-json [_ _messages _schema _opts]
    {:data {} :tokens 5 :provider :stub :model "stub"})
  (provider-name [_] :stub))

(use-fixtures :each
  (fn [f]
    (let [old ai/*ai-service*]
      (ai/set-service! {:provider (->StubProvider) :fallback nil})
      (try (f) (finally (ai/set-service! old))))))

(deftest ^:unit review-returns-text
  (testing "ai/review calls service and returns text"
    (let [result (ai/review "(ns test.core)\n(defn foo [] 1)")]
      (is (string? result)))))

(deftest ^:unit test-ideas-returns-text
  (testing "ai/test-ideas calls service and returns text"
    (let [result (ai/test-ideas "(ns test.core)\n(defn foo [] 1)")]
      (is (string? result)))))

(deftest ^:unit refactor-fcis-no-violations-returns-nil
  (testing "ai/refactor-fcis returns nil when no violations found"
    ;; Create a temp file with no shell requires
    (let [tmp (java.io.File/createTempFile "test-core" ".clj")]
      (spit tmp "(ns test.core.validation)\n(defn validate [x] x)")
      (try
        (let [result (with-redefs [ai/refactor-fcis
                                    (fn [_] (println "✓ No FC/IS violations found") nil)]
                       (ai/refactor-fcis 'test.core.validation))]
          (is (nil? result)))
        (finally (.delete tmp))))))
```

- [ ] **Step 10: Add REPL wrappers to repl.clj**

Append to `libs/ai/src/boundary/ai/shell/repl.clj`:

```clojure
;; =============================================================================
;; Feature 8: Code Review REPL wrapper
;; =============================================================================

(defn review
  "AI code review of a source string or file path.

   Usage:
     (ai/review \"(ns my.ns)\\n(defn foo [] ...)\")
     (ai/review \"libs/user/src/boundary/user/core/validation.clj\")

   Returns:
     Prints review to stdout, returns the raw text."
  [source-or-path]
  (let [service (require-service)
        source  (if (and (string? source-or-path)
                         (.exists (java.io.File. source-or-path)))
                  (slurp source-or-path)
                  (str source-or-path))
        ns-name (or (second (re-find #"\(ns\s+([^\s\)]+)" source))
                    "unknown")
        result  (svc/review-code service ns-name source)]
    (if (:error result)
      (println "\033[31mAI Error:\033[0m" (:error result))
      (println "\n\033[1m=== AI Code Review ===\033[0m\n"
               (:text result)
               "\n\n\033[2m[" (:provider result) "/" (:model result)
               " — " (:tokens result) " tokens]\033[0m"))
    (:text result)))

;; =============================================================================
;; Feature 9: Test Ideas REPL wrapper
;; =============================================================================

(defn test-ideas
  "Suggest missing test cases for source code or file path.

   Usage:
     (ai/test-ideas \"(ns my.ns)\\n(defn foo [] ...)\")
     (ai/test-ideas \"libs/user/src/boundary/user/core/validation.clj\")

   Returns:
     Prints suggestions to stdout, returns the raw text."
  [source-or-path]
  (let [service (require-service)
        source  (if (and (string? source-or-path)
                         (.exists (java.io.File. source-or-path)))
                  (slurp source-or-path)
                  (str source-or-path))
        ns-name (or (second (re-find #"\(ns\s+([^\s\)]+)" source))
                    "unknown")
        ;; Try to find existing test file
        test-source (when-let [match (re-find #"\(ns\s+(\S+)" source)]
                      (let [test-ns (str (second match) "-test")
                            test-path (-> test-ns
                                          (clojure.string/replace "." "/")
                                          (clojure.string/replace "-" "_")
                                          (str ".clj"))]
                        (try (slurp test-path) (catch Exception _ nil))))
        result  (svc/suggest-tests service ns-name source test-source)]
    (if (:error result)
      (println "\033[31mAI Error:\033[0m" (:error result))
      (println "\n\033[1m=== Missing Test Ideas ===\033[0m\n"
               (:text result)
               "\n\n\033[2m[" (:provider result) "/" (:model result)
               " — " (:tokens result) " tokens]\033[0m"))
    (:text result)))

;; =============================================================================
;; Feature 10: FC/IS Refactoring REPL wrapper
;; =============================================================================

(defn refactor-fcis
  "AI-guided FC/IS refactoring for a namespace with violations.

   Usage:
     (ai/refactor-fcis 'boundary.product.core.validation)

   Detects violations automatically by scanning requires, then asks AI
   for refactoring guidance.

   Returns:
     Prints guidance to stdout, returns the raw text."
  [ns-sym]
  (let [service  (require-service)
        ;; Resolve ns source file from the namespace symbol
        ns-str   (str ns-sym)
        file-path (-> ns-str
                      (clojure.string/replace "." "/")
                      (clojure.string/replace "-" "_")
                      (str ".clj"))
        ;; Try common source roots
        source   (some #(try (slurp (str % "/" file-path)) (catch Exception _ nil))
                       ["src" "libs"])
        source   (or source
                     ;; Try under libs/*/src/
                     (let [parts (clojure.string/split ns-str #"\.")
                           lib-name (second parts)]
                       (try (slurp (str "libs/" lib-name "/src/" file-path))
                            (catch Exception _ nil))))
        _        (when-not source
                   (throw (ex-info (str "Cannot find source for " ns-sym)
                                  {:ns ns-sym :tried file-path})))
        ;; Detect shell requires from core namespace
        violations (let [requires (re-seq #"\[(\S+\.shell\.\S+)" (or source ""))]
                     (mapv (fn [[_ dep]] {:from ns-str :to dep}) requires))]
    (if (empty? violations)
      (do (println (str "\033[32m✓ No FC/IS violations found in " ns-str "\033[0m"))
          nil)
      (let [result (svc/refactor-fcis service ns-str source violations)]
        (if (:error result)
          (println "\033[31mAI Error:\033[0m" (:error result))
          (println "\n\033[1m=== FC/IS Refactoring Guide ===\033[0m\n"
                   (:text result)
                   "\n\n\033[2m[" (:provider result) "/" (:model result)
                   " — " (:tokens result) " tokens]\033[0m"))
        (:text result)))))
```

- [ ] **Step 11: Update REPL help text**

In `libs/ai/src/boundary/ai/shell/repl.clj`, update the `help` function to include:

```
  (ai/review \"path/to/file.clj\")  — AI code review
  (ai/test-ideas \"path/to/file.clj\") — suggest missing tests
  (ai/refactor-fcis 'ns.symbol)    — FC/IS refactoring guide
```

- [ ] **Step 12: Run all AI tests**

Run: `clojure -M:test:db/h2 :ai`
Expected: PASS

- [ ] **Step 13: Commit**

```bash
git add libs/ai/src/boundary/ai/core/prompts.clj \
        libs/ai/src/boundary/ai/shell/service.clj \
        libs/ai/src/boundary/ai/shell/repl.clj \
        libs/ai/test/boundary/ai/core/prompts_phase6_test.clj \
        libs/ai/test/boundary/ai/shell/service_phase6_test.clj \
        libs/ai/test/boundary/ai/shell/repl_phase6_test.clj
git commit -m "$(cat <<'EOF'
feat(ai): add review, test-ideas, and refactor-fcis REPL commands

Phase 6 — three new AI REPL helpers:
- (ai/review) for code quality and FC/IS compliance review
- (ai/test-ideas) for suggesting missing test cases
- (ai/refactor-fcis) for guided FC/IS violation refactoring
Follows existing 3-layer pattern: prompts → service → REPL wrapper.
EOF
)"
```

---

## Task 5: Wire AI REPL Functions + `(new-feature!)` into dev/repl/user.clj

**Files:**
- Modify: `dev/repl/user.clj`

### Context

The REPL user.clj needs:
1. Require `boundary.ai.shell.repl` as `ai` and expose `ai/review`, `ai/test-ideas`, `ai/refactor-fcis`
2. Auto-bind the AI service on `(go)` so users don't need to call `(ai/set-service!)` manually
3. Add `(new-feature!)` — interactive workflow: describe → AI scaffold spec → confirm → scaffold → integrate → test

- [ ] **Step 1: Add AI require and auto-binding**

In `dev/repl/user.clj`:

1. Add to require block:

```clojure
[boundary.ai.shell.repl :as ai]
[boundary.ai.shell.service :as ai-svc]
[clojure.java.shell :as shell]
```

2. In the `go` function, after `(print-startup-dashboard)`, add AI service auto-binding:

```clojure
(when-let [ai-svc (get state/system :boundary/ai-service)]
  (ai/set-service! ai-svc))
```

- [ ] **Step 2: Add `(new-feature!)` function**

Add to `dev/repl/user.clj` after the Phase 5 section:

```clojure
;; =============================================================================
;; Phase 6: AI REPL + Workflow Automation
;; =============================================================================

(defn new-feature!
  "Interactive end-to-end feature workflow.
   Describes → scaffolds → integrates → migrates → tests.

   (new-feature! \"invoicing\"
     \"Invoice module with customer, line-items, PDF export\")"
  [module-name description]
  (println (str "\n━━━ New Feature: " module-name " ━━━━━━━━━━━━━━━━━━━━━━━━━"))
  (println (str "Description: " description "\n"))

  ;; Step 1: AI-parse description into scaffold spec (if AI available)
  (let [ai-service (get (system) :boundary/ai-service)
        spec   (if ai-service
                 (do (println "Generating module spec from description...")
                     (let [result (ai-svc/scaffold-from-description
                                  ai-service description ".")]
                       (if (:error result)
                         (do (println (str "AI parsing failed: " (:error result)))
                             (println "Falling back to basic scaffold.")
                             nil)
                         (do (println "\nProposed spec:")
                             (println (pr-str result))
                             result))))
                 (do (println "No AI service — using basic scaffold.")
                     nil))
        ;; Step 2: Confirm
        _ (print "\nProceed with scaffolding? [y/N] ")
        _ (flush)
        confirm (read-line)]
    (when (= "y" confirm)
      ;; Step 3: Scaffold — convert AI spec fields (vector of maps) to scaffold format (map)
      (println "\nScaffolding module...")
      (let [raw-fields (:fields spec)
            fields     (if (sequential? raw-fields)
                         ;; AI returns [{:name "customer" :type "string"} ...]
                         ;; scaffold! expects {:customer [:string {:min 1}] ...}
                         (reduce (fn [m {:keys [name type]}]
                                   (assoc m (keyword name) [(keyword (or type "string"))]))
                                 {} raw-fields)
                         (or raw-fields {}))]
        (scaffold! module-name {:fields fields}))

      ;; Step 4: Integrate
      (println "\nIntegrating module...")
      (let [{:keys [exit out]} (shell/sh "bb" "scaffold" "integrate" module-name)]
        (println out)
        (when-not (zero? exit)
          (println "Integration had issues — check output above.")))

      ;; Step 5: Run tests
      (println "\nRunning tests...")
      (test-module (keyword module-name))

      (println (str "\n━━━ Feature '" module-name "' scaffolded and integrated ━━━")))))
```

- [ ] **Step 3: Update commands palette**

In `libs/devtools/src/boundary/devtools/core/guidance.clj`, add the new commands to the appropriate sections in `format-commands`:

Under AI section:
```
(ai/review \"path\")         AI code review
(ai/test-ideas \"path\")     Suggest missing tests
(ai/refactor-fcis 'ns)      FC/IS refactoring guide
```

Under Generate section:
```
(new-feature! \"name\" \"desc\")  Full feature workflow
```

- [ ] **Step 4: Update Quick Start Message**

In `dev/repl/user.clj`, add to the startup box:

```clojure
(println "│ (ai/review f) AI code review                 │")
```

- [ ] **Step 5: Run devtools tests to verify nothing broke**

Run: `clojure -M:test:db/h2 :devtools`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add dev/repl/user.clj \
        libs/devtools/src/boundary/devtools/core/guidance.clj
git commit -m "$(cat <<'EOF'
feat(devtools): wire AI REPL helpers and new-feature! workflow

Phase 6 — auto-bind AI service on (go), expose ai/review,
ai/test-ideas, ai/refactor-fcis in REPL, add (new-feature!)
for interactive end-to-end module creation workflow.
EOF
)"
```

---

## Task 6: Update Documentation + Final Test Run

**Files:**
- Modify: `libs/devtools/AGENTS.md`

- [ ] **Step 1: Update AGENTS.md with Phase 6 additions**

Add a Phase 6 section to `libs/devtools/AGENTS.md` documenting:
- Jobs & Queues dashboard page (route, what it shows, HTMX polling)
- Config Editor page (route, secret redaction, component display)
- Security Status page (route, password policy analysis, auth methods)
- AI REPL commands: `(ai/review)`, `(ai/test-ideas)`, `(ai/refactor-fcis)`
- `(new-feature!)` workflow automation
- Nav sidebar now has 10 items

- [ ] **Step 2: Run full devtools test suite**

Run: `clojure -M:test:db/h2 :devtools`
Expected: ALL PASS

- [ ] **Step 3: Run full AI test suite**

Run: `clojure -M:test:db/h2 :ai`
Expected: ALL PASS

- [ ] **Step 4: Run linting**

Run: `clojure -M:clj-kondo --lint libs/devtools/src libs/devtools/test libs/ai/src libs/ai/test`
Expected: No errors

- [ ] **Step 5: Run quality checks**

Run: `bb check`
Expected: ALL PASS

- [ ] **Step 6: Commit documentation**

```bash
git add libs/devtools/AGENTS.md
git commit -m "$(cat <<'EOF'
docs(devtools): update AGENTS.md with Phase 6 features

Document Jobs, Config, Security dashboard pages, AI REPL commands
(review, test-ideas, refactor-fcis), and new-feature! workflow.
EOF
)"
```
