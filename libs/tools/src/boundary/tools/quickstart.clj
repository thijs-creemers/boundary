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
  (println (green "  Your Boundary project is ready!"))
  (println)
  (println "  Web:       " (bold "http://localhost:3000"))
  (println "  Admin:     " (bold "http://localhost:3000/admin"))
  (println "  nREPL:     " (bold "port 7888"))
  (println)
  (println "  Next steps:")
  (println "    1. Connect your editor to nREPL on port 7888")
  (println "    2. Run " (bold "bb scaffold") " to create your first module")
  (println "    3. Run " (bold "(commands)") " in the REPL to see all helpers")
  (println))

(defn -main [& args]
  (let [preset-idx (.indexOf (vec args) "--preset")
        preset     (when (and (>= preset-idx 0) (< (inc preset-idx) (count args)))
                     (nth args (inc preset-idx)))]
    (print-banner)

    ;; Step 1: Check environment prerequisites (critical — abort on failure)
    (run-step 1 5 "Checking development environment"
              ["bb" "doctor:env" "--ci"])

    ;; Step 2: Run setup
    (if preset
      (do (println (dim (str "\n  Running non-interactive setup with preset: " preset)))
          (run-step 2 5 "Running configuration setup"
                    ["bb" "setup" "--database" preset]))
      (run-step 2 5 "Running configuration setup"
                ["bb" "setup"]))

    ;; Step 3: Validate configuration (critical — abort on failure)
    (run-step 3 5 "Validating configuration"
              ["bb" "doctor" "--ci"])

    ;; Step 4: Run migrations (critical — abort on failure)
    (run-step 4 5 "Running database migrations"
              ["bb" "migrate" "up"])

    ;; Step 5: Verify project structure
    (run-step 5 5 "Verifying project structure"
              ["bb" "smoke-check"])

    (print-success)))

;; Run when executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
