(ns boundary.devtools.shell.http-error-middleware
  "Dev-mode HTTP error enrichment middleware.
   Positioned INSIDE wrap-enhanced-exception-handling. Catches exceptions,
   runs the error pipeline, and re-throws with :dev-info attached
   to ex-data so the outer middleware can include it in the RFC 7807 response."
  (:require [boundary.devtools.core.error-classifier :as classifier]
            [boundary.devtools.core.error-enricher :as enricher]
            [boundary.devtools.core.error-formatter :as formatter]))

(defn- build-dev-info
  "Build the :dev-info map from an enriched error."
  [{:keys [code category fix] :as enriched}]
  {:formatted      (if code
                     (formatter/format-enriched-error enriched)
                     (formatter/format-unclassified-error (:exception enriched)))
   :code           code
   :category       category
   :fix-available? (boolean fix)
   :fix-label      (:label fix)})

(defn wrap-dev-error-enrichment
  "Ring middleware that enriches exceptions with dev-info in dev mode.
   Catches exceptions, runs the error pipeline, attaches result as
   :dev-info in the exception's ex-data, and re-throws.
   The outer error handling middleware can then include :dev-info
   in its Problem Details response."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception ex
        (let [classified (classifier/classify ex)
              enriched   (enricher/enrich classified)
              dev-info   (build-dev-info enriched)
              original-data (or (ex-data ex) {})
              enhanced-data (assoc original-data :dev-info dev-info)]
          ;; Pass original exception as cause to preserve full stack trace
          (throw (ex-info (.getMessage ex)
                          enhanced-data
                          ex)))))))
