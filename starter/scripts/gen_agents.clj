#!/usr/bin/env bb
;; scripts/gen_agents.clj
;;
;; Regenerate AGENTS.md from this project's deps.edn.
;; Self-contained — no dependency on starter scripts.
;;
;; Usage:
;;   bb gen-agents               ; Regenerate in the current directory
;;   bb gen-agents --dry-run     ; Print without writing
;;   bb gen-agents --project-dir /path/to/project

(ns gen-agents
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(defn parse-args [args]
  (loop [rem args result {:dry-run false :project-dir "."}]
    (if (empty? rem)
      result
      (case (first rem)
        "--dry-run"     (recur (rest rem) (assoc result :dry-run true))
        "--project-dir" (recur (drop 2 rem) (assoc result :project-dir (second rem)))
        (recur (rest rem) result)))))

;; =============================================================================
;; Project introspection
;; =============================================================================

(defn read-deps [project-dir]
  (let [f (io/file project-dir "deps.edn")]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn extract-libs
  "Return ordered vector of boundary lib keywords inferred from deps.edn."
  [deps]
  (let [lib-order [:core :observability :platform :user :admin :storage
                   :scaffolder :cache :jobs :email :tenant :realtime
                   :workflow :search :external :reports :calendar :geo :ai :ui-style]
        present   (->> (:deps deps)
                       keys
                       (filter #(= "boundary" (namespace %)))
                       (map #(keyword (name %)))
                       set)]
    (filterv present lib-order)))

(defn detect-db
  "Infer :sqlite, :postgres, or :both from JDBC driver deps."
  [deps]
  (let [dep-names (->> (:deps deps) keys (map name) set)
        pg?       (dep-names "postgresql")
        sq?       (dep-names "sqlite-jdbc")]
    (cond
      (and pg? sq?) :both
      pg?           :postgres
      :else         :sqlite)))

(defn infer-project-name [project-dir]
  (.getName (.getAbsoluteFile (io/file project-dir))))

;; =============================================================================
;; Content generation
;; =============================================================================

(def github-base "https://github.com/tcbv/boundary/blob/main")

(def lib-guide-info
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

(defn- env-rows
  "Build env-var table rows from detected libs and db."
  [libs db-choice]
  (let [has? (set libs)]
    (concat
     [["BND_ENV"    "Yes" "`development` / `test` / `production`"]]
     (when (has? :user)    [["JWT_SECRET" "Yes" "Minimum 32 characters"]])
     (when (has? :email)   [["SMTP_HOST"  "Yes" ""]
                            ["SMTP_PORT"  "Yes" ""]
                            ["EMAIL_FROM" "Yes" ""]])
     (when (= db-choice :postgres) [["DATABASE_URL" "Yes" "JDBC PostgreSQL connection string"]])
     [["LOG_LEVEL"  "No"  "`info` (default), `debug`, `warn`, `error`"]
      ["HTTP_PORT"  "No"  "Default: 3000"]]
     (when (has? :cache)   [["REDIS_URL"  "No"  "Falls back to in-memory when absent"]])
     (when (has? :storage) [["S3_BUCKET"  "No"  "Required for S3 storage provider"]
                            ["S3_REGION"  "No"  ""]
                            ["AWS_ACCESS_KEY_ID"     "No" ""]
                            ["AWS_SECRET_ACCESS_KEY" "No" ""]])
     (when (has? :email)   [["SMTP_USERNAME" "No" ""]
                            ["SMTP_PASSWORD" "No" ""]]))))

(defn- env-table [libs db-choice]
  (str
   "| Variable | Required | Notes |\n"
   "|----------|----------|-------|\n"
   (str/join "\n"
             (map (fn [[v req notes]] (str "| `" v "` | " req " | " notes " |"))
                  (env-rows libs db-choice)))))

(defn- lib-table [libs]
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

(defn agents-md-content [libs project-name db-choice]
  (let [has? (set libs)]
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
     (when (has? :user)
       "JWT_SECRET=\"dev-secret-32-chars-minimum\" clojure -M:test  ; Auth tests\n")
     "\n"
     "# Scaffolding\n"
     "bb scaffold                                        ; Interactive module wizard\n"
     "bb scaffold ai \"product with name, price, stock\"  ; AI-powered NL scaffolding\n\n"
     "# Regenerate this file\n"
     "bb gen-agents\n\n"
     "# Build\n"
     "clojure -T:build clean && clojure -T:build uber\n"
     "java -jar target/" project-name "-*.jar\n"
     "```\n\n"
     "---\n\n"

     "## Environment Variables\n\n"
     (env-table libs db-choice) "\n\n"
     (when (= db-choice :postgres)
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
     (lib-table libs) "\n\n"
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

     "*Generated with Boundary Framework — run `bb gen-agents` to refresh*\n")))

;; =============================================================================
;; Main
;; =============================================================================

(let [{:keys [dry-run project-dir]} (parse-args *command-line-args*)
      deps         (read-deps project-dir)
      _            (when-not deps
                     (println (str "Error: deps.edn not found in " project-dir))
                     (System/exit 1))
      libs         (extract-libs deps)
      db-choice    (detect-db deps)
      project-name (infer-project-name project-dir)
      content      (agents-md-content libs project-name db-choice)
      output-file  (io/file project-dir "AGENTS.md")]
  (if dry-run
    (println content)
    (do
      (spit output-file content)
      (println (str "✓ AGENTS.md written (" (.length output-file) " bytes)")))))
