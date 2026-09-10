(ns wagoe.tools.migration-manifests-test
  "A library that ships migrations must publish the manifest that reveals them.

   `wagoe.platform.shell.database.migrations` discovers library migrations from
   a `wagoe/migration-paths.edn` resource enumerated off the classpath. A
   library with migration files and no manifest contributes nothing: the runner
   never looks in its directory, `bb migrate up` reports nothing pending, and
   the first query fails on a missing table.

   `libs/push` shipped three migrations and no manifest, so `wagoe add push`
   gave you a module whose tables were never created (BOU-423). Two libraries
   ship migrations today, which makes this the cheapest moment to notice the
   third."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(defn- repo-root []
  (let [cwd (fs/cwd)]
    (if (fs/exists? (fs/path cwd "libs"))
      cwd
      (fs/path cwd ".." ".."))))

(defn- libs-with-migrations
  "{lib-name #{migration-dir-relative-to-resources}} for every library that has
   `*.up.sql` under `resources/`."
  []
  (into {}
        (for [lib  (fs/list-dir (fs/path (repo-root) "libs"))
              :let [resources (fs/path lib "resources")]
              :when (fs/directory? resources)
              :let  [ups (fs/glob resources "**/*.up.sql")]
              :when (seq ups)]
          [(fs/file-name lib)
           (into #{} (map #(str (fs/relativize resources (fs/parent %)) "/")) ups)])))

(defn- declared-paths
  "The migration directories `lib` publishes, or nil when it publishes none."
  [lib]
  (let [manifest (fs/path (repo-root) "libs" lib "resources" "wagoe" "migration-paths.edn")]
    (when (fs/exists? manifest)
      (let [data (edn/read-string (slurp (fs/file manifest)))]
        (set (if (vector? data) data (:paths data)))))))

(deftest ^:unit every-library-with-migrations-publishes-a-manifest
  (let [with-migrations (libs-with-migrations)]

    (testing "the libraries were read — otherwise this passes vacuously"
      (is (seq with-migrations)
          "found no library shipping migrations; the scan is looking in the wrong place"))

    (testing "each one publishes wagoe/migration-paths.edn"
      (doseq [[lib dirs] with-migrations]
        (let [declared (declared-paths lib)]
          (is (some? declared)
              (str lib " ships migrations in " (pr-str (sort dirs))
                   " and publishes no wagoe/migration-paths.edn, so the runner"
                   " never reads them. Add {:paths " (pr-str (vec (sort dirs))) "}"))

          (testing (str lib " declares the directories it actually has")
            (when declared
              (is (empty? (remove declared dirs))
                  (str lib " has migrations in " (pr-str (sort (remove declared dirs)))
                       " which its manifest does not declare")))))))))
