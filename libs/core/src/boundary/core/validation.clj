(ns boundary.core.validation
  "Legacy validation namespace maintained for backward compatibility.
  
   This namespace provides the original validation interface while supporting
   the new structured result format when BND_DEVEX_VALIDATION is enabled.
   
   New code should use:
     - boundary.core.validation.result
     - boundary.core.validation.registry
     - boundary.core.validation.codes
   
   This namespace will delegate to new implementations when feature flag is enabled."
  (:require [malli.core :as m]
            [boundary.core.validation.result :as vr]))

(defn validate-with-transform
  "Validate data with transformation (legacy interface).
  
   When BND_DEVEX_VALIDATION is enabled, returns enhanced result format.
   Otherwise maintains legacy format for backward compatibility.
  
   Args:
     schema: Malli schema
     data: Data to validate
     transformer: Malli transformer
  
   Returns:
     {:valid? boolean :data map :errors ...} (format depends on feature flag)"
  [schema data transformer]
  (let [result (m/decode schema data transformer)
        valid? (m/validate schema result)]
    (if (vr/devex-validation-enabled?)
      ;; Enhanced format with structured errors
      (if valid?
        (vr/success-result result)
        (let [explanation (m/explain schema result)
              ;; Convert Malli errors to structured format
              errors (mapv (fn [err]
                             (vr/error-map
                              (first (:path err))
                              :invalid-format
                              (or (:message err) "Validation failed")
                              {:path (:path err)
                               :params {:value (:value err)
                                        :schema (:schema err)}}))
                           (:errors explanation))]
          (vr/failure-result errors)))
      ;; Legacy format
      (if valid?
        {:valid? true :data result}
        {:valid? false :errors (m/explain schema result)}))))

(defn validation-passed?
  "Check if validation passed (works with legacy and new format).
   
    Args:
      result: Validation result map
    
    Returns:
      Boolean indicating if validation passed"
   [result]
   (vr/validation-passed? result))

(defn get-validation-errors
  "Get validation errors (works with legacy and new format)."
  [result]
  (when (and result (not (validation-passed? result)))
    (:errors result)))

(defn get-validated-data
  "Get validated data (works with legacy and new format)."
  [result]
  (vr/get-validated-data result))

(defn validate-cli-args
  "Validate CLI arguments (legacy convenience function)."
  [schema args transformer]
  (validate-with-transform schema args transformer))

(defn validate-request
  "Validate API request (legacy convenience function)."
  [schema request transformer]
  (validate-with-transform schema request transformer))

;; =============================================================================
;; New Enhanced API (delegates to validation.result)
;; =============================================================================

(defn success-result
  "Create success result (delegates to validation.result)."
  ([data] (vr/success-result data))
  ([data warnings] (vr/success-result data warnings)))

(defn failure-result
  "Create failure result (delegates to validation.result)."
  ([errors] (vr/failure-result errors))
  ([errors warnings] (vr/failure-result errors warnings)))

(defn error-map
  "Create structured error map (delegates to validation.result)."
  ([field code message] (vr/error-map field code message))
  ([field code message opts] (vr/error-map field code message opts)))

(defn devex-enabled?
  "Check if DevEx validation features are enabled.
   
    Returns:
      Boolean indicating if DevEx validation is enabled"
   []
   (vr/devex-validation-enabled?))