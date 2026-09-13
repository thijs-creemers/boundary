(ns wagoe.tools.dependency-pins-test
  "One version per third-party coordinate, across every deps.edn.

   A library pinned at a different version than the rest does not get that
   version: an application resolves whichever wins the conflict, so the library
   runs on something it was never tested against. audience was two behind on
   the SQL layer it compiles every query with, and push two minor versions
   behind on Ring (BOU-442).

   It also costs CI. Each divergent pin is an artifact no root closure warms,
   which the 30 isolation cells then fetch from Central (BOU-441)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- repo-root []
  (let [cwd (io/file (System/getProperty "user.dir"))]
    (or (first (filter #(.exists (io/file % "deps.edn"))
                       [cwd (io/file cwd ".." "..")]))
        (throw (ex-info "no deps.edn above the working directory" {:cwd (str cwd)})))))

(defn- deps-files []
  (let [root (repo-root)]
    (cons (io/file root "deps.edn")
          (->> (.listFiles (io/file root "libs"))
               (filter #(.isDirectory ^java.io.File %))
               (map #(io/file % "deps.edn"))
               (filter #(.exists ^java.io.File %))
               (sort-by #(.getPath ^java.io.File %))))))

(defn- pins
  "{coordinate {version #{where}}} for every :mvn/version in every deps.edn.

   The framework's own `com.wagoe/*` coordinates are excluded: those move
   together on a release and `bb check:versions` already owns them."
  []
  (reduce
   (fn [acc ^java.io.File f]
     (let [path  (.getPath f)
           where (if-let [[_ lib] (re-find #"/libs/([^/]+)/deps\.edn$" path)]
                   lib
                   "root")]
       (reduce (fn [acc [_ coord version]]
                 (if (str/starts-with? coord "com.wagoe/")
                   acc
                   (update-in acc [coord version] (fnil conj #{}) where)))
               acc
               (re-seq #"([a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+)\s*\{:mvn/version\s+\"([^\"]+)\""
                       (slurp f)))))
   {}
   (deps-files)))

(deftest ^:unit a-third-party-coordinate-is-pinned-at-one-version
  (let [by-coord (pins)]

    (testing "the files were read"
      (is (< 20 (count (deps-files))) "far fewer deps.edn than this repo has")
      (is (< 50 (count by-coord))))

    (testing "and nothing is pinned twice"
      (let [split (into {} (filter (fn [[_ vs]] (< 1 (count vs))) by-coord))]
        (is (empty? split)
            (str "these run on whichever version wins the conflict, not the one "
                 "they were tested against:\n"
                 (str/join "\n"
                           (for [[coord vs] (sort split)]
                             (str "  " coord "\n"
                                  (str/join "\n"
                                            (for [[v where] (sort-by (comp - count val) vs)]
                                              (str "     " v "  " (str/join ", " (sort where))))))))))))))
