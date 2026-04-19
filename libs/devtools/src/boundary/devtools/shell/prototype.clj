(ns boundary.devtools.shell.prototype
  "Orchestrates module generation: scaffold -> migrate -> reset -> summary."
  (:require [boundary.devtools.core.prototype :as core]
            [boundary.scaffolder.core.generators :as gen]
            [clojure.java.io :as io]))

(defn- write-file! [path content]
  (let [f (io/file path)]
    (io/make-parents f)
    (spit f content)
    path))

(defn- generate-module-files!
  "Generate all files for a module using scaffolder generators."
  [module-name ctx generators]
  (let [gen-set  (set generators)
        src-dir  (str "libs/" module-name "/src/boundary/" module-name)
        files    (atom [])]
    (when (contains? gen-set :schema)
      (swap! files conj (write-file! (str src-dir "/schema.clj")
                                     (gen/generate-schema-file ctx))))
    (when (contains? gen-set :ports)
      (swap! files conj (write-file! (str src-dir "/ports.clj")
                                     (gen/generate-ports-file ctx))))
    (when (contains? gen-set :core)
      (swap! files conj (write-file! (str src-dir "/core/validation.clj")
                                     (gen/generate-core-file ctx))))
    (when (contains? gen-set :service)
      (swap! files conj (write-file! (str src-dir "/shell/service.clj")
                                     (gen/generate-service-file ctx))))
    (when (contains? gen-set :persistence)
      (swap! files conj (write-file! (str src-dir "/shell/persistence.clj")
                                     (gen/generate-persistence-file ctx))))
    (when (contains? gen-set :http)
      (swap! files conj (write-file! (str src-dir "/shell/http.clj")
                                     (gen/generate-http-file ctx))))
    (swap! files conj (write-file! (str "libs/" module-name "/deps.edn")
                                   (gen/generate-project-deps module-name)))
    @files))

(defn scaffold!
  "Generate module files from a name and field spec.
   Does NOT integrate, migrate, or reset.

   opts keys:
     :fields    - vector of [field-name malli-spec] pairs
     :endpoints - vector of endpoint keywords (default [:crud])"
  [module-name opts]
  (let [spec       {:fields    (:fields opts)
                    :endpoints (or (:endpoints opts) [:crud])}
        ctx        (core/build-scaffold-context module-name spec)
        generators (core/endpoints-to-generators (:endpoints spec))
        files      (generate-module-files! module-name ctx generators)]
    (println (format "\n=> Module '%s' generated at libs/%s/" module-name module-name))
    (println "\nGenerated files:")
    (doseq [f files] (println (str "  " f)))
    (println "\nNext steps:")
    (println (format "  1. Review schema:  libs/%s/src/boundary/%s/schema.clj" module-name module-name))
    (println (format "  2. Wire module:    bb scaffold integrate %s" module-name))
    (println (format "  3. Add migration:  bb migrate create add-%s-table" module-name))
    (println (format "  4. Run tests:      clojure -M:test:db/h2 :%s" module-name))
    files))

(defn prototype!
  "Generate a complete working module: scaffold + migrate + reset.
   reset-fn should be the REPL's reset function (e.g. integrant.repl/reset).

   spec keys:
     :fields    - vector of [field-name malli-spec] pairs
     :endpoints - vector of endpoint keywords (default [:crud])"
  [module-name spec reset-fn]
  (let [generators (core/endpoints-to-generators (or (:endpoints spec) [:crud]))
        ctx        (core/build-scaffold-context module-name spec)
        files      (generate-module-files! module-name ctx generators)
        migration-num     (System/currentTimeMillis)
        migration-content (gen/generate-migration-file ctx migration-num)
        migration-path    (format "resources/migrations/%d-add-%s-table.sql"
                                  migration-num module-name)]
    (write-file! migration-path migration-content)
    (println (format "\n=> Module '%s' prototyped:" module-name))
    (println "\nGenerated files:")
    (doseq [f (conj files migration-path)] (println (str "  " f)))
    (println "\nRunning migration...")
    (let [result (clojure.java.shell/sh "bb" "migrate" "up")]
      (if (zero? (:exit result))
        (println "  => Migration applied")
        (println (str "  Warning: Migration failed: " (:err result)))))
    (println "\nResetting system to load new module...")
    (reset-fn)
    (println (format "\n=> Module '%s' is live!" module-name))
    (println (format "  Try: (simulate :get \"/api/%s\")" module-name))))
