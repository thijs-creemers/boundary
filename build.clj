(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'tcbv/boundary)
(def version (format "1.2.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")

;; Include database drivers in the uberjar basis
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:db]}))

(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn clean
  "Remove compiled artifacts."
  [_]
  (b/delete {:path "target"}))

(defn uber
  "Build uberjar with all dependencies including database drivers."
  [_]
  (clean nil)
  (println "Building uberjar...")
  (println (str "  Library: " lib))
  (println (str "  Version: " version))
  (println (str "  Output:  " uber-file))

  ;; Copy source and resources
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})

  ;; Compile Clojure namespaces for better startup time
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir
                  :ns-compile '[boundary.main]})

  ;; Build uberjar
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'boundary.main})

  (println (str "✓ Uberjar built successfully: " uber-file))
  (println)
  (println "Run with:")
  (println (str "  java -jar " uber-file))
  (println (str "  java -jar " uber-file " server"))
  (println (str "  java -jar " uber-file " cli user list")))
