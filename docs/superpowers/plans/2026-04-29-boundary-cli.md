# Boundary CLI Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone `boundary` CLI tool that bootstraps a new Boundary project on a fresh machine with a single `curl` command and lets users (and AI agents) wire in optional modules incrementally.

**Architecture:** A Babashka library at `libs/boundary-cli/` published via bbin git install. Commands share a static module catalogue (`modules-catalogue.edn`) bundled in resources. Generated projects are standalone — they do not require the monorepo. All commands are non-interactive by default (safe for AI agents).

**Tech Stack:** Babashka (bb), Clojure `clojure.test`, `clojure.java.io`, `cheshire` (JSON), `babashka.process`, existing boundary build/deploy pipeline (`build.clj` + `bb deploy`).

---

## File Map

### New files

| File | Responsibility |
|------|---------------|
| `libs/boundary-cli/bb.edn` | bbin entrypoint + local test task |
| `libs/boundary-cli/deps.edn` | Babashka classpath: src + resources |
| `libs/boundary-cli/build.clj` | tools.build config for Clojars publish |
| `libs/boundary-cli/src/boundary/cli/main.clj` | Command dispatch (`boundary <cmd>`) |
| `libs/boundary-cli/src/boundary/cli/catalogue.clj` | Load, validate, query `modules-catalogue.edn` |
| `libs/boundary-cli/src/boundary/cli/list_modules.clj` | `boundary list modules [--json]` |
| `libs/boundary-cli/src/boundary/cli/new.clj` | `boundary new <name> [--force]` |
| `libs/boundary-cli/src/boundary/cli/add.clj` | `boundary add <module>` |
| `libs/boundary-cli/resources/boundary/cli/modules-catalogue.edn` | Static module registry (all 21 entries) |
| `libs/boundary-cli/resources/boundary/cli/templates/deps.edn.tmpl` | deps.edn template for new projects |
| `libs/boundary-cli/resources/boundary/cli/templates/bb.edn.tmpl` | bb.edn template for new projects |
| `libs/boundary-cli/resources/boundary/cli/templates/dev-config.edn.tmpl` | dev config.edn template |
| `libs/boundary-cli/resources/boundary/cli/templates/test-config.edn.tmpl` | test config.edn template |
| `libs/boundary-cli/resources/boundary/cli/templates/system.clj.tmpl` | system.clj template |
| `libs/boundary-cli/resources/boundary/cli/templates/AGENTS.md.tmpl` | AGENTS.md template with sentinel comments |
| `libs/boundary-cli/resources/boundary/cli/templates/CLAUDE.md.tmpl` | CLAUDE.md template |
| `libs/boundary-cli/resources/boundary/cli/templates/gitignore.tmpl` | .gitignore template |
| `libs/boundary-cli/test/boundary/cli/catalogue_test.clj` | Tests for catalogue loading and querying |
| `libs/boundary-cli/test/boundary/cli/list_modules_test.clj` | Tests for list output (table + JSON) |
| `libs/boundary-cli/test/boundary/cli/new_test.clj` | Tests for project generation |
| `libs/boundary-cli/test/boundary/cli/add_test.clj` | Tests for module wiring |
| `scripts/install.sh` | Bootstrap script: JVM → Clojure → bbin → boundary |

### Modified files

| File | Change |
|------|--------|
| `bb.edn` | Add `test:boundary-cli` task |
| `libs/tools/src/boundary/tools/deploy.clj` | Add `boundary-cli` to `all-libs`; patch catalogue after deploy |

---

## Task 1: Scaffold `libs/boundary-cli/` structure

**Files:**
- Create: `libs/boundary-cli/bb.edn`
- Create: `libs/boundary-cli/deps.edn`
- Create: `libs/boundary-cli/build.clj`
- Create: `libs/boundary-cli/src/boundary/cli/main.clj`
- Modify: `bb.edn` (add `test:boundary-cli` task)

- [ ] **Step 1: Create `libs/boundary-cli/deps.edn`**

```edn
{:paths ["src" "resources"]
 :deps  {org.babashka/babashka {:mvn/version "RELEASE"}
         cheshire/cheshire     {:mvn/version "5.12.0"}}
 :aliases
 {:test {:extra-paths ["test"]}}}
```

- [ ] **Step 2: Create `libs/boundary-cli/bb.edn`**

This is used by bbin to know the classpath and entrypoint. The `:bbin/bin` key tells bbin how to install the `boundary` command.

```edn
{:paths ["src" "resources"]
 :bbin/bin {boundary {:main-opts ["-m" "boundary.cli.main"]}}}
```

Note: verify `bbin install https://github.com/thijs-creemers/boundary --tag <tag> --git/root libs/boundary-cli --as boundary` works during implementation. If `--git/root` is not supported by the installed bbin version, the alternative is to add a top-level `bin/boundary.clj` wrapper script. Adjust `scripts/install.sh` accordingly.

- [ ] **Step 3: Create `libs/boundary-cli/build.clj`**

Follow the same pattern as `libs/tools/build.clj`:

```clojure
(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'org.boundary-app/boundary-cli)
(def version "1.0.0-alpha-1")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_] (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir :lib lib :version version :basis basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/thijs-creemers/boundary"
                      :connection "scm:git:git://github.com/thijs-creemers/boundary.git"
                      :developerConnection "scm:git:ssh://git@github.com/thijs-creemers/boundary.git"
                      :tag (str "v" version)}
                :pom-data [[:description "boundary CLI — project generator and module installer"]
                           [:url "https://github.com/thijs-creemers/boundary"]
                           [:licenses [:license
                                       [:name "Eclipse Public License 2.0"]
                                       [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file}))

(defn deploy [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact jar-file
    :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
```

- [ ] **Step 4: Create minimal `libs/boundary-cli/src/boundary/cli/main.clj`**

```clojure
(ns boundary.cli.main
  (:require [clojure.string :as str]))

(defn- usage []
  (println "boundary — Boundary Framework project tool")
  (println)
  (println "Commands:")
  (println "  boundary new <project-name>       Create a new project")
  (println "  boundary add <module>             Add a module to the current project")
  (println "  boundary list modules             List available modules")
  (println "  boundary list modules --json      Machine-readable module list")
  (println "  boundary version                  Show CLI version"))

(defn -main [& args]
  (let [[cmd & rest-args] args]
    (case cmd
      "new"     (do (require 'boundary.cli.new)
                    ((resolve 'boundary.cli.new/-main) rest-args))
      "add"     (do (require 'boundary.cli.add)
                    ((resolve 'boundary.cli.add/-main) rest-args))
      "list"    (do (require 'boundary.cli.list-modules)
                    ((resolve 'boundary.cli.list-modules/-main) rest-args))
      "version" (println "boundary CLI version 1.0.0-alpha-1")
      (do (when cmd (println (str "Unknown command: " cmd "\n")))
          (usage)
          (System/exit (if cmd 1 0))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
```

- [ ] **Step 5: Add `test:boundary-cli` to the root `bb.edn`**

Find the `test:tools` task block in `bb.edn` and add a parallel entry after it:

```edn
test:boundary-cli
{:doc "Run boundary-cli tests"
 :extra-paths ["libs/boundary-cli/src"
               "libs/boundary-cli/resources"
               "libs/boundary-cli/test"]
 :task (do (require 'boundary.cli.catalogue-test
                    'boundary.cli.list-modules-test
                    'boundary.cli.new-test
                    'boundary.cli.add-test)
           (let [summary (clojure.test/run-tests
                           'boundary.cli.catalogue-test
                           'boundary.cli.list-modules-test
                           'boundary.cli.new-test
                           'boundary.cli.add-test)]
             (when (pos? (+ (:fail summary) (:error summary)))
               (System/exit 1))))}
```

- [ ] **Step 6: Verify the scaffold runs**

```bash
cd libs/boundary-cli
bb -cp src:resources -m boundary.cli.main
```

Expected output: the usage text with command list.

- [ ] **Step 7: Commit**

```bash
git add libs/boundary-cli/ bb.edn
git commit -m "feat: scaffold libs/boundary-cli structure"
```

---

## Task 2: Module catalogue

**Files:**
- Create: `libs/boundary-cli/resources/boundary/cli/modules-catalogue.edn`
- Create: `libs/boundary-cli/src/boundary/cli/catalogue.clj`
- Create: `libs/boundary-cli/test/boundary/cli/catalogue_test.clj`

- [ ] **Step 1: Write the failing tests first**

Create `libs/boundary-cli/test/boundary/cli/catalogue_test.clj`:

```clojure
(ns boundary.cli.catalogue-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.cli.catalogue :as cat]))

(deftest load-catalogue-test
  (testing "catalogue loads without error"
    (let [c (cat/load-catalogue)]
      (is (map? c))
      (is (contains? c :modules))
      (is (seq (:modules c)))))

  (testing "every module has required fields"
    (doseq [m (:modules (cat/load-catalogue))]
      (is (string? (:name m))       (str "missing :name in " m))
      (is (string? (:description m)) (str "missing :description in " m))
      (is (keyword? (:category m))  (str "missing :category in " m))
      (is (string? (:version m))    (str "missing :version in " m))
      (is (string? (:add-command m)) (str "missing :add-command in " m))
      (is (string? (:config-snippet m)) (str "missing :config-snippet in " m))
      (is (string? (:test-config-snippet m)) (str "missing :test-config-snippet in " m))
      (is (string? (:docs-url m))   (str "missing :docs-url in " m)))))

(deftest find-module-test
  (testing "finds a module by name"
    (let [m (cat/find-module "payments")]
      (is (= "payments" (:name m)))))

  (testing "returns nil for unknown module"
    (is (nil? (cat/find-module "does-not-exist"))))

  (testing "core modules are present"
    (doseq [core-name ["core" "observability" "platform" "user"]]
      (is (cat/find-module core-name) (str "core module missing: " core-name))))

  (testing "optional modules include payments and storage"
    (is (cat/find-module "payments"))
    (is (cat/find-module "storage"))))

(deftest optional-modules-test
  (testing "optional-modules returns only :optional category"
    (let [opts (cat/optional-modules)]
      (is (every? #(= :optional (:category %)) opts))
      (is (seq opts)))))
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
bb -cp libs/boundary-cli/src:libs/boundary-cli/resources:libs/boundary-cli/test \
   -e "(require 'boundary.cli.catalogue-test 'clojure.test) (clojure.test/run-tests 'boundary.cli.catalogue-test)"
```

Expected: errors about `boundary.cli.catalogue` not found.

- [ ] **Step 3: Create `libs/boundary-cli/resources/boundary/cli/modules-catalogue.edn`**

```edn
{:cli-version       "1.0.0-alpha-1"
 :catalogue-version "1.0.1-alpha-14"
 :modules
 [;; ─── Core (always included) ───────────────────────────────────────────────
  {:name                "core"
   :description         "Pure validation, case conversion, interceptor pipeline, feature flags"
   :clojars             org.boundary-app/boundary-core
   :version             "1.0.1-alpha-14"
   :category            :core
   :config-snippet      ""
   :test-config-snippet ""
   :add-command         "boundary add core"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/core/AGENTS.md"}

  {:name                "observability"
   :description         "Interceptor-based metrics, logging, and error reporting"
   :clojars             org.boundary-app/boundary-observability
   :version             "1.0.1-alpha-14"
   :category            :core
   :config-snippet      "  :boundary/metrics\n  {:provider :no-op}\n\n  :boundary/error-reporting\n  {:provider :no-op}\n"
   :test-config-snippet "  :boundary/metrics\n  {:provider :no-op}\n\n  :boundary/error-reporting\n  {:provider :no-op}\n"
   :add-command         "boundary add observability"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/observability/AGENTS.md"}

  {:name                "platform"
   :description         "HTTP server, Reitit router, Ring middleware pipeline"
   :clojars             org.boundary-app/boundary-platform
   :version             "1.0.1-alpha-14"
   :category            :core
   :config-snippet      "  :boundary/http\n  {:port #or [#env HTTP_PORT 3000] :host \"0.0.0.0\" :join? false}\n\n  :boundary/router\n  {:adapter :reitit :coercion :malli :middleware []}\n"
   :test-config-snippet ""
   :add-command         "boundary add platform"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/platform/AGENTS.md"}

  {:name                "user"
   :description         "Authentication, JWT, MFA, user management"
   :clojars             org.boundary-app/boundary-user
   :version             "1.0.1-alpha-14"
   :category            :core
   :config-snippet      ""
   :test-config-snippet ""
   :add-command         "boundary add user"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/user/AGENTS.md"}

  ;; ─── Optional ─────────────────────────────────────────────────────────────
  {:name                "payments"
   :description         "PSP abstraction — Mollie, Stripe, Mock checkout and webhook verification"
   :clojars             org.boundary-app/boundary-payments
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/payment-provider\n  {:provider :mock}\n"
   :test-config-snippet "  :boundary/payment-provider\n  {:provider :mock}\n"
   :add-command         "boundary add payments"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/payments/AGENTS.md"}

  {:name                "storage"
   :description         "File storage — local filesystem and S3, image processing"
   :clojars             org.boundary-app/boundary-storage
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/storage\n  {:provider :local :root \"uploads\"}\n"
   :test-config-snippet "  :boundary/storage\n  {:provider :local :root \"/tmp/boundary-test-uploads\"}\n"
   :add-command         "boundary add storage"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/storage/AGENTS.md"}

  {:name                "jobs"
   :description         "Background job processing with retry logic"
   :clojars             org.boundary-app/boundary-jobs
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/jobs\n  {:provider :in-memory}\n"
   :test-config-snippet "  :boundary/jobs\n  {:provider :in-memory}\n"
   :add-command         "boundary add jobs"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/jobs/AGENTS.md"}

  {:name                "email"
   :description         "SMTP email sending, async and queued modes"
   :clojars             org.boundary-app/boundary-email
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary.external/smtp\n  {:host #or [#env SMTP_HOST \"localhost\"] :port 1025 :tls? false :from \"no-reply@localhost\"}\n"
   :test-config-snippet ""
   :add-command         "boundary add email"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/email/AGENTS.md"}

  {:name                "cache"
   :description         "Distributed caching — Redis or in-memory, TTL, atomic ops"
   :clojars             org.boundary-app/boundary-cache
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/cache\n  {:provider :in-memory :default-ttl 300}\n"
   :test-config-snippet "  :boundary/cache\n  {:provider :in-memory :default-ttl 300}\n"
   :add-command         "boundary add cache"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/cache/AGENTS.md"}

  {:name                "search"
   :description         "Full-text search"
   :clojars             org.boundary-app/boundary-search
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/search\n  {:provider :in-memory}\n"
   :test-config-snippet "  :boundary/search\n  {:provider :in-memory}\n"
   :add-command         "boundary add search"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/search/AGENTS.md"}

  {:name                "realtime"
   :description         "WebSocket pub/sub messaging"
   :clojars             org.boundary-app/boundary-realtime
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/realtime\n  {:provider :in-memory}\n"
   :test-config-snippet "  :boundary/realtime\n  {:provider :in-memory}\n"
   :add-command         "boundary add realtime"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/realtime/AGENTS.md"}

  {:name                "tenant"
   :description         "Multi-tenancy with schema-per-tenant isolation"
   :clojars             org.boundary-app/boundary-tenant
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/tenant\n  {:strategy :schema-per-tenant}\n"
   :test-config-snippet "  :boundary/tenant\n  {:strategy :schema-per-tenant}\n"
   :add-command         "boundary add tenant"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/tenant/AGENTS.md"}

  {:name                "ai"
   :description         "Multi-provider AI — Ollama, Anthropic Claude, OpenAI"
   :clojars             org.boundary-app/boundary-ai
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/ai-service\n  {:provider :ollama :model #or [#env AI_MODEL \"qwen2.5-coder:7b\"] :base-url #or [#env OLLAMA_URL \"http://localhost:11434\"]}\n"
   :test-config-snippet "  :boundary/ai-service\n  {:provider :no-op}\n"
   :add-command         "boundary add ai"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/ai/AGENTS.md"}

  {:name                "external"
   :description         "External service adapters — Twilio, SMTP, IMAP"
   :clojars             org.boundary-app/boundary-external
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      ""
   :test-config-snippet ""
   :add-command         "boundary add external"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/external/AGENTS.md"}

  {:name                "workflow"
   :description         "Workflow orchestration with state machines"
   :clojars             org.boundary-app/boundary-workflow
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/workflow\n  {:provider :in-memory}\n"
   :test-config-snippet "  :boundary/workflow\n  {:provider :in-memory}\n"
   :add-command         "boundary add workflow"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/workflow/AGENTS.md"}

  {:name                "reports"
   :description         "PDF/CSV export and scheduled report generation"
   :clojars             org.boundary-app/boundary-reports
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/reports\n  {:provider :in-memory}\n"
   :test-config-snippet "  :boundary/reports\n  {:provider :in-memory}\n"
   :add-command         "boundary add reports"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/reports/AGENTS.md"}

  {:name                "calendar"
   :description         "iCal, RRULE recurrence, conflict detection, Hiccup UI"
   :clojars             org.boundary-app/boundary-calendar
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/calendar\n  {:provider :in-memory}\n"
   :test-config-snippet "  :boundary/calendar\n  {:provider :in-memory}\n"
   :add-command         "boundary add calendar"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/calendar/AGENTS.md"}

  {:name                "geo"
   :description         "Multi-provider geocoding (OSM/Google/Mapbox), Haversine distance"
   :clojars             org.boundary-app/boundary-geo
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/geo\n  {:provider :osm}\n"
   :test-config-snippet "  :boundary/geo\n  {:provider :no-op}\n"
   :add-command         "boundary add geo"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/geo/AGENTS.md"}

  {:name                "i18n"
   :description         "Marker-based i18n, translation catalogues, locale chains"
   :clojars             org.boundary-app/boundary-i18n
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/i18n\n  {:default-locale :en}\n"
   :test-config-snippet "  :boundary/i18n\n  {:default-locale :en}\n"
   :add-command         "boundary add i18n"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/i18n/AGENTS.md"}

  {:name                "admin"
   :description         "Admin UI with entity config, HTMX forms"
   :clojars             org.boundary-app/boundary-admin
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/admin\n  {:enabled? true :base-path \"/web/admin\" :require-role :admin}\n"
   :test-config-snippet "  :boundary/admin\n  {:enabled? true :base-path \"/web/admin\" :require-role :admin}\n"
   :add-command         "boundary add admin"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/admin/AGENTS.md"}

  {:name                "ui-style"
   :description         "Shared CSS/JS style bundles — :base, :pilot, :admin-pilot"
   :clojars             org.boundary-app/boundary-ui-style
   :version             "1.0.1-alpha-14"
   :category            :optional
   :config-snippet      "  :boundary/ui-style\n  {:bundle :base}\n"
   :test-config-snippet ""
   :add-command         "boundary add ui-style"
   :docs-url            "https://github.com/thijs-creemers/boundary/blob/main/libs/ui-style/AGENTS.md"}]}
```

- [ ] **Step 4: Create `libs/boundary-cli/src/boundary/cli/catalogue.clj`**

```clojure
(ns boundary.cli.catalogue
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def ^:private catalogue-path "boundary/cli/modules-catalogue.edn")

(defn load-catalogue
  "Load the bundled modules-catalogue.edn. Throws if not found."
  []
  (let [r (io/resource catalogue-path)]
    (when-not r
      (throw (ex-info "modules-catalogue.edn not found on classpath"
                      {:path catalogue-path})))
    (edn/read-string (slurp r))))

(defn find-module
  "Find a module by name string. Returns the module map or nil."
  [name]
  (first (filter #(= name (:name %)) (:modules (load-catalogue)))))

(defn optional-modules
  "Return all modules with :category :optional."
  []
  (filter #(= :optional (:category %)) (:modules (load-catalogue))))

(defn core-modules
  "Return all modules with :category :core."
  []
  (filter #(= :core (:category %)) (:modules (load-catalogue))))

(defn validate-catalogue!
  "Validate all entries have required fields. Throws on first violation."
  []
  (let [required [:name :description :category :version :clojars
                  :config-snippet :test-config-snippet :add-command :docs-url]]
    (doseq [m (:modules (load-catalogue))
            field required]
      (when-not (contains? m field)
        (throw (ex-info (str "Catalogue entry missing field: " field)
                        {:module (:name m) :field field}))))))
```

- [ ] **Step 5: Run the catalogue tests**

```bash
bb test:boundary-cli
```

Expected: all catalogue tests pass.

- [ ] **Step 6: Commit**

```bash
git add libs/boundary-cli/
git commit -m "feat: add module catalogue and catalogue.clj"
```

---

## Task 3: `boundary list modules`

**Files:**
- Create: `libs/boundary-cli/src/boundary/cli/list_modules.clj`
- Create: `libs/boundary-cli/test/boundary/cli/list_modules_test.clj`

- [ ] **Step 1: Write the failing tests**

Create `libs/boundary-cli/test/boundary/cli/list_modules_test.clj`:

```clojure
(ns boundary.cli.list-modules-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [boundary.cli.list-modules :as lm]))

(deftest human-table-test
  (testing "table output contains module names"
    (let [out (with-out-str (lm/print-table))]
      (is (str/includes? out "payments"))
      (is (str/includes? out "storage"))
      (is (str/includes? out "boundary add payments"))))

  (testing "table output contains header"
    (let [out (with-out-str (lm/print-table))]
      (is (str/includes? out "Module"))
      (is (str/includes? out "Description")))))

(deftest json-output-test
  (testing "JSON output is valid JSON"
    (let [out (with-out-str (lm/print-json))
          parsed (json/parse-string out true)]
      (is (map? parsed))
      (is (contains? parsed :modules))
      (is (contains? parsed :cli-version))
      (is (contains? parsed :catalogue-version))))

  (testing "JSON modules include required fields"
    (let [out (with-out-str (lm/print-json))
          parsed (json/parse-string out true)
          payments (first (filter #(= "payments" (:name %)) (:modules parsed)))]
      (is payments)
      (is (= "optional" (:category payments)))
      (is (string? (:description payments)))
      (is (string? (:add-command payments)))
      (is (string? (:docs-url payments)))))

  (testing "JSON includes core modules"
    (let [out (with-out-str (lm/print-json))
          parsed (json/parse-string out true)
          names (set (map :name (:modules parsed)))]
      (is (contains? names "core"))
      (is (contains? names "platform")))))
```

- [ ] **Step 2: Run to confirm failure**

```bash
bb test:boundary-cli
```

Expected: errors about `boundary.cli.list-modules` not found.

- [ ] **Step 3: Implement `list_modules.clj`**

```clojure
(ns boundary.cli.list-modules
  (:require [boundary.cli.catalogue :as cat]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- pad [s width]
  (let [s (str s)]
    (if (>= (count s) width)
      (subs s 0 width)
      (str s (apply str (repeat (- width (count s)) " "))))))

(defn print-table []
  (let [modules (cat/optional-modules)
        fmt     "  %-12s  %-50s  %s"]
    (println)
    (println (format fmt "Module" "Description" "Command"))
    (println (format fmt (apply str (repeat 12 "-"))
                          (apply str (repeat 50 "-"))
                          (apply str (repeat 28 "-"))))
    (doseq [{:keys [name description add-command]} modules]
      (println (format fmt (pad name 12) (pad description 50) add-command)))
    (println)))

(defn print-json []
  (let [cat      (cat/load-catalogue)
        modules  (map (fn [m]
                        {:name             (:name m)
                         :description      (:description m)
                         :clojars          (str (:clojars m))
                         :version          (:version m)
                         :category         (name (:category m))
                         :add-command      (:add-command m)
                         :docs-url         (:docs-url m)})
                      (:modules cat))]
    (println (json/generate-string
               {:cli-version       (:cli-version cat)
                :catalogue-version (:catalogue-version cat)
                :modules           modules}
               {:pretty true}))))

(defn -main [[sub & _]]
  (if (= sub "--json")
    (print-json)
    (print-table)))
```

- [ ] **Step 4: Run tests**

```bash
bb test:boundary-cli
```

Expected: all list-modules tests pass.

- [ ] **Step 5: Smoke test the command manually**

```bash
bb -cp libs/boundary-cli/src:libs/boundary-cli/resources:libs/boundary-cli/test \
   -m boundary.cli.main list modules
bb -cp libs/boundary-cli/src:libs/boundary-cli/resources:libs/boundary-cli/test \
   -m boundary.cli.main list modules --json
```

Expected: formatted table, then valid JSON.

- [ ] **Step 6: Commit**

```bash
git add libs/boundary-cli/src/boundary/cli/list_modules.clj \
        libs/boundary-cli/test/boundary/cli/list_modules_test.clj
git commit -m "feat: implement boundary list modules"
```

---

## Task 4: Project templates

**Files:**
- Create: all files under `libs/boundary-cli/resources/boundary/cli/templates/`

Templates use `{{placeholder}}` substitution. Placeholders:
- `{{project-name}}` — e.g. `my-app`
- `{{project-ns}}` — e.g. `my_app` (hyphens replaced with underscores)
- `{{boundary-version}}` — e.g. `1.0.1-alpha-14`

- [ ] **Step 1: Create `deps.edn.tmpl`**

```edn
{:paths ["src" "resources"]
 :deps  {org.boundary-app/boundary-core         {:mvn/version "{{boundary-version}}"}
         org.boundary-app/boundary-observability {:mvn/version "{{boundary-version}}"}
         org.boundary-app/boundary-platform      {:mvn/version "{{boundary-version}}"}
         org.boundary-app/boundary-user          {:mvn/version "{{boundary-version}}"}}
 :aliases
 {:repl-clj {:extra-deps {nrepl/nrepl {:mvn/version "1.3.0"}
                          cider/cider-nrepl {:mvn/version "0.50.2"}}
             :main-opts  ["-m" "nrepl.cmdline" "--middleware" "[cider.nrepl/cider-middleware]"
                          "--port" "7888"]}
  :test     {:extra-paths ["test"]
             :main-opts   ["-m" "kaocha.runner"]}
  :migrate  {:main-opts ["-m" "boundary.platform.shell.migrate"]}}}
```

- [ ] **Step 2: Create `bb.edn.tmpl`**

```edn
{:tasks
 {migrate {:doc "Run database migrations"
           :task (clojure ["-M:migrate"] *command-line-args*)}
  repl    {:doc "Start REPL"
           :task (clojure ["-M:repl-clj"])}
  test    {:doc "Run tests"
           :task (clojure ["-M:test"])}}}
```

- [ ] **Step 3: Create `dev-config.edn.tmpl`**

```edn
{;; {{project-name}} — dev configuration
 :active
 {  :boundary/settings
    {:name             "{{project-name}}-dev"
     :version          "0.1.0"
     :date-format      "yyyy-MM-dd"
     :date-time-format "yyyy-MM-dd HH:mm:ss"
     :features         {}}

   :boundary/h2
   {:memory true
    :pool   {:minimum-idle 1 :maximum-pool-size 10}}

   :boundary/http
   {:port       #or [#env HTTP_PORT 3000]
    :host       #or [#env HTTP_HOST "0.0.0.0"]
    :join?      false
    :port-range {:start 3000 :end 3099}}

   :boundary/router
   {:adapter :reitit :coercion :malli :middleware []}

   :boundary/logging
   {:level :debug :console true}}

 :inactive
 {}}
```

- [ ] **Step 4: Create `test-config.edn.tmpl`**

```edn
{;; {{project-name}} — test configuration
 :active
 {  :boundary/settings
    {:name             "{{project-name}}-test"
     :version          "0.1.0"
     :date-format      "yyyy-MM-dd"
     :date-time-format "yyyy-MM-dd HH:mm:ss"
     :features         {}}

   :boundary/h2
   {:memory true
    :pool   {:minimum-idle 1 :maximum-pool-size 5}}

   :boundary/logging
   {:level :warn :console true}}

 :inactive
 {}}
```

- [ ] **Step 5: Create `system.clj.tmpl`**

```clojure
(ns {{project-ns}}.system
  (:require [integrant.core :as ig]))

;; System configuration is loaded from resources/conf/{env}/config.edn
;; via Aero. Add module configs with `boundary add <module>`.

(defmethod ig/init-key :boundary/settings [_ config] config)
```

- [ ] **Step 6: Create `CLAUDE.md.tmpl`**

```markdown
# {{project-name}}

Built with the [Boundary Framework](https://github.com/thijs-creemers/boundary) (Clojure, FC/IS architecture).

See AGENTS.md for available modules, dev commands, and architecture conventions.

To discover available boundary modules in machine-readable form:
  boundary list modules --json
```

- [ ] **Step 7: Create `AGENTS.md.tmpl`**

```markdown
# {{project-name}} — Developer Reference

Built with the Boundary Framework. Follows the Functional Core / Imperative Shell (FC/IS) pattern.

## Essential Commands

```bash
clojure -M:repl-clj          # Start REPL (nREPL on port 7888)
bb migrate up                 # Run database migrations
clojure -M:test               # Run all tests
boundary add <module>         # Add a boundary module
boundary list modules --json  # Machine-readable module catalogue
```

## Architecture

- `src/{{project-ns}}/core/`  — Pure functions, no side effects
- `src/{{project-ns}}/shell/` — I/O, HTTP, database
- `resources/conf/dev/`       — Dev configuration (Aero + Integrant)
- `resources/conf/test/`      — Test configuration

## Boundary Modules

Core modules are pre-installed. Add optional modules with:

  boundary add <module>
  boundary list modules --json    ← machine-readable catalogue for AI tools

<!-- boundary:available-modules -->
| Module     | Description                                   | Command                     |
|------------|-----------------------------------------------|-----------------------------|
| payments   | PSP abstraction — Mollie, Stripe, Mock        | boundary add payments       |
| storage    | File storage, local/S3, image processing      | boundary add storage        |
| jobs       | Background job processing, retry logic        | boundary add jobs           |
| email      | SMTP sending, async/queued                    | boundary add email          |
| cache      | Redis or in-memory caching                    | boundary add cache          |
| search     | Full-text search                              | boundary add search         |
| realtime   | WebSocket pub/sub                             | boundary add realtime       |
| tenant     | Multi-tenancy, schema-per-tenant              | boundary add tenant         |
| ai         | Multi-provider AI (Ollama/Claude/OpenAI)      | boundary add ai             |
| external   | Twilio, SMTP, IMAP adapters                   | boundary add external       |
| workflow   | Workflow orchestration                        | boundary add workflow       |
| reports    | PDF/CSV export, scheduling                    | boundary add reports        |
| calendar   | iCal, RRULE, conflict detection               | boundary add calendar       |
| geo        | Geocoding (OSM/Google/Mapbox), Haversine      | boundary add geo            |
| i18n       | Marker-based i18n, translation catalogues     | boundary add i18n           |
| admin      | Admin UI, entity config, HTMX forms           | boundary add admin          |
| ui-style   | Shared CSS/JS style bundles                   | boundary add ui-style       |
<!-- /boundary:available-modules -->

<!-- boundary:installed-modules -->
## Installed Modules

- core (`org.boundary-app/boundary-core`) — [docs](https://github.com/thijs-creemers/boundary/blob/main/libs/core/AGENTS.md)
- observability (`org.boundary-app/boundary-observability`) — [docs](https://github.com/thijs-creemers/boundary/blob/main/libs/observability/AGENTS.md)
- platform (`org.boundary-app/boundary-platform`) — [docs](https://github.com/thijs-creemers/boundary/blob/main/libs/platform/AGENTS.md)
- user (`org.boundary-app/boundary-user`) — [docs](https://github.com/thijs-creemers/boundary/blob/main/libs/user/AGENTS.md)
<!-- /boundary:installed-modules -->
```

- [ ] **Step 8: Create `gitignore.tmpl`**

```
.env
target/
.cpcache/
*.db
uploads/
logs/
```

- [ ] **Step 9: Commit templates**

```bash
git add libs/boundary-cli/resources/boundary/cli/templates/
git commit -m "feat: add project templates"
```

---

## Task 5: `boundary new`

**Files:**
- Create: `libs/boundary-cli/src/boundary/cli/new.clj`
- Create: `libs/boundary-cli/test/boundary/cli/new_test.clj`

- [ ] **Step 1: Write the failing tests**

Create `libs/boundary-cli/test/boundary/cli/new_test.clj`:

```clojure
(ns boundary.cli.new-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.cli.new :as new]))

(deftest validate-name-test
  (testing "valid kebab-case names are accepted"
    (is (nil? (new/validate-name "my-app")))
    (is (nil? (new/validate-name "myapp")))
    (is (nil? (new/validate-name "my-app-2"))))

  (testing "invalid names return an error string"
    (is (string? (new/validate-name "My-App")))    ; uppercase
    (is (string? (new/validate-name "123app")))    ; starts with digit
    (is (string? (new/validate-name "my.app")))    ; dot
    (is (string? (new/validate-name "")))           ; empty
    (is (string? (new/validate-name "my_app")))))  ; underscore not allowed in project name

(deftest name->ns-test
  (testing "converts hyphens to underscores"
    (is (= "my_app" (new/name->ns "my-app")))
    (is (= "myapp" (new/name->ns "myapp")))
    (is (= "my_long_name" (new/name->ns "my-long-name")))))

(deftest generate-project-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-test-" (System/currentTimeMillis))]
    (try
      (testing "creates project directory"
        (new/generate! tmp "test-proj" {})
        (is (.exists (io/file tmp))))

      (testing "generates required files"
        (doseq [f ["deps.edn" "bb.edn" ".gitignore" "CLAUDE.md" "AGENTS.md"
                   "resources/conf/dev/config.edn"
                   "resources/conf/test/config.edn"]]
          (is (.exists (io/file tmp f)) (str "Missing: " f))))

      (testing "substitutes project name in CLAUDE.md"
        (let [content (slurp (io/file tmp "CLAUDE.md"))]
          (is (str/includes? content "test-proj"))
          (is (not (str/includes? content "{{project-name}}")))))

      (testing "sentinel comments are present in AGENTS.md"
        (let [content (slurp (io/file tmp "AGENTS.md"))]
          (is (str/includes? content "<!-- boundary:available-modules -->"))
          (is (str/includes? content "<!-- /boundary:available-modules -->"))
          (is (str/includes? content "<!-- boundary:installed-modules -->"))
          (is (str/includes? content "<!-- /boundary:installed-modules -->"))))
      (finally
        ;; cleanup
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))

(deftest directory-exists-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-exists-test")]
    (io/make-parents (io/file tmp "dummy.txt"))
    (spit (io/file tmp "dummy.txt") "x")
    (try
      (testing "non-empty directory without --force exits with error"
        (let [result (new/check-directory tmp false)]
          (is (= :non-empty result))))

      (testing "--force allows non-empty directory"
        (let [result (new/check-directory tmp true)]
          (is (= :ok result))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))
```

- [ ] **Step 2: Run to confirm failure**

```bash
bb test:boundary-cli
```

Expected: errors about `boundary.cli.new` not found.

- [ ] **Step 3: Implement `new.clj`**

```clojure
(ns boundary.cli.new
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.cli.catalogue :as cat]))

(defn validate-name [n]
  (cond
    (str/blank? n)                       "Project name cannot be empty"
    (not (re-matches #"[a-z][a-z0-9-]*" n)) "Project name must be kebab-case (lowercase letters, digits, hyphens; must start with a letter)"
    :else nil))

(defn name->ns [n]
  (str/replace n "-" "_"))

(defn- render [template substitutions]
  (reduce (fn [s [k v]] (str/replace s (str "{{" (name k) "}}") v))
          template
          substitutions))

(defn- template-path [name]
  (str "boundary/cli/templates/" name))

(defn- read-template [name]
  (let [r (io/resource (template-path name))]
    (when-not r
      (throw (ex-info (str "Template not found: " name) {:name name})))
    (slurp r)))

(defn- write-file! [dir relative-path content]
  (let [f (io/file dir relative-path)]
    (io/make-parents f)
    (spit f content)))

(defn check-directory
  "Returns :ok, :empty-exists (needs confirm), or :non-empty.
   If force? is true, always returns :ok."
  [dir force?]
  (let [f (io/file dir)]
    (cond
      force?                                   :ok
      (not (.exists f))                        :ok
      (empty? (.list f))                       :empty-exists
      :else                                    :non-empty)))

(defn generate!
  "Generate project files into dir. substitutions overrides template defaults."
  [dir project-name _opts]
  (let [project-ns (name->ns project-name)
        cat        (cat/load-catalogue)
        version    (:catalogue-version cat)
        subs       {:project-name    project-name
                    :project-ns      project-ns
                    :boundary-version version}
        files      {"deps.edn"                        "deps.edn.tmpl"
                    "bb.edn"                          "bb.edn.tmpl"
                    ".gitignore"                      "gitignore.tmpl"
                    "CLAUDE.md"                       "CLAUDE.md.tmpl"
                    "AGENTS.md"                       "AGENTS.md.tmpl"
                    "resources/conf/dev/config.edn"   "dev-config.edn.tmpl"
                    "resources/conf/test/config.edn"  "test-config.edn.tmpl"
                    (str "src/" project-ns "/system.clj") "system.clj.tmpl"}]
    (doseq [[target tmpl] files]
      (write-file! dir target (render (read-template tmpl) subs)))))

(defn -main [[project-name & flags]]
  (let [force? (boolean (some #{"--force"} flags))]
    (when-not project-name
      (println "Usage: boundary new <project-name> [--force]")
      (System/exit 1))
    (let [err (validate-name project-name)]
      (when err
        (println (str "Error: " err))
        (System/exit 1)))
    (let [dir    (str (System/getProperty "user.dir") "/" project-name)
          status (check-directory dir force?)]
      (case status
        :non-empty
        (do (println (str "Error: Directory " project-name "/ already exists and is not empty."))
            (println "Use a different name, remove the directory, or pass --force.")
            (System/exit 1))
        :empty-exists
        (do (print (str "Directory " project-name "/ exists but is empty. Populate it? [Y/n]: "))
            (flush)
            (let [input (str/lower-case (str/trim (or (read-line) "")))]
              (when (= input "n")
                (println "Aborted.")
                (System/exit 0))))
        :ok nil)
      (println (str "Creating " project-name "/..."))
      (generate! dir project-name {})
      (println (str "\n✓ Project created: " project-name "/"))
      (println "\nCore modules installed: core, observability, platform, user")
      (println "\nOptional modules available — add any with:\n")
      (doseq [{:keys [name description add-command]} (take 6 (cat/optional-modules))]
        (println (format "  %-25s %s" add-command description)))
      (println "  ... (boundary list modules for full list)")
      (println (str "\nNext:\n  cd " project-name "\n  boundary add <module>    (optional)\n  clojure -M:repl-clj")))))
```

- [ ] **Step 4: Run tests**

```bash
bb test:boundary-cli
```

Expected: all new tests pass.

- [ ] **Step 5: Smoke test**

```bash
cd /tmp
bb -cp /path/to/repo/libs/boundary-cli/src:/path/to/repo/libs/boundary-cli/resources \
   -m boundary.cli.main new my-test-app
ls my-test-app/
cat my-test-app/AGENTS.md
rm -rf my-test-app
```

Expected: project directory with all files, AGENTS.md with sentinel comments.

- [ ] **Step 6: Commit**

```bash
git add libs/boundary-cli/src/boundary/cli/new.clj \
        libs/boundary-cli/test/boundary/cli/new_test.clj
git commit -m "feat: implement boundary new"
```

---

## Task 6: `boundary add`

**Files:**
- Create: `libs/boundary-cli/src/boundary/cli/add.clj`
- Create: `libs/boundary-cli/test/boundary/cli/add_test.clj`

- [ ] **Step 1: Write the failing tests**

Create `libs/boundary-cli/test/boundary/cli/add_test.clj`:

```clojure
(ns boundary.cli.add-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.cli.add :as add]))

(defn- make-boundary-project! [dir]
  "Create a minimal boundary project structure in dir for testing."
  (io/make-parents (io/file dir "resources/conf/dev/config.edn"))
  (io/make-parents (io/file dir "resources/conf/test/config.edn"))
  (spit (io/file dir "deps.edn")
        "{:deps {org.boundary-app/boundary-core {:mvn/version \"1.0.0\"}}}")
  (spit (io/file dir "resources/conf/dev/config.edn")
        "{\n :active\n {\n }\n\n :inactive\n {}\n}")
  (spit (io/file dir "resources/conf/test/config.edn")
        "{\n :active\n {\n }\n\n :inactive\n {}\n}")
  (spit (io/file dir "AGENTS.md")
        "# Test\n<!-- boundary:available-modules -->\n| payments | desc | boundary add payments |\n<!-- /boundary:available-modules -->\n<!-- boundary:installed-modules -->\n- core\n<!-- /boundary:installed-modules -->\n"))

(deftest boundary-project-detection-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-add-detect-" (System/currentTimeMillis))]
    (try
      (testing "detects a boundary project by deps.edn content"
        (make-boundary-project! tmp)
        (is (add/boundary-project? tmp)))

      (testing "returns false for non-boundary project"
        (let [other (str tmp "-other")]
          (io/make-parents (io/file other "deps.edn"))
          (spit (io/file other "deps.edn") "{:deps {}}")
          (is (not (add/boundary-project? other)))
          (doseq [f (reverse (file-seq (io/file other)))] (.delete f))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest patch-deps-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-add-deps-" (System/currentTimeMillis))]
    (try
      (make-boundary-project! tmp)
      (testing "adds module coordinate to deps.edn"
        (add/patch-deps! tmp {:clojars 'org.boundary-app/boundary-payments :version "1.0.0"})
        (let [content (slurp (io/file tmp "deps.edn"))]
          (is (str/includes? content "boundary-payments"))))

      (testing "is idempotent — does not duplicate if already present"
        (add/patch-deps! tmp {:clojars 'org.boundary-app/boundary-payments :version "1.0.0"})
        (let [content (slurp (io/file tmp "deps.edn"))]
          (is (= 1 (count (re-seq #"boundary-payments" content))))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest patch-config-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-add-cfg-" (System/currentTimeMillis))]
    (try
      (make-boundary-project! tmp)
      (testing "injects config-snippet into dev config"
        (add/patch-config! tmp "resources/conf/dev/config.edn"
                           "  :boundary/payment-provider\n  {:provider :mock}\n")
        (let [content (slurp (io/file tmp "resources/conf/dev/config.edn"))]
          (is (str/includes? content ":boundary/payment-provider"))))

      (testing "does not inject if key already present"
        (let [before (slurp (io/file tmp "resources/conf/dev/config.edn"))]
          (add/patch-config! tmp "resources/conf/dev/config.edn"
                             "  :boundary/payment-provider\n  {:provider :mock}\n")
          (let [after (slurp (io/file tmp "resources/conf/dev/config.edn"))]
            (is (= (count (re-seq #":boundary/payment-provider" before))
                   (count (re-seq #":boundary/payment-provider" after)))))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))

(deftest patch-agents-md-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-add-agents-" (System/currentTimeMillis))]
    (try
      (make-boundary-project! tmp)
      (testing "removes module from available block"
        (add/patch-agents-md! tmp {:name "payments" :docs-url "http://example.com"})
        (let [content (slurp (io/file tmp "AGENTS.md"))]
          (is (not (str/includes? content "boundary add payments")))))

      (testing "adds module to installed block"
        (let [content (slurp (io/file tmp "AGENTS.md"))]
          (is (str/includes? content "payments"))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f))))))
```

- [ ] **Step 2: Run to confirm failure**

```bash
bb test:boundary-cli
```

Expected: errors about `boundary.cli.add` not found.

- [ ] **Step 3: Implement `add.clj`**

```clojure
(ns boundary.cli.add
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.cli.catalogue :as cat]))

;; ─── Project detection ────────────────────────────────────────────────────────

(defn boundary-project?
  "True if dir contains a deps.edn with at least one org.boundary-app coordinate."
  [dir]
  (let [f (io/file dir "deps.edn")]
    (and (.exists f)
         (str/includes? (slurp f) "org.boundary-app"))))

;; ─── deps.edn patching ───────────────────────────────────────────────────────

(defn patch-deps!
  "Add clojars coordinate to deps.edn if not already present."
  [dir {:keys [clojars version]}]
  (let [f       (io/file dir "deps.edn")
        content (slurp f)
        coord   (str clojars)
        entry   (str "org.boundary-app/" (name (symbol clojars)))]
    (when-not (str/includes? content entry)
      ;; Insert before the closing } of :deps map
      (let [new-content (str/replace-first
                          content
                          #"(:deps\s*\{)"
                          (str "$1\n         " coord " {:mvn/version \"" version "\"}"))]
        (spit f new-content)))))

;; ─── config.edn patching ─────────────────────────────────────────────────────

(defn patch-config!
  "Inject snippet into :active section of config file if config-key not present.
   Uses the same brace-walking approach as quickstart.clj."
  [dir relative-path snippet]
  (when (seq snippet)
    (let [f       (io/file dir relative-path)
          content (slurp f)
          ;; Extract the config key from the snippet (first keyword on first line)
          config-key (second (re-find #":(\S+)" snippet))]
      (when-not (str/includes? content (str ":" config-key))
        ;; Find closing brace of :active section
        (let [active-idx (str/index-of content ":active")
              open-idx   (when active-idx (str/index-of content "{" (+ active-idx 7)))]
          (when open-idx
            (let [close-idx (loop [i (inc open-idx) d 1]
                              (cond
                                (>= i (count content)) nil
                                (zero? d) (dec i)
                                :else (let [c (nth content i)]
                                        (recur (inc i) (case c \{ (inc d) \} (dec d) d)))))]
              (when close-idx
                (spit f (str (subs content 0 close-idx)
                             "\n" snippet
                             (subs content close-idx)))))))))))

;; ─── AGENTS.md patching ──────────────────────────────────────────────────────

(defn patch-agents-md!
  "Remove module row from available block; add to installed block.
   Skips with warning if sentinel comments are missing."
  [dir {:keys [name docs-url]}]
  (let [f (io/file dir "AGENTS.md")]
    (when (.exists f)
      (let [content (slurp f)]
        (if-not (str/includes? content "<!-- boundary:available-modules -->")
          (println "  Warning: AGENTS.md sentinel comments not found — skipping AGENTS.md update")
          (let [;; Remove the table row for this module from available block
                row-pattern (re-pattern (str "(?m)^.*\\b" (java.util.regex.Pattern/quote name) "\\b.*boundary add " (java.util.regex.Pattern/quote name) ".*\\n?"))
                without-row (str/replace content row-pattern "")
                ;; Append to installed block
                install-line (str "- " name " — [docs](" docs-url ")\n")
                with-install  (str/replace without-row
                                           "<!-- /boundary:installed-modules -->"
                                           (str install-line "<!-- /boundary:installed-modules -->"))]
            (spit f with-install)))))))

;; ─── Main ────────────────────────────────────────────────────────────────────

(defn -main [[module-name & _]]
  (when-not module-name
    (println "Usage: boundary add <module>")
    (println "Run 'boundary list modules' to see available modules.")
    (System/exit 1))
  (let [dir (System/getProperty "user.dir")]
    (when-not (boundary-project? dir)
      (println "Error: No boundary project found in current directory.")
      (println "Run 'boundary new <name>' first, then cd into the project.")
      (System/exit 1))
    (let [module (cat/find-module module-name)]
      (when-not module
        (println (str "Error: Unknown module '" module-name "'."))
        (println "Available modules:")
        (doseq [m (cat/optional-modules)]
          (println (str "  " (:name m))))
        (System/exit 1))
      ;; Check if already installed
      (let [deps (slurp (io/file dir "deps.edn"))
            coord (str (:clojars module))]
        (if (str/includes? deps (name (symbol (:clojars module))))
          (println (str "Module '" module-name "' is already installed."))
          (do
            (println (str "Adding " module-name "..."))
            (patch-deps! dir module)
            (patch-config! dir "resources/conf/dev/config.edn" (:config-snippet module))
            (patch-config! dir "resources/conf/test/config.edn" (:test-config-snippet module))
            (patch-agents-md! dir module)
            (println (str "\n✓ " module-name " added"))
            (println (str "\nDocs: " (:docs-url module)))))))))
```

- [ ] **Step 4: Run tests**

```bash
bb test:boundary-cli
```

Expected: all add tests pass.

- [ ] **Step 5: Commit**

```bash
git add libs/boundary-cli/src/boundary/cli/add.clj \
        libs/boundary-cli/test/boundary/cli/add_test.clj
git commit -m "feat: implement boundary add"
```

---

## Task 7: Install script

**Files:**
- Create: `scripts/install.sh`

- [ ] **Step 1: Create `scripts/install.sh`**

```bash
#!/usr/bin/env bash
# Boundary Framework installer
# Usage: curl -fsSL https://get.boundary-app.org | sh
# Fallback: curl -fsSL https://raw.githubusercontent.com/thijs-creemers/boundary/main/scripts/install.sh | sh

set -euo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; DIM='\033[2m'; RESET='\033[0m'
ok()   { echo -e "${GREEN}✓${RESET} $1"; }
fail() { echo -e "${RED}✗${RESET} $1"; exit 1; }
info() { echo -e "${DIM}  $1${RESET}"; }

echo ""
echo "━━━ Boundary Framework Installer ━━━━━━━━━━━━━━━━━━━━━"
echo ""

# ── Detect OS ────────────────────────────────────────────────
if [[ "$OSTYPE" == "darwin"* ]]; then
  OS="macos"
elif grep -qi microsoft /proc/version 2>/dev/null; then
  OS="wsl"
elif [[ -f /etc/debian_version ]]; then
  OS="debian"
elif [[ -f /etc/arch-release ]]; then
  OS="arch"
else
  fail "Unsupported OS. Boundary supports macOS, Debian/Ubuntu, Arch, and WSL2.
  Windows users: install WSL2 first — https://learn.microsoft.com/en-us/windows/wsl/install"
fi
ok "Detected OS: $OS"

# ── JVM ──────────────────────────────────────────────────────
if java -version 2>/dev/null | grep -q "version"; then
  ok "JVM already installed"
else
  info "Installing JVM..."
  if [[ "$OS" == "macos" ]]; then
    brew install --cask temurin 2>/dev/null || fail "Failed to install JVM via brew"
  elif [[ "$OS" == "debian" || "$OS" == "wsl" ]]; then
    if ! command -v sdk &>/dev/null; then
      info "Installing sdkman..."
      curl -s "https://get.sdkman.io" | bash
      source "$HOME/.sdkman/bin/sdkman-init.sh"
    fi
    sdk install java || fail "Failed to install JVM via sdkman"
  elif [[ "$OS" == "arch" ]]; then
    sudo pacman -S --noconfirm jdk-openjdk || fail "Failed to install JVM via pacman"
  fi
  ok "JVM installed"
fi

# ── Clojure CLI ───────────────────────────────────────────────
if command -v clojure &>/dev/null; then
  ok "Clojure CLI already installed"
else
  info "Installing Clojure CLI..."
  if [[ "$OS" == "macos" ]]; then
    brew install clojure 2>/dev/null || fail "Failed to install Clojure via brew"
  else
    curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
    chmod +x linux-install.sh
    sudo ./linux-install.sh && rm linux-install.sh
  fi
  ok "Clojure CLI installed"
fi

# ── bbin ─────────────────────────────────────────────────────
if command -v bbin &>/dev/null; then
  ok "bbin already installed"
else
  info "Installing bbin..."
  curl -o- https://raw.githubusercontent.com/babashka/bbin/master/bbin | bash || fail "Failed to install bbin"
  ok "bbin installed"
fi

# ── PATH ─────────────────────────────────────────────────────
BBIN_BIN="$HOME/.babashka/bbin/bin"
SHELL_RC="$HOME/.zshrc"
[[ "$SHELL" == *"bash"* ]] && SHELL_RC="$HOME/.bashrc"

if ! echo "$PATH" | grep -q "$BBIN_BIN"; then
  echo "export PATH=\"$BBIN_BIN:\$PATH\"" >> "$SHELL_RC"
  ok "Added $BBIN_BIN to PATH in $SHELL_RC"
  info "Run: source $SHELL_RC   (or open a new terminal)"
fi

export PATH="$BBIN_BIN:$PATH"

# ── boundary CLI ──────────────────────────────────────────────
info "Fetching latest boundary release tag..."
BOUNDARY_TAG=$(curl -fsSL https://api.github.com/repos/thijs-creemers/boundary/releases/latest \
  | grep '"tag_name"' | sed 's/.*"tag_name": "\(.*\)".*/\1/') \
  || fail "Failed to fetch latest release tag. Check your internet connection."

info "Installing boundary CLI @ $BOUNDARY_TAG..."
bbin install https://github.com/thijs-creemers/boundary \
  --tag "$BOUNDARY_TAG" \
  --git/root libs/boundary-cli \
  --main-opts '["-m" "boundary.cli.main"]' \
  --as boundary \
  || fail "Failed to install boundary CLI via bbin.
  If --git/root is not supported by your bbin version, upgrade bbin and retry."

ok "boundary CLI installed"

echo ""
echo -e "${GREEN}━━━ Install complete ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""
echo "  Next step:"
echo ""
echo "    boundary new <your-app-name>"
echo ""
```

- [ ] **Step 2: Make executable and test locally (macOS)**

```bash
chmod +x scripts/install.sh
# Dry-run — check OS detection works:
bash -c 'source scripts/install.sh' 2>&1 | head -20
```

- [ ] **Step 3: Verify `--git/root` bbin flag**

```bash
bbin --help | grep git
```

If `--git/root` is not supported, replace with the top-level wrapper approach: create `bin/boundary.clj` as a thin script that adds the right classpath and delegates to `boundary.cli.main`. Adjust the install command in `install.sh` and `bbin install` to target `bin/boundary.clj` as a file install.

- [ ] **Step 4: Commit**

```bash
git add scripts/install.sh
git commit -m "feat: add bootstrap install script"
```

---

## Task 8: Version bump integration

**Files:**
- Modify: `libs/tools/src/boundary/tools/deploy.clj`

- [ ] **Step 1: Read the current deploy.clj to understand `all-libs` and deploy flow**

```bash
cat libs/tools/src/boundary/tools/deploy.clj
```

- [ ] **Step 2: Add `boundary-cli` to `all-libs` in `deploy.clj`**

Find the `all-libs` vector and add `"boundary-cli"` in dependency order (after `"tools"`, before `"core"`):

```clojure
(def all-libs
  ["tools"
   "boundary-cli"   ; <-- add here
   "core"
   ...])
```

- [ ] **Step 3: Add catalogue-patch function to `deploy.clj`**

After the existing deploy logic, add:

```clojure
(def ^:private catalogue-path
  "libs/boundary-cli/resources/boundary/cli/modules-catalogue.edn")

(defn- patch-catalogue-version!
  "Update :version for lib-name in modules-catalogue.edn after a successful deploy."
  [lib-name new-version]
  (let [f       (io/file catalogue-path)
        content (slurp f)
        ;; Replace the version in the entry for this lib
        ;; Match: {:name "lib-name" ... :version "old-version"}
        pattern (re-pattern (str "(?m)(\\{[^}]*:name\\s+\"" (java.util.regex.Pattern/quote lib-name) "\"[^}]*:version\\s+\")([^\"]+)(\")"))]
    (if (re-find pattern content)
      (do (spit f (str/replace content pattern (str "$1" new-version "$3")))
          (println (green (str "  Catalogue updated: " lib-name " → " new-version))))
      (println (dim (str "  Catalogue: no entry for " lib-name " (skipping)"))))))
```

- [ ] **Step 4: Call `patch-catalogue-version!` after each successful deploy**

In the deploy loop (wherever a successful Clojars deploy is confirmed), add:

```clojure
(patch-catalogue-version! lib-name version)
```

Find the version for each lib from its `build.clj`:

```clojure
(defn- read-lib-version [lib-name]
  (let [build-file (str "libs/" lib-name "/build.clj")
        content    (slurp build-file)]
    (second (re-find #"\(def version \"([^\"]+)\"" content))))
```

- [ ] **Step 5: Run the tools tests to confirm no regression**

```bash
bb test:tools
```

Expected: all existing tools tests pass.

- [ ] **Step 6: Run the boundary-cli tests**

```bash
bb test:boundary-cli
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add libs/tools/src/boundary/tools/deploy.clj
git commit -m "feat: auto-patch modules-catalogue.edn version on deploy"
```

---

## Done

All tasks complete. Verify the full flow end to end:

```bash
# Run all tests
bb test:boundary-cli
bb test:tools

# Smoke test the CLI
bb -cp libs/boundary-cli/src:libs/boundary-cli/resources -m boundary.cli.main list modules
bb -cp libs/boundary-cli/src:libs/boundary-cli/resources -m boundary.cli.main list modules --json

# Full new + add flow in /tmp
cd /tmp
bb -cp /repo/libs/boundary-cli/src:/repo/libs/boundary-cli/resources -m boundary.cli.main new my-smoke-test
cd my-smoke-test
bb -cp /repo/libs/boundary-cli/src:/repo/libs/boundary-cli/resources -m boundary.cli.main add payments
cat AGENTS.md
cat deps.edn
cat resources/conf/dev/config.edn
cd /tmp && rm -rf my-smoke-test
```
