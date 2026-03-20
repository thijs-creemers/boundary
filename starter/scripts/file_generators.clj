#!/usr/bin/env bb
;; scripts/file_generators.clj
;;
;; File generation functions for Boundary starter projects.
;; Takes resolved templates and writes actual project files to disk.
;;
;; Core functions:
;; - create-directory-structure! - Create src/test/resources directories
;; - write-deps-edn! - Write deps.edn with proper formatting
;; - write-config-edn! - Write config.edn with Aero tags
;; - write-env-example! - Write .env.example
;; - write-readme! - Generate README.md from template
;; - write-gitignore! - Generate .gitignore
;; - write-build-clj! - Generate build.clj
;; - write-system-clj! - Generate Integrant system.clj
;; - generate-project! - Full project generation

(ns file-generators
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; Load helpers for template processing
(load-file "scripts/helpers.clj")
(require '[helpers :as helpers])

;; =============================================================================
;; Directory Structure
;; =============================================================================

(defn create-directory-structure!
  "Create standard Boundary project directory structure.
   Returns map of created directories."
  [output-dir]
  (let [dirs ["src/boundary"
              "test/boundary"
              "resources/conf/dev"
              "resources/conf/dev/admin"
              "resources/public"
              "scripts"
              "target"
              ".clj-kondo"]]
    (doseq [dir dirs]
      (let [dir-path (io/file output-dir dir)]
        (.mkdirs dir-path)))
    {:output-dir output-dir
     :created-dirs dirs}))

;; =============================================================================
;; deps.edn Generation
;; =============================================================================

(defn write-deps-edn!
  "Write deps.edn file from template.
   
   Options:
   - :db-choice - :sqlite, :postgres, or :both"
  ([template output-dir] (write-deps-edn! template output-dir {}))
  ([template output-dir {:keys [db-choice] :or {db-choice :sqlite}}]
   (let [deps (helpers/template->deps-edn template {:db-choice db-choice})
         output-file (io/file output-dir "deps.edn")
         content (helpers/pprint-edn deps)]
     (spit output-file content)
     {:file (.getPath output-file)
      :size (.length output-file)})))

;; =============================================================================
;; config.edn Generation
;; =============================================================================

(defn write-config-edn!
  "Write resources/conf/dev/config.edn with Aero tags."
  [template output-dir]
  (let [config (helpers/template->config-edn template)
        output-file (io/file output-dir "resources/conf/dev/config.edn")
        content (helpers/config->aero-string config)]
    (spit output-file content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; .env.example Generation
;; =============================================================================

(defn write-env-example!
  "Write .env.example file from template."
  [template output-dir]
  (let [env-content (helpers/template->env-vars template)
        output-file (io/file output-dir ".env.example")]
    (spit output-file env-content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; .gitignore Generation
;; =============================================================================

(def gitignore-content
  "# Clojure
.cpcache/
.nrepl-port
target/
*.class
*.jar
!project.jar

# Environment
.env
.env.local

# IDE
.idea/
.vscode/
*.iml
.lsp/
.clj-kondo/.cache/

# Database
*.db
*.db-shm
*.db-wal
dev-database.db

# Logs
logs/
*.log

# OS
.DS_Store
Thumbs.db

# Build
pom.xml
pom.xml.asc
")

(defn write-gitignore!
  "Write .gitignore file."
  [output-dir]
  (let [output-file (io/file output-dir ".gitignore")]
    (spit output-file gitignore-content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; build.clj Generation
;; =============================================================================

(defn build-clj-content
  "Generate build.clj content with project name."
  [project-name]
  (str "(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib '" project-name "/app)
(def version \"0.1.0\")
(def class-dir \"target/classes\")
(def basis (b/create-basis {:project \"deps.edn\"}))
(def uber-file (format \"target/%s-%s.jar\" (name lib) version))

(defn clean [_]
  (b/delete {:path \"target\"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs [\"resources\" \"src\"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs [\"src\"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'conf.dev.system})
  (println \"Built\" uber-file))
"))

(defn write-build-clj!
  "Write build.clj file."
  [output-dir project-name]
  (let [content (build-clj-content project-name)
        output-file (io/file output-dir "build.clj")]
    (spit output-file content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; system.clj Generation
;; =============================================================================

(def system-clj-content
  "(ns conf.dev.system
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [integrant.core :as ig])
  (:import (java.io PushbackReader)))

(defn read-config [profile]
  (aero/read-config (-> (str \"conf/\" (name profile) \"/config.edn\")
                        io/resource
                        io/reader
                        PushbackReader.)
                    {:profile profile}))

(defn -main [& _]
  (let [profile (or (System/getenv \"BND_ENV\") \"development\")
        config (read-config (keyword profile))]
    (ig/init config)))
")

(defn write-system-clj!
  "Write resources/conf/dev/system.clj Integrant bootstrap."
  [output-dir]
  (let [output-file (io/file output-dir "resources/conf/dev/system.clj")]
    (spit output-file system-clj-content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; README.md Generation
;; =============================================================================

(defn readme-content
  "Generate README.md content from template."
  [template project-name]
  (let [sections (helpers/template->readme-sections template)
        template-name (get-in template [:meta :name])
        description (get-in template [:meta :description])]
    (str "# " project-name "

**Template**: " template-name "
**Description**: " description "

---

## Features

" (:features sections) "

---

## Quick Start

```bash
# Set environment variables
export BND_ENV=development
export JWT_SECRET=\"" (apply str (repeatedly 32 #(rand-nth "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))) "\"

# Start REPL
clojure -M:repl-clj

# In REPL
(require '[integrant.repl :as ig-repl])
(require 'conf.dev.system)
(ig-repl/go)

# Visit http://localhost:3000
```

---

## Next Steps

" (:next-steps sections) "

---

## Testing

Generated app tests depend on project libraries (for example Integrant/Reitit).
If you run tests via `bb -e/load-file`, make sure you include the project classpath, or prefer the Clojure test alias.

```bash
# Run all tests
clojure -M:test:db/h2

# Run with watch
clojure -M:test:db/h2 --watch

# Run specific test
clojure -M:test:db/h2 --focus your-test-ns
```

---

## Build

```bash
# Build uberjar
clojure -T:build clean
clojure -T:build uber

# Run standalone
java -jar target/" project-name "-*.jar
```

---

## Project Structure

```
" project-name "/
├── src/boundary/          # Application code (FC/IS pattern)
├── test/boundary/         # Tests
├── resources/
│   ├── conf/dev/          # Configuration files
│   └── public/            # Static assets
├── deps.edn               # Dependencies
├── build.clj              # Build configuration
└── README.md              # This file
```

---

## Documentation

- [Boundary Framework](https://github.com/thijs-creemers/boundary)
- [AGENTS.md](https://github.com/thijs-creemers/boundary/blob/main/AGENTS.md) - Commands and conventions

---

**Generated with Boundary Framework**
")))

(defn write-readme!
  "Write README.md file from template."
  [template output-dir project-name]
  (let [content (readme-content template project-name)
        output-file (io/file output-dir "README.md")]
    (spit output-file content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; Placeholder App Files
;; =============================================================================

(def app-clj-content
  "(ns boundary.app
  (:require [clojure.tools.logging :as log]))

(defn hello-world []
  (log/info \"Hello from Boundary!\")
  {:status 200
   :headers {\"Content-Type\" \"text/plain\"}
   :body \"Hello, Boundary!\"})
")

(defn write-app-clj!
  "Write src/boundary/app.clj placeholder."
  [output-dir]
  (let [output-file (io/file output-dir "src/boundary/app.clj")]
    (spit output-file app-clj-content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

(def app-test-clj-content
  "(ns boundary.app-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.app :as app]))

(deftest hello-world-test
  (testing \"hello-world returns 200 OK\"
    (let [response (app/hello-world)]
      (is (= 200 (:status response)))
      (is (= \"Hello, Boundary!\" (:body response))))))
")

(defn write-app-test-clj!
  "Write test/boundary/app_test.clj placeholder."
  [output-dir]
  (let [output-file (io/file output-dir "test/boundary/app_test.clj")]
    (spit output-file app-test-clj-content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; gen_agents script + bb.edn Generation
;; =============================================================================

(defn write-gen-agents-script!
  "Copy scripts/gen_agents.clj into the generated project's scripts/ directory.
   The script is self-contained so it works independently of the starter."
  [output-dir]
  (let [source      (io/file "scripts/gen_agents.clj")
        output-file (io/file output-dir "scripts/gen_agents.clj")]
    (io/copy source output-file)
    {:file (.getPath output-file)
     :size (.length output-file)}))

(def bb-edn-content
  "{:tasks
 {scaffold
  {:doc \"Interactive module scaffolding wizard (bb scaffold [generate|new|field|endpoint|adapter|ai]\"
   :task (apply shell \"bb scripts/scaffold.clj\" *command-line-args*)}

  gen-agents
  {:doc \"Regenerate AGENTS.md from deps.edn (bb gen-agents [--dry-run])\"
   :task (apply shell \"bb scripts/gen_agents.clj\" *command-line-args*)}}}")

(defn write-bb-edn!
  "Write bb.edn for the generated project."
  [output-dir]
  (let [output-file (io/file output-dir "bb.edn")]
    (spit output-file bb-edn-content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; AGENTS.md Generation
;; =============================================================================

(def ^:private github-base
  "https://github.com/tcbv/boundary/blob/main")

(def ^:private lib-guide-info
  {:core         {:path "libs/core/AGENTS.md"          :desc "Validation, case conversion, interceptor pipeline, feature flags"}
   :observability {:path "libs/observability/AGENTS.md" :desc "Logging, metrics, service/persistence interceptor patterns"}
   :platform     {:path "libs/platform/AGENTS.md"      :desc "HTTP interceptor architecture, routing, DB infrastructure"}
   :user         {:path "libs/user/AGENTS.md"          :desc "Authentication, authorization, MFA, session management"}
   :admin        {:path "libs/admin/AGENTS.md"         :desc "Auto-CRUD admin UI (Hiccup, HTMX), entity config, form pitfalls"}
   :storage      {:path "libs/storage/AGENTS.md"       :desc "File storage (local/S3), validation, image processing, signed URLs"}
   :scaffolder   {:path "libs/scaffolder/AGENTS.md"    :desc "Module code generation commands and workflow"}
   :cache        {:path "libs/cache/AGENTS.md"         :desc "Distributed caching, TTL, atomic ops, tenant-scoped cache"}
   :jobs         {:path "libs/jobs/AGENTS.md"          :desc "Background job processing, retry logic, worker pools, dead letter queue"}
   :email        {:path "libs/email/AGENTS.md"         :desc "SMTP sending, async/queued modes, jobs integration"}
   :tenant       {:path "libs/tenant/AGENTS.md"        :desc "Multi-tenancy, schema-per-tenant, provisioning, lifecycle states"}
   :realtime     {:path "libs/realtime/AGENTS.md"      :desc "WebSocket messaging, JWT auth, pub/sub, message routing"}
   :workflow     {:path "libs/workflow/AGENTS.md"      :desc "State machine definitions, transitions, lifecycle hooks, auto-transitions"}
   :search       {:path "libs/search/AGENTS.md"        :desc "Document indexing, FTS/LIKE strategy, filter support, migrations"}
   :external     {:path "libs/external/AGENTS.md"      :desc "Stripe payments, Twilio SMS/WhatsApp, SMTP transport, IMAP mailbox"}
   :reports      {:path "libs/reports/AGENTS.md"       :desc "defreport macro, PDF/CSV export, scheduling"}
   :calendar     {:path "libs/calendar/AGENTS.md"      :desc "defevent macro, RRULE recurrence, iCal, conflict detection, Hiccup UI"}
   :geo          {:path "libs/geo/AGENTS.md"           :desc "Geocoding (OSM/Google/Mapbox), DB cache, rate limiting, Haversine distance"}
   :ai           {:path "libs/ai/AGENTS.md"            :desc "Multi-provider AI (Ollama/Anthropic/OpenAI), NL scaffolding, error explainer, SQL copilot"}
   :ui-style     {:path "libs/ui-style/AGENTS.md"      :desc "Shared UI style bundles, design tokens, CSS assets"}})

(defn- agents-env-table
  "Render the environment variables as a markdown table.
   BND_ENV is always first; required and optional come from the resolved template."
  [template]
  (let [required (get-in template [:env-vars :required] [])
        optional (get-in template [:env-vars :optional] [])
        rows (concat
              [["BND_ENV" "Yes" "`development` / `test` / `production`"]]
              (map #(vector % "Yes" "") required)
              (map #(vector % "No"  "") optional))]
    (str
     "| Variable | Required | Notes |\n"
     "|----------|----------|-------|\n"
     (str/join "\n"
               (map (fn [[v req notes]] (str "| `" v "` | " req " | " notes " |"))
                    rows)))))

(defn- agents-lib-table
  "Render included boundary libs as a markdown table with links to their AGENTS.md on GitHub."
  [libs]
  (str
   "| Library | Guide | Purpose |\n"
   "|---------|-------|---------|\n"
   (str/join "\n"
             (for [lib libs
                   :let [info (get lib-guide-info lib)]
                   :when info]
               (str "| **" (name lib) "**"
                    " | [AGENTS.md](" github-base "/" (:path info) ")"
                    " | " (:desc info) " |")))))

(defn agents-md-content
  "Generate AGENTS.md content tailored to the resolved template and project."
  [template project-name db-choice]
  (let [libs       (:boundary-libs template)
        has-user?  (some #{:user}   libs)
        has-pg?    (= db-choice :postgres)]
    (str
     "# " project-name " — Development Guide\n\n"
     "> Built with [Boundary Framework](https://github.com/tcbv/boundary).  \n"
     "> Full framework reference: [Boundary AGENTS.md](" github-base "/AGENTS.md)\n\n"
     "---\n\n"

     "## Quick Reference\n\n"
     "```bash\n"
     "# REPL\n"
     "clojure -M:repl-clj\n\n"
     "# In REPL:\n"
     "(require '[integrant.repl :as ig-repl])\n"
     "(ig-repl/go)      ; Start system\n"
     "(ig-repl/reset)   ; Reload changed namespaces and restart\n"
     "(ig-repl/halt)    ; Stop system\n\n"
     "# Testing\n"
     "clojure -M:test                    ; All tests\n"
     "clojure -M:test --watch            ; Watch mode\n"
     (when has-user?
       "JWT_SECRET=\"dev-secret-32-chars-minimum\" clojure -M:test  ; Auth tests\n")
     "\n"
     "# Scaffolding\n"
     "bb scaffold                                        ; Interactive module wizard\n"
     "bb scaffold ai \"product with name, price, stock\"  ; AI-powered NL scaffolding\n\n"
     "# Build\n"
     "clojure -T:build clean && clojure -T:build uber\n"
     "java -jar target/" project-name "-*.jar\n"
     "```\n\n"
     "---\n\n"

     "## Environment Variables\n\n"
     (agents-env-table template) "\n\n"
     (when has-pg?
       (str "> **PostgreSQL**: set `DATABASE_URL` to a valid JDBC URL,\n"
            "> e.g. `jdbc:postgresql://localhost:5432/mydb?user=myuser&password=secret`\n\n"))
     "---\n\n"

     "## Architecture: Functional Core / Imperative Shell\n\n"
     "```\n"
     "src/boundary/" project-name "/\n"
     "├── core/       # Pure functions ONLY — no I/O, no logging, no exceptions\n"
     "├── shell/      # All side effects: persistence, services, HTTP handlers\n"
     "├── ports.clj   # Protocol definitions (interfaces)\n"
     "└── schema.clj  # Malli validation schemas\n"
     "```\n\n"
     "**Dependency rules (strictly enforced):**\n"
     "- Shell → Core (allowed)\n"
     "- Core → Ports (allowed)\n"
     "- Core → Shell (NEVER — violates FC/IS)\n\n"
     "---\n\n"

     "## Included Libraries\n\n"
     (agents-lib-table libs) "\n\n"
     "---\n\n"

     "## Key Conventions\n\n"
     "### Case conversion\n\n"
     "| Location | Format | Example |\n"
     "|----------|--------|---------|\n"
     "| All Clojure code | kebab-case | `:password-hash`, `:created-at` |\n"
     "| Database boundary only | snake_case | `password_hash`, `created_at` |\n"
     "| API boundary only | camelCase | `passwordHash`, `createdAt` |\n\n"
     "```clojure\n"
     "(require '[boundary.core.utils.case-conversion :as cc])\n\n"
     ";; DB → Clojure (at persistence boundary)\n"
     "(cc/snake-case->kebab-case-map db-record)\n\n"
     ";; Clojure → DB (at persistence boundary)\n"
     "(cc/kebab-case->snake-case-map entity)\n"
     "```\n\n"
     "### Adding new fields — always synchronize:\n\n"
     "1. Malli schema in `schema.clj`\n"
     "2. Database column (new migration)\n"
     "3. Persistence layer transformations in `shell/persistence.clj`\n\n"
     "---\n\n"

     "## Common Pitfalls\n\n"
     "### 1. `defrecord` changes require full restart\n\n"
     "`(ig-repl/reset)` does not recreate `defrecord` instances. Use:\n\n"
     "```clojure\n"
     "(ig-repl/halt)\n"
     "(ig-repl/go)\n"
     "```\n\n"
     "### 2. Always include `:type` in `ex-info`\n\n"
     "```clojure\n"
     ";; Every (throw (ex-info ...)) must carry :type\n"
     "(throw (ex-info \"Not found\" {:type :not-found :id id}))\n"
     ";; Valid types: :validation-error :not-found :unauthorized\n"
     ";;              :forbidden :conflict :internal-error\n"
     "```\n\n"
     "### 3. API routes — normalized map format, not Reitit vectors\n\n"
     "```clojure\n"
     ";; WRONG\n"
     "[[\"api/my-resource\" {:get {:handler ...}}]]\n\n"
     ";; CORRECT — versioning middleware adds /api/v1 automatically\n"
     "[{:path \"/my-resource\" :methods {:get {:handler ... :summary \"...\"}}}]\n"
     "```\n\n"
     "### 4. Parenthesis repair — never fix manually\n\n"
     "```bash\n"
     "clj-paren-repair src/boundary/" project-name "/core/my_module.clj\n"
     "```\n\n"
     "---\n\n"

     "## REPL Debugging\n\n"
     "```clojure\n"
     ";; Access a running service component\n"
     "(def my-svc (get integrant.repl.state/system :boundary/my-service))\n\n"
     ";; Reload a namespace after editing\n"
     "(require '[boundary." project-name ".core.my-module :as m] :reload)\n\n"
     ";; Query the database directly\n"
     "(def ds (get-in integrant.repl.state/system [:boundary/db-context :datasource]))\n"
     "(next.jdbc/execute! ds [\"SELECT * FROM my_table LIMIT 10\"])\n"
     "```\n\n"
     "---\n\n"

     "*Generated with Boundary Framework — "
     "template: `" (get-in template [:meta :name]) "`*\n")))

(defn write-agents-md!
  "Write AGENTS.md for the generated project."
  [template output-dir project-name db-choice]
  (let [content     (agents-md-content template project-name db-choice)
        output-file (io/file output-dir "AGENTS.md")]
    (spit output-file content)
    {:file (.getPath output-file)
     :size (.length output-file)}))

;; =============================================================================
;; Full Project Generation
;; =============================================================================

(defn generate-project!
  "Generate complete project from template.
   
   Args:
   - template: Resolved template map (from resolve-extends)
   - output-dir: Target directory for project
   - project-name: Project name (for build.clj, README)
   - opts: Options map
     - :db-choice - :sqlite, :postgres, or :both
   
   Returns:
   Map with generation results and file paths."
  ([template output-dir project-name]
   (generate-project! template output-dir project-name {}))
  ([template output-dir project-name opts]
   (let [start-time (System/currentTimeMillis)]
     (println (str "Generating project: " project-name))
     (println (str "Output directory: " output-dir))
     (println (str "Template: " (get-in template [:meta :name])))
     (println)

     ;; Step 1: Create directory structure
     (println "📁 Creating directory structure...")
     (let [dirs (create-directory-structure! output-dir)]
       (println (str "   Created " (count (:created-dirs dirs)) " directories"))
       (println))

     ;; Step 2: Write deps.edn
     (println "📦 Writing deps.edn...")
     (let [result (write-deps-edn! template output-dir opts)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 3: Write config.edn
     (println "⚙️  Writing config.edn...")
     (let [result (write-config-edn! template output-dir)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 4: Write .env.example
     (println "🌍 Writing .env.example...")
     (let [result (write-env-example! template output-dir)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 5: Write .gitignore
     (println "🚫 Writing .gitignore...")
     (let [result (write-gitignore! output-dir)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 6: Write build.clj
     (println "🔨 Writing build.clj...")
     (let [result (write-build-clj! output-dir project-name)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 7: Write system.clj
     (println "🔧 Writing system.clj...")
     (let [result (write-system-clj! output-dir)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 8: Write README.md
     (println "📖 Writing README.md...")
     (let [result (write-readme! template output-dir project-name)]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 9: Write placeholder app files
     (println "📝 Writing application files...")
     (write-app-clj! output-dir)
     (println "   ✓ src/boundary/app.clj")
     (write-app-test-clj! output-dir)
     (println "   ✓ test/boundary/app_test.clj")
     (println)

     ;; Step 10: Write AGENTS.md
     (println "🤖 Writing AGENTS.md...")
     (let [result (write-agents-md! template output-dir project-name (get opts :db-choice :sqlite))]
       (println (str "   ✓ " (:file result) " (" (:size result) " bytes)"))
       (println))

     ;; Step 11: Write bb.edn + gen_agents script
     (println "📋 Writing bb.edn and scripts/gen_agents.clj...")
     (let [bb-result  (write-bb-edn! output-dir)
           gen-result (write-gen-agents-script! output-dir)]
       (println (str "   ✓ " (:file bb-result)))
       (println (str "   ✓ " (:file gen-result)))
       (println))

     (let [elapsed (- (System/currentTimeMillis) start-time)]
       (println "✅ Project generation complete!")
       (println (str "   Time: " elapsed "ms"))
       (println)
       (println "Next steps:")
       (println (str "   cd " output-dir))
       (println "   clojure -M:repl-clj")
       (println "   (ig-repl/go)")

       {:success true
        :project-name project-name
        :output-dir output-dir
        :template-name (get-in template [:meta :name])
        :elapsed-ms elapsed}))))
