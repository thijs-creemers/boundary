(ns boundary.i18n.shell.module-wiring
  "Integrant wiring for the i18n module.

   Config key: :boundary/i18n

   Example (development):
     :boundary/i18n {:catalogue-path \"boundary/i18n/translations\"
                     :default-locale :en
                     :dev? true}

   Example (production):
     :boundary/i18n {:catalogue-path \"boundary/i18n/translations\"
                     :default-locale :en}"
  (:require [boundary.i18n.shell.catalogue :as catalogue]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; =============================================================================
;; Integrant lifecycle
;; =============================================================================

(defmethod ig/init-key :boundary/i18n
  [_ {:keys [catalogue-path default-locale dev?]
      :or   {default-locale :en}}]
  (log/info "Initializing i18n service" {:catalogue-path catalogue-path
                                         :default-locale default-locale})
  (let [data      (catalogue/load-catalogue catalogue-path)
        cat       (catalogue/create-map-catalogue data)
        locales   (set (keys data))]
    (log/info "i18n service initialized" {:locales locales})
    {:catalogue      cat
     :default-locale default-locale
     :dev?           (boolean dev?)}))

(defmethod ig/halt-key! :boundary/i18n
  [_ _]
  (log/info "Halting i18n service")
  nil)
