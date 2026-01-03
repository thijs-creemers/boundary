(ns boundary.main
  "Main entry point for Boundary application uberjar.
   
   Provides unified entry point that can run the application in different modes:
   - server: Start HTTP server (default)
   - cli: Run CLI commands
   
   Usage:
     java -jar boundary-standalone.jar              # Start HTTP server
     java -jar boundary-standalone.jar server       # Start HTTP server explicitly
     java -jar boundary-standalone.jar cli [args]   # Run CLI commands"
  (:require [boundary.config :as config]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig])
  (:gen-class))

(defn- print-usage
  "Print usage information."
  []
  (println "Boundary Framework")
  (println)
  (println "Usage:")
  (println "  java -jar boundary.jar [mode] [options]")
  (println)
  (println "Modes:")
  (println "  server  - Start HTTP server (default)")
  (println "  cli     - Run CLI commands")
  (println "  help    - Show this help message")
  (println)
  (println "Environment Variables:")
  (println "  HTTP_PORT           - HTTP server port (default: 3000)")
  (println "  HTTP_HOST           - HTTP server host (default: 0.0.0.0)")
  (println "  ENV                 - Environment profile (dev, prod, test)")
  (println)
  (println "Examples:")
  (println "  java -jar boundary.jar")
  (println "  java -jar boundary.jar server")
  (println "  ENV=prod java -jar boundary.jar server")
  (println "  java -jar boundary.jar cli user list"))

(defn- start-server!
  "Start the HTTP server and block."
  []
  (log/info "Starting Boundary HTTP server")
  (try
    (let [cfg (config/load-config)
          ig-config (config/ig-config cfg)
          system (ig/init ig-config)]
      (log/info "Boundary HTTP server started successfully")
      (log/info "Press Ctrl+C to stop the server")

      ;; Add shutdown hook for graceful shutdown
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. (fn []
                  (log/info "Shutdown signal received, stopping server...")
                  (try
                    (ig/halt! system)
                    (log/info "Server stopped gracefully")
                    (catch Exception e
                      (log/error e "Error during shutdown"))))))

      ;; Block forever (until Ctrl+C)
      @(promise))
    (catch Exception e
      (log/error e "Failed to start server")
      (System/exit 1))))

(defn- run-cli!
  "Run CLI command and exit with status code."
  [args]
  (log/info "Running Boundary CLI" {:args args})
  (try
    ;; Load CLI namespace dynamically to avoid loading HTTP dependencies
    (require 'boundary.cli)
    (let [cli-main (resolve 'boundary.cli/-main)]
      (apply cli-main args))
    (catch Exception e
      (log/error e "CLI command failed")
      (System/exit 1))))

(defn -main
  "Main entry point for uberjar.
   
   Parses command-line arguments to determine mode:
   - server: Start HTTP server (default if no mode specified)
   - cli: Run CLI commands
   - help: Show usage information"
  [& args]
  (let [mode (first args)
        remaining-args (rest args)]
    (case (str/lower-case (or mode "server"))
      "server"
      (start-server!)

      "cli"
      (run-cli! remaining-args)

      ("help" "-h" "--help")
      (do
        (print-usage)
        (System/exit 0))

      ;; Default: treat as server mode
      (if (nil? mode)
        (start-server!)
        (do
          (println (str "Unknown mode: " mode))
          (println)
          (print-usage)
          (System/exit 1))))))
