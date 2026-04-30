(ns boundary.cli.main
  (:require [clojure.string :as str]))

(defn- usage []
  (println "boundary — Boundary Framework project tool")
  (println)
  (println "Commands:")
  (println "  boundary new <project-name>       Create a new project")
  (println "  boundary add <module>             Add a module to the current project")
  (println "  boundary list modules             List available modules")
  (println "  boundary list modules --json      Machine-readable module list")
  (println "  boundary version                  Show CLI version"))

(defn -main [& args]
  (let [[cmd & rest-args] args]
    (case cmd
      "new"     (do (require 'boundary.cli.new)
                    ((resolve 'boundary.cli.new/-main) rest-args))
      "add"     (do (require 'boundary.cli.add)
                    ((resolve 'boundary.cli.add/-main) rest-args))
      "list"    (do (require 'boundary.cli.list-modules)
                    ((resolve 'boundary.cli.list-modules/-main) rest-args))
      "version" (println "boundary CLI version 1.0.0-alpha-1")
      (do (when cmd (println (str "Unknown command: " cmd "\n")))
          (usage)
          (System/exit (if cmd 1 0))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))