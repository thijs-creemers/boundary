(ns ecommerce.main
  "E-commerce API entry point."
  (:require [ecommerce.system :as system])
  (:gen-class))

(defn -main
  "Start the e-commerce API server."
  [& _args]
  (println "Starting E-commerce API...")
  (let [sys (system/start!)]
    (println "E-commerce API running on http://localhost:3002")
    (println "Press Ctrl+C to stop.")
    ;; Keep the main thread alive
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (println "\nShutting down...")
                                 (system/stop! sys))))
    @(promise)))
