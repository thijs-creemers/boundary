(ns existing.dir.core
  "Main entry point for existing-dir."
  (:require [boundary.platform.shell.system :as system]
            [integrant.core :as ig]))

(defn -main [& args]
  (let [config (system/load-config)]
    (ig/init config)))
