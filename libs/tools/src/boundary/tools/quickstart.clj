#!/usr/bin/env bb
;; libs/tools/src/boundary/tools/quickstart.clj
;;
;; Quickstart — one command from clone to running app.
;;
;; Usage (via bb.edn task):
;;   bb quickstart                  # Interactive quickstart
;;   bb quickstart --preset minimal # Non-interactive with minimal preset

(ns boundary.tools.quickstart
  (:require [boundary.tools.ansi :refer [bold green red dim]]
            [babashka.process :as process]
            [clojure.string :as str]))

;; =============================================================================
;; Presets — named onboarding profiles mapping to concrete setup flags
;; =============================================================================

(def presets
  {"minimal"  {:database "h2"         :description "H2 in-memory, no extras"}
   "standard" {:database "postgresql" :description "PostgreSQL, recommended for production"}
   "sqlite"   {:database "sqlite"     :description "SQLite file-based, zero-config"}})

(defn- resolve-preset
  "Resolve a preset name to setup flags. Returns nil if unknown."
  [preset-name]
  (get presets preset-name))

;; =============================================================================
;; Step runner
;; =============================================================================

(defn- run-step
  "Run a named step. Returns true if successful."
  [step-num total description cmd & {:keys [continue?]}]
  (println)
  (println (bold (str "[" step-num "/" total "] " description)))
  (println (dim (str "  $ " (str/join " " cmd))))
  (let [result (apply process/shell {:continue true} cmd)
        ok?    (zero? (:exit result))]
    (if ok?
      (println (green "  Done"))
      (do (println (red "  Failed"))
          (when-not continue?
            (println (red "\nQuickstart aborted. Fix the issue above and re-run bb quickstart."))
            (System/exit 1))))
    ok?))

;; =============================================================================
;; Main flow
;; =============================================================================

(defn- print-banner []
  (println)
  (println (bold "━━━ Boundary Quickstart ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"))
  (println)
  (println "  This will set up a complete Boundary development environment.")
  (println "  Steps: check environment, configure, scaffold, migrate, start.")
  (println))

(defn- print-success []
  (println)
  (println (green (bold "━━━ Quickstart Complete ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")))
  (println)
  (println (green "  Your Boundary project is configured and ready to start!"))
  (println)
  (println "  Next steps:")
  (println "    1. Start the REPL:  " (bold "clojure -M:repl-clj"))
  (println "    2. In the REPL:     " (bold "(require '[integrant.repl :as ig-repl]) (ig-repl/go)"))
  (println "    3. Open browser:    " (bold "http://localhost:3000"))
  (println)
  (println "  Once running:")
  (println "    Web:       " (bold "http://localhost:3000"))
  (println "    Admin:     " (bold "http://localhost:3000/admin"))
  (println "    nREPL:     " (bold "port 7888"))
  (println)
  (println "  Useful commands:")
  (println "    " (bold "bb scaffold") "   — create more modules")
  (println "    " (bold "bb guide") "      — contextual help and guidance")
  (println "    " (bold "(commands)") "    — in the REPL, see all helpers")
  (println))

(defn -main [& args]
  (let [preset-idx (.indexOf (vec args) "--preset")
        preset-name (when (and (>= preset-idx 0) (< (inc preset-idx) (count args)))
                      (nth args (inc preset-idx)))
        preset      (when preset-name (resolve-preset preset-name))]
    (when (and preset-name (not preset))
      (println (red (str "Unknown preset: " preset-name)))
      (println (dim (str "Available presets: " (str/join ", " (sort (keys presets))))))
      (System/exit 1))

    (print-banner)

    ;; Step 1: Check environment prerequisites (critical — abort on failure)
    (run-step 1 6 "Checking development environment"
              ["bb" "doctor:env" "--ci"])

    ;; Step 2: Run setup
    (if preset
      (do (println (dim (str "\n  Using preset: " preset-name " (" (:description preset) ")")))
          (run-step 2 6 "Running configuration setup"
                    ["bb" "setup" "--database" (:database preset)]))
      (run-step 2 6 "Running configuration setup"
                ["bb" "setup"]))

    ;; Step 3: Validate configuration (critical — abort on failure)
    (run-step 3 6 "Validating configuration"
              ["bb" "doctor" "--ci"])

    ;; Step 4: Scaffold a sample module (non-critical — continue on failure)
    (run-step 4 6 "Scaffolding sample module"
              ["bb" "scaffold" "generate" "tasks" "title:string" "done:boolean"]
              :continue? true)

    ;; Step 5: Run migrations (critical — abort on failure)
    (run-step 5 6 "Running database migrations"
              ["bb" "migrate" "up"])

    ;; Step 6: Verify project structure
    (run-step 6 6 "Verifying project structure"
              ["bb" "smoke-check"])

    (print-success)))

;; Run when executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
