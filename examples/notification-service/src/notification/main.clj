(ns notification.main
  "Main entry point for the notification service."
  (:require [notification.system :as system]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

;; =============================================================================
;; CLI Options
;; =============================================================================

(def cli-options
  [["-p" "--port PORT" "HTTP server port"
    :default 3003
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 65536) "Port must be between 0 and 65536"]]
   ["-h" "--help" "Show help"]])

(defn usage [options-summary]
  (->> ["Notification Service - Event-Driven Notification Microservice"
        ""
        "Usage: notification-service [options]"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  Start server:           java -jar notification-service.jar"
        "  Start on custom port:   java -jar notification-service.jar --port 8080"]
       (clojure.string/join \newline)))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defonce system (atom nil))

(defn start! [options]
  (println "Starting Notification Service...")
  (reset! system (system/start-system options))
  (println "")
  (println "Notification Service is ready!")
  (println "")
  (println "Available endpoints:")
  (println "  POST /api/events              - Publish event")
  (println "  GET  /api/events              - List events")
  (println "  GET  /api/events/:id          - Get event")
  (println "  GET  /api/notifications       - List notifications")
  (println "  GET  /api/notifications/:id   - Get notification")
  (println "  POST /api/notifications/:id/retry - Retry failed notification")
  (println "  GET  /health                  - Health check")
  (println "")
  @system)

(defn stop! []
  (when @system
    (println "Stopping Notification Service...")
    (system/stop-system @system)
    (reset! system nil)))

(defn -main [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (do (println (usage summary))
          (System/exit 0))
      
      errors
      (do (println "Errors:")
          (doseq [err errors]
            (println "  " err))
          (println "")
          (println (usage summary))
          (System/exit 1))
      
      :else
      (do
        (start! {:port (:port options)})
        ;; Keep the main thread alive
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. ^Runnable stop!))
        @(promise)))))
