(ns todo.main
  "Runnable entry point: `clojure -M:run`.

   Boots an in-memory H2 database, then drives the todo service through a small
   workflow (add a few todos, complete one, list them). This is a service-layer
   demonstration of the Functional Core / Imperative Shell pattern — no HTTP
   server, so it runs to completion in seconds and needs no configuration."
  (:require [next.jdbc :as jdbc]
            [todo.core.todo :as core]
            [todo.shell.persistence :as db]
            [todo.shell.service :as svc]))

(defn -main [& _args]
  ;; DB_CLOSE_DELAY=-1 keeps the in-memory DB alive across connections for the
  ;; lifetime of the JVM (otherwise H2 drops the schema when a connection closes).
  (let [ds  (jdbc/get-datasource {:dbtype "h2:mem" :dbname "todo;DB_CLOSE_DELAY=-1"})
        now (java.time.Instant/now)
        repo (db/->H2TodoRepository ds)]
    (db/create-table! ds)

    (println "Adding todos…")
    (svc/add-todo! repo "Write the docs" now)
    (svc/add-todo! repo "Ship the example" now)
    (let [milk (svc/add-todo! repo "Buy milk" now)]
      (svc/complete! repo (:id milk)))

    (let [todos (svc/list-todos repo)]
      (println (format "%d todos, %d remaining:"
                       (count todos) (count (core/remaining todos))))
      (doseq [t todos]
        (println (format "  [%s] %s" (if (:done? t) \x \space) (:title t)))))

    (println "Done.")
    (System/exit 0)))
