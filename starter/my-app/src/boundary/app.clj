(ns boundary.app
  (:require [clojure.tools.logging :as log]))

(defn hello-world []
  (log/info "Hello from Boundary!")
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello, Boundary!"})
