(ns blog.main
  "Application entry point.
   
   Starts the blog application with the specified profile."
  (:require [blog.system :as system])
  (:gen-class))

(defn -main
  "Start the blog application.
   
   Usage:
     clojure -M:run           # Start with :dev profile
     clojure -M:run prod      # Start with :prod profile"
  [& args]
  (let [profile (keyword (or (first args) "dev"))]
    (println (str "Starting blog application with profile: " profile))
    (system/start! profile)
    (println "Blog is running! Visit http://localhost:3001")
    ;; Keep the main thread alive
    @(promise)))
