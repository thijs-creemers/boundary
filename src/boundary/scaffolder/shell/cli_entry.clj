(ns boundary.scaffolder.shell.cli-entry
  "Scaffolder module CLI entrypoint wrapper.

   Encapsulates scaffolder-specific CLI startup so that the top-level CLI can
   remain as module-agnostic as possible and delegate into this module."
  (:require [boundary.scaffolder.cli :as scaffolder-cli]
            [boundary.scaffolder.shell.service :as scaffolder-service]
            [boundary.shell.adapters.filesystem.core :as fs]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn run-scaffolder-cli!
  "Run the scaffolder module CLI for the given command-line arguments.

   Returns an integer exit status. Does not call System/exit."
  [args]
  (let [exit-status (atom 1)]
    (try
      (log/info "Starting Boundary Scaffolder CLI" {:args args})

      ;; Create file system adapter (writing to current directory)
      (let [fs-adapter (fs/create-file-system-adapter ".")
            ;; Create scaffolder service
            scaffolder-svc (scaffolder-service/create-scaffolder-service fs-adapter)
            ;; Dispatch CLI commands and capture exit status
            status (scaffolder-cli/run-cli! scaffolder-svc args)]
        
        ;; Ensure we always store an integer exit status
        (reset! exit-status (if (integer? status) status 1)))

      (catch Exception e
        (log/error "Scaffolder CLI execution failed" {:error (.getMessage e)})
        (binding [*out* *err*]
          (println "Fatal error:" (.getMessage e)))
        (reset! exit-status 1)))
    @exit-status))

(defn -main
  "CLI main entry point for scaffolder module.
   
   Exits with the returned status code."
  [& args]
  (System/exit (run-scaffolder-cli! args)))
