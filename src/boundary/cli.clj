(ns boundary.cli
  "Main CLI entry point for Boundary framework.
   
   Provides command-line interface for enabled modules.
   Currently only the user module is wired, but selection is driven by config."
  (:require [boundary.config :as config]
            [boundary.shell.modules :as modules]
            [boundary.user.shell.cli-entry :as user-cli-entry]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main
  "CLI main entry point.
   
   Delegates to the appropriate module CLI implementation(s) based on
   configuration and exits with the returned status code."
  [& args]
  (log/info "Starting Boundary CLI" {:args args})
  (let [cfg (config/load-config)
        enabled (modules/enabled-modules cfg)
        ;; Map of module keyword to its CLI runner function
        module->runner {:user user-cli-entry/run!}
        status (modules/dispatch-cli enabled module->runner args)]
    (System/exit status)))
