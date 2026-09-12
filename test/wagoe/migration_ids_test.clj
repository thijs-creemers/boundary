(ns wagoe.migration-ids-test
  "No two migrations on the classpath may share an id.

   Migratus keys applied migrations by their numeric id across every directory
   it reads, so two libraries that happen to pick the same timestamp collide.
   It does not report this: it records the id once, runs whichever it saw first
   and skips the other, and `bb migrate up` says it succeeded. Push and geo were
   given the same id in this PR and geo's schema change silently never ran
   (BOU-431 review).

   A library author cannot see the collision — it needs every library on one
   classpath, which is here."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [migratus.migrations :as migratus-migrations]
            [wagoe.platform.shell.database.migrations :as mig]))

(defn- migration-files
  "Every migration file the runner will read, as {:id :name :dir}."
  []
  (for [dir (mig/discover-migration-dirs)
        :let [resource (io/resource dir)]
        :when (and resource (= "file" (.getProtocol resource)))
        file (file-seq (io/file resource))
        :when (.isFile file)
        :let [parsed (migratus-migrations/parse-name (.getName file))]
        :when parsed]
    {:id   (first parsed)
     :name (.getName file)
     :dir  dir}))

(deftest ^:unit no-two-migrations-share-an-id
  (let [by-id (->> (migration-files)
                   ;; up and down are one migration in two files.
                   (map #(select-keys % [:id :dir]))
                   distinct
                   (group-by :id))
        clashes (into {}
                      (for [[id entries] by-id
                            :when (< 1 (count entries))]
                        [id (mapv :dir entries)]))]

    (testing "the runner sees at least the libraries that ship migrations"
      (is (seq (migration-files))
          "no migrations discovered at all — this test would pass vacuously"))

    (testing "and none of them collide"
      (is (empty? clashes)
          (str "these ids are claimed twice, so one of each pair silently "
               "never runs: "
               (str/join "; " (for [[id dirs] clashes]
                                (str id " in " (str/join " and " dirs)))))))))
