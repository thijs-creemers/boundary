(ns boundary.ai.shell.repl
  "REPL helper functions for the AI module.

   Provides convenient wrappers for use during interactive development:

     (require '[boundary.ai.shell.repl :as ai])
     (ai/explain *e)
     (ai/sql \"find active users with orders in the last 7 days\")

   The service is resolved from a dynamic var that you can bind
   in your dev/user.clj before using these helpers."
  (:require [boundary.ai.shell.service :as svc]))

;; =============================================================================
;; Service binding
;; =============================================================================

(def ^:dynamic *ai-service*
  "Dynamic var holding the AI service map.
   Bind this in your REPL session or dev/user.clj:

     (alter-var-root #'boundary.ai.shell.repl/*ai-service*
                     (constantly (integrant.repl.state/system :boundary/ai-service)))"
  nil)

(defn set-service!
  "Set the global REPL AI service (convenience for REPL sessions).

   Args:
     service - AIService map from Integrant system

   Returns:
     The service map."
  [service]
  (alter-var-root #'*ai-service* (constantly service))
  service)

(defn- require-service []
  (or *ai-service*
      (throw (ex-info "No AI service bound. Call (ai/set-service! system-service) first."
                      {:hint "Try: (ai/set-service! (integrant.repl.state/system :boundary/ai-service))"}))))

;; =============================================================================
;; Feature 2: Error Explainer REPL wrapper
;; =============================================================================

(defn explain
  "Explain a Clojure exception or stack trace string.

   Usage:
     (ai/explain *e)
     (ai/explain \"clojure.lang.ExceptionInfo: ...\\n\\tat ...\")

   Args:
     exception-or-string - Exception or stack trace string

   Returns:
     Prints explanation to stdout, returns the raw text."
  ([exception-or-string]
   (explain exception-or-string "."))
  ([exception-or-string project-root]
   (let [service    (require-service)
         stacktrace (if (instance? Throwable exception-or-string)
                      (let [sw (java.io.StringWriter.)
                            pw (java.io.PrintWriter. sw)]
                        (.printStackTrace exception-or-string pw)
                        (.toString sw))
                      (str exception-or-string))
         result     (svc/explain-error service stacktrace project-root)]
     (if (:error result)
       (println "\033[31mAI Error:\033[0m" (:error result))
       (println "\n\033[1m=== AI Error Explanation ===\033[0m\n"
                (:text result)
                "\n\n\033[2m[" (:provider result) "/" (:model result)
                " — " (:tokens result) " tokens]\033[0m"))
     (:text result))))

;; =============================================================================
;; Feature 4: SQL Copilot REPL wrapper
;; =============================================================================

(defn sql
  "Generate a HoneySQL query from a natural language description.

   Usage:
     (ai/sql \"find active users with orders in the last 7 days\")

   Args:
     description  - NL description string
     project-root - optional project root path (default \".\")

   Returns:
     Prints HoneySQL + explanation, returns the result map."
  ([description]
   (sql description "."))
  ([description project-root]
   (let [service (require-service)
         result  (svc/sql-from-description service description project-root)]
     (if (:error result)
       (println "\033[31mAI Error:\033[0m" (:error result))
       (do
         (println "\n\033[1m=== HoneySQL ===\033[0m")
         (println (:honeysql result))
         (println "\n\033[1m=== Explanation ===\033[0m")
         (println (:explanation result))
         (println "\n\033[1m=== Raw SQL ===\033[0m")
         (println (:raw-sql result))))
     result)))

;; =============================================================================
;; Feature 3: Test Generator REPL wrapper
;; =============================================================================

(defn gen-tests
  "Generate tests for a source file and print to stdout.

   Usage:
     (ai/gen-tests \"libs/user/src/boundary/user/core/validation.clj\")

   Args:
     source-path - path to the source file

   Returns:
     Generated test source string."
  [source-path]
  (let [service (require-service)
        result  (svc/generate-tests service source-path)]
    (if (:error result)
      (println "\033[31mAI Error:\033[0m" (:error result))
      (do
        (println "\n\033[1m=== Generated Tests ===\033[0m\n")
        (println (:text result))
        (println "\n\033[2m[" (:provider result) "/" (:model result)
                 " — " (:tokens result) " tokens]\033[0m")))
    (:text result)))

;; =============================================================================
;; Help
;; =============================================================================

(defn help []
  (println
   "\033[1mBoundary AI REPL Helpers\033[0m

First, bind the service:
  (require '[boundary.ai.shell.repl :as ai])
  (ai/set-service! (integrant.repl.state/system :boundary/ai-service))

Commands:
  (ai/explain *e)                    — explain last exception
  (ai/sql \"description\")           — generate HoneySQL
  (ai/gen-tests \"path/to/file.clj\") — generate test namespace"))
