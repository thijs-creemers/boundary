(ns boundary.devtools.shell.repl-error-handler
  "REPL error handler — runs the error pipeline and stores the last exception.
   This is a shell namespace: it performs I/O (printing).
   Usage from user.clj:
     Wrap public REPL functions with try/catch that calls handle-repl-error!
     The zero-arity (fix!) reads from last-exception*."
  (:require [boundary.devtools.core.error-classifier :as classifier]
            [boundary.devtools.core.error-enricher :as enricher]
            [boundary.devtools.core.error-formatter :as formatter]))

(defonce last-exception* (atom nil))

(defn handle-repl-error!
  "Run the full error pipeline on an exception and print the result.
   Stores the exception in last-exception* for (fix!) to access.
   Pipeline: classify → enrich → format → print
   Falls back to standard output + AI hint for unclassified errors."
  [^Throwable exception]
  (when exception
    (reset! last-exception* exception)
    (let [classified (classifier/classify exception)]
      (if (:code classified)
        (let [enriched  (enricher/enrich classified)
              formatted (formatter/format-enriched-error enriched)]
          (println formatted))
        (println (formatter/format-unclassified-error exception))))))
