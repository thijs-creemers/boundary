(ns boundary.i18n.schema
  "Malli validation schemas for the i18n module."
  (:require [malli.core :as m]))

;; =============================================================================
;; I18nConfig — Integrant component configuration
;; =============================================================================

(def I18nConfig
  "Configuration map for the :boundary/i18n Integrant component."
  [:map
   [:catalogue-path :string]
   [:default-locale {:optional true} :keyword]
   [:dev?           {:optional true} :boolean]])

;; =============================================================================
;; Validation helpers
;; =============================================================================

(defn valid-i18n-config?
  "Returns true if the given map satisfies I18nConfig schema."
  [config]
  (m/validate I18nConfig config))
