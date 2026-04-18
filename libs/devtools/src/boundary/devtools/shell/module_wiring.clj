(ns boundary.devtools.shell.module-wiring
  "Integrant lifecycle methods for the devtools library."
  (:require [integrant.core :as ig]
            [clojure.tools.logging :as log]))

;; =============================================================================
;; Guidance component
;; =============================================================================

(defmethod ig/init-key :boundary/guidance [_ config]
  (let [level (get config :guidance-level :full)]
    (log/info "Guidance engine started" {:level level})
    (atom {:guidance-level level
           :shown-tips     #{}})))

(defmethod ig/halt-key! :boundary/guidance [_ state]
  (log/info "Guidance engine stopped")
  (reset! state nil))
