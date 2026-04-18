#!/usr/bin/env bb
;; libs/tools/src/boundary/tools/help.clj
;;
;; Contextual Help — state-aware guidance and reference for Boundary projects.
;;
;; Usage (via bb.edn task):
;;   bb help                    # General help listing all commands
;;   bb help next               # State-aware guidance (what to do next)
;;   bb help <topic>            # Detailed help for a topic
;;   bb help error BND-xxx      # Look up an error code

(ns boundary.tools.help
  (:require [boundary.tools.ansi :refer [bold green red yellow dim cyan]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Error code catalog (simplified, BB-compatible)
;; =============================================================================

(def error-catalog
  {"BND-001" {:title   "Missing required configuration key"
              :explain "A required key is missing from your config.edn file."
              :fix     "Check resources/conf/<env>/config.edn and add the missing key. Run `bb doctor` to identify which keys are missing."}
   "BND-002" {:title   "Invalid provider value"
              :explain "A :provider key in config.edn has a value that is not recognized."
              :fix     "Run `bb doctor` to see valid provider values for each module."}
   "BND-003" {:title   "FC/IS violation — core depends on shell"
              :explain "A namespace under core/ is importing from shell/, which violates the Functional Core / Imperative Shell architecture."
              :fix     "Move the side-effecting logic to shell/ and pass data through ports. Run `bb check:fcis` to find all violations."}
   "BND-004" {:title   "Schema validation failure"
              :explain "Input data does not match the Malli schema defined in schema.clj."
              :fix     "Check the Malli schema in the relevant library's schema.clj and ensure the data conforms. Common issue: kebab-case vs snake_case mismatch."}
   "BND-005" {:title   "Database migration conflict"
              :explain "Two migration files have the same version number or there is a gap in the sequence."
              :fix     "Check resources/migrations/ for duplicate version numbers and rename as needed."}
   "BND-006" {:title   "JWT_SECRET not configured"
              :explain "The user/auth module requires JWT_SECRET but it is not set in the environment."
              :fix     "Export JWT_SECRET with at least 32 characters: export JWT_SECRET=\"your-secret-here\""}
   "BND-007" {:title   "Circular library dependency"
              :explain "Two or more libraries depend on each other, creating a cycle."
              :fix     "Run `bb check:deps` to identify the cycle. Extract shared code into a lower-level library (usually core)."}
   "BND-008" {:title   "Admin entity config missing required field"
              :explain "An admin entity EDN file is missing a required field such as :entity/name or :entity/table."
              :fix     "Check the entity EDN file in resources/conf/<env>/admin/ and add the missing field."}
   "BND-009" {:title   "Unresolved #env reference"
              :explain "A #env reference in config.edn has no value set and no #or default."
              :fix     "Either set the environment variable or wrap the reference in #or [#env VAR \"default-value\"]."}
   "BND-010" {:title   "Module not wired in wiring.clj"
              :explain "A module is active in config.edn but has no require entry in wiring.clj, so Integrant cannot find its init-key methods."
              :fix     "Add a require for the module in libs/platform/src/boundary/platform/shell/system/wiring.clj."}})

;; =============================================================================
;; Topic help content
;; =============================================================================

(defn- help-topic-scaffold []
  (println (bold "Scaffolding Guide"))
  (println)
  (println "Boundary includes a scaffolding wizard to generate modules, fields, endpoints, and adapters.")
  (println)
  (println (cyan "Interactive mode:"))
  (println "  bb scaffold                                           # Launch interactive wizard")
  (println)
  (println (cyan "AI-powered scaffolding:"))
  (println "  bb scaffold ai \"product module with name, price\"      # Generate from description")
  (println "  bb scaffold ai \"product module\" --yes                 # Skip confirmation prompts")
  (println)
  (println (cyan "Integration:"))
  (println "  bb scaffold integrate product                         # Wire module into deps/tests/wiring")
  (println "  bb scaffold integrate product --dry-run               # Preview changes only")
  (println)
  (println (dim "After scaffolding, always run `bb scaffold integrate <module>` to wire it up."))
  (println (dim "See libs/scaffolder/AGENTS.md for full documentation.")))

(defn- help-topic-testing []
  (println (bold "Testing Guide"))
  (println)
  (println (cyan "Run tests:"))
  (println "  clojure -M:test:db/h2                                 # All tests")
  (println "  clojure -M:test:db/h2 :core                           # Single library")
  (println "  clojure -M:test:db/h2 --focus-meta :unit              # By metadata")
  (println "  clojure -M:test:db/h2 --focus ns-name-test            # Single namespace")
  (println "  clojure -M:test:db/h2 --watch :core                   # Watch mode")
  (println)
  (println (cyan "Test categories:"))
  (println "  ^:unit          Pure core functions, no mocks needed")
  (println "  ^:integration   Shell services with mocked adapters")
  (println "  ^:contract      Adapters against real DB (H2 in-memory)")
  (println "  ^:security      Security-focused tests (error mapping, CSRF, XSS, SQL)")
  (println)
  (println (cyan "AI-assisted:"))
  (println "  bb ai gen-tests libs/user/src/boundary/user/core/validation.clj")
  (println)
  (println (cyan "Quality gates:"))
  (println "  bb check:fcis                                         # FC/IS enforcement")
  (println "  bb check:placeholder-tests                            # Detect placeholder tests")
  (println "  bb check:deps                                         # Dependency direction check")
  (println)
  (println (dim "Test suites are defined in tests.edn. Each library has its own :id.")))

(defn- help-topic-database []
  (println (bold "Database Guide"))
  (println)
  (println (cyan "Migrations:"))
  (println "  clojure -M:migrate up                                 # Run pending migrations")
  (println)
  (println (cyan "SQL generation:"))
  (println "  bb ai sql \"find active users with orders\"             # Generate HoneySQL from NL")
  (println)
  (println (cyan "Case convention:"))
  (println "  Clojure code    kebab-case   :password-hash, :created-at")
  (println "  Database        snake_case   password_hash, created_at")
  (println "  API boundary    camelCase    passwordHash, createdAt")
  (println)
  (println (cyan "Adding a new field:"))
  (println "  1. Add Malli schema in schema.clj")
  (println "  2. Create database migration")
  (println "  3. Update persistence layer in shell/persistence.clj")
  (println)
  (println (dim "Use boundary.shared.core.utils.case-conversion for conversions.")))

(defn- help-topic-fcis []
  (println (bold "Functional Core / Imperative Shell (FC/IS)"))
  (println)
  (println "Every library in libs/ follows the FC/IS architecture pattern:")
  (println)
  (println (cyan "Structure:"))
  (println "  libs/{library}/src/boundary/{library}/")
  (println "  +-- core/        Pure functions ONLY (no I/O, no logging, no exceptions)")
  (println "  +-- shell/       All side effects (persistence, services, HTTP handlers)")
  (println "  +-- ports.clj    Protocol definitions (interfaces)")
  (println "  +-- schema.clj   Malli validation schemas")
  (println)
  (println (cyan "Dependency rules (strictly enforced):"))
  (println (green  "  Shell -> Core     allowed"))
  (println (green  "  Core  -> Ports    allowed"))
  (println (red    "  Core  -> Shell    NEVER (violates FC/IS)"))
  (println)
  (println (cyan "Enforcement:"))
  (println "  bb check:fcis                                         # Check for violations")
  (println)
  (println (dim "Core functions should be pure: same input always gives same output.")))

(defn- help-topic-config []
  (println (bold "Configuration Guide"))
  (println)
  (println (cyan "Config files:"))
  (println "  resources/conf/dev/config.edn     Development")
  (println "  resources/conf/test/config.edn    Testing")
  (println "  resources/conf/prod/config.edn    Production")
  (println "  resources/conf/acc/config.edn     Acceptance")
  (println)
  (println (cyan "Setup:"))
  (println "  bb setup                                              # Interactive wizard")
  (println "  bb setup ai \"PostgreSQL with Stripe\"                  # AI-powered setup")
  (println "  bb setup --database postgresql --payment stripe       # Non-interactive")
  (println)
  (println (cyan "Validation:"))
  (println "  bb doctor                                             # Check dev config")
  (println "  bb doctor --env all                                   # Check all environments")
  (println "  bb doctor --env all --ci                              # CI mode (exit on error)")
  (println)
  (println (cyan "Key concepts:"))
  (println "  Aero         Config reader with #env, #or, #profile tags")
  (println "  Integrant    Dependency injection and lifecycle management")
  (println "  :active      Map of modules that are enabled")
  (println)
  (println (dim "See libs/platform/AGENTS.md for HTTP/system configuration details.")))

(def topic-fns
  {"scaffold" help-topic-scaffold
   "testing"  help-topic-testing
   "database" help-topic-database
   "fcis"     help-topic-fcis
   "config"   help-topic-config})

;; =============================================================================
;; General help
;; =============================================================================

(defn- help-general []
  (println)
  (println (bold "Boundary CLI — Available Commands"))
  (println)
  (println (cyan "Scaffolding:"))
  (println "  bb scaffold                          Interactive module scaffolding wizard")
  (println "  bb scaffold ai \"description\"          AI-powered scaffolding from NL")
  (println "  bb scaffold integrate <module>        Wire scaffolded module into project")
  (println)
  (println (cyan "AI Tools:"))
  (println "  bb ai explain --file stacktrace.txt   Explain a Clojure/Boundary error")
  (println "  bb ai gen-tests <file>                Generate test namespace")
  (println "  bb ai sql \"description\"               Generate HoneySQL from NL")
  (println "  bb ai docs --module <lib> --type agents  Generate AGENTS.md")
  (println "  bb ai admin-entity \"description\"      Generate admin entity config")
  (println)
  (println (cyan "Project Setup:"))
  (println "  bb setup                              Interactive config setup wizard")
  (println "  bb setup ai \"description\"             AI-powered config setup")
  (println "  bb create-admin                       Create first admin user")
  (println)
  (println (cyan "Quality & Validation:"))
  (println "  bb doctor                             Validate config for common mistakes")
  (println "  bb check:fcis                         FC/IS enforcement check")
  (println "  bb check:placeholder-tests            Detect placeholder test assertions")
  (println "  bb check:deps                         Verify library dependency direction")
  (println "  bb check-links                        Validate local markdown links")
  (println "  bb smoke-check                        Verify deps.edn aliases and tools")
  (println)
  (println (cyan "Deployment:"))
  (println "  bb deploy --all                       Deploy all libraries to Clojars")
  (println "  bb deploy --missing                   Deploy only missing libraries")
  (println "  bb deploy core platform user          Deploy specific libraries")
  (println)
  (println (cyan "Utilities:"))
  (println "  bb install-hooks                      Configure git hooks")
  (println)
  (println (cyan "Help:"))
  (println "  bb help                               This listing")
  (println "  bb help next                          State-aware guidance (what to do next)")
  (println "  bb help <topic>                       Detailed help for a topic")
  (println "  bb help error BND-xxx                 Look up an error code")
  (println)
  (println (dim (str "Topics: " (str/join ", " (sort (keys topic-fns)))))))

;; =============================================================================
;; State-aware guidance (help next)
;; =============================================================================

(defn- root-dir [] (System/getProperty "user.dir"))

(defn- lib-dirs
  "List subdirectory names under libs/."
  []
  (let [d (io/file (root-dir) "libs")]
    (when (.exists d)
      (->> (.listFiles d)
           (filter #(.isDirectory %))
           (map #(.getName %))
           sort))))

(defn- integrated?
  "Check if a library is referenced in deps.edn."
  [deps-text module]
  (str/includes? deps-text (str "libs/" module "/src")))

(defn- check-unintegrated-modules
  "Find libs/ directories that are not referenced in deps.edn."
  []
  (let [deps-file (io/file (root-dir) "deps.edn")]
    (if-not (.exists deps-file)
      [{:level :warn :msg "deps.edn not found — cannot check module integration"}]
      (let [deps-text    (slurp deps-file)
            modules      (lib-dirs)
            unintegrated (remove #(integrated? deps-text %) modules)]
        (if (seq unintegrated)
          [{:level :warn
            :msg   (str "Unintegrated modules (in libs/ but not in deps.edn): "
                        (str/join ", " unintegrated))
            :fix   (str "Run `bb scaffold integrate <module>` to wire them in, or verify they are standalone libraries.")}]
          [{:level :pass
            :msg   (str "All " (count modules) " modules are integrated in deps.edn")}])))))

(defn- check-migrations
  "Check for pending migration files."
  []
  (let [migration-dir (io/file (root-dir) "resources" "migrations")]
    (if-not (.exists migration-dir)
      [{:level :warn
        :msg   "No resources/migrations/ directory found"
        :fix   "Create resources/migrations/ and add migration files, or run `clojure -M:migrate up`."}]
      (let [files (->> (.listFiles migration-dir)
                       (filter #(.isFile %))
                       vec)]
        (if (empty? files)
          [{:level :warn
            :msg   "No migration files found in resources/migrations/"
            :fix   "Add migration SQL files if you have database tables to create."}]
          [{:level :pass
            :msg   (str (count files) " migration file(s) found in resources/migrations/")}])))))

(defn- check-seeds
  "Check if dev seed data file exists."
  []
  (let [seed-file (io/file (root-dir) "resources" "seeds" "dev.edn")]
    (if (.exists seed-file)
      [{:level :pass
        :msg   "Dev seed file exists (resources/seeds/dev.edn)"}]
      [{:level :warn
        :msg   "No dev seed file found at resources/seeds/dev.edn"
        :fix   "Create resources/seeds/dev.edn with sample data for local development."}])))

(defn- check-config-exists
  "Check that at least dev config exists."
  []
  (let [dev-config (io/file (root-dir) "resources" "conf" "dev" "config.edn")]
    (if (.exists dev-config)
      [{:level :pass
        :msg   "Dev config exists (resources/conf/dev/config.edn)"}]
      [{:level :warn
        :msg   "No dev config found at resources/conf/dev/config.edn"
        :fix   "Run `bb setup` to create your configuration."}])))

(defn- format-next-result [{:keys [level msg fix]}]
  (let [icon (case level
               :pass (green "✓")
               :warn (yellow "⚠")
               :error (red "✗"))]
    (str "  " icon " " msg
         (when fix
           (str "\n" (dim (str "    Fix: " fix)))))))

(defn- help-next []
  (println)
  (println (bold "Boundary — What To Do Next"))
  (println)
  (let [results (concat (check-config-exists)
                        (check-unintegrated-modules)
                        (check-migrations)
                        (check-seeds))
        passes (count (filter #(= :pass (:level %)) results))
        warns  (count (filter #(= :warn (:level %)) results))]
    (doseq [r results]
      (println (format-next-result r)))
    (println)
    (if (zero? warns)
      (println (green "Everything looks good! Run `bb doctor` for deeper config validation."))
      (println (str (yellow (str warns " item" (when (not= warns 1) "s") " need attention"))
                    ", " (green (str passes " OK"))
                    ". " (dim "Fix the warnings above to get started."))))))

;; =============================================================================
;; Error code lookup
;; =============================================================================

(defn- help-error [code]
  (if-not code
    (do
      (println)
      (println (bold "Error Code Reference"))
      (println)
      (doseq [[code {:keys [title]}] (sort-by key error-catalog)]
        (println (str "  " (cyan code) "  " title)))
      (println)
      (println (dim "Usage: bb help error BND-xxx")))
    (let [upper-code (str/upper-case code)
          entry      (get error-catalog upper-code)]
      (println)
      (if-not entry
        (do
          (println (red (str "Unknown error code: " upper-code)))
          (println)
          (println (dim "Known codes:"))
          (doseq [k (sort (keys error-catalog))]
            (println (dim (str "  " k)))))
        (do
          (println (bold (str upper-code " — " (:title entry))))
          (println)
          (println (cyan "What happened:"))
          (println (str "  " (:explain entry)))
          (println)
          (println (cyan "How to fix:"))
          (println (str "  " (:fix entry))))))))

;; =============================================================================
;; Topic help dispatcher
;; =============================================================================

(defn- help-topic [topic]
  (let [t (str/lower-case topic)]
    (if-let [f (get topic-fns t)]
      (do (println) (f))
      (do
        (println)
        (println (red (str "Unknown topic: " topic)))
        (println)
        (println (str "Available topics: " (str/join ", " (sort (keys topic-fns)))))
        (println (dim "Usage: bb help <topic>"))))))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (case (first args)
    "next"  (help-next)
    "error" (help-error (second args))
    nil     (help-general)
    (help-topic (first args))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
