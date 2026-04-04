#!/usr/bin/env bb
;; boundary-tools/src/boundary/tools/check_tests.clj
;;
;; Detects placeholder test assertions like (is true) and (is (= true true))
;; that pass green but provide no real coverage.

(ns boundary.tools.check-tests
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.tools.ansi :as ansi]))

;; ---------------------------------------------------------------------------
;; Placeholder patterns
;; ---------------------------------------------------------------------------

(def ^:private placeholder-patterns
  "Regex patterns matching placeholder assertions."
  [#"\(\s*is\s+true\s*\)"
   #"\(\s*is\s+\(\s*=\s+true\s+true\s*\)\s*\)"])

;; ---------------------------------------------------------------------------
;; File scanning
;; ---------------------------------------------------------------------------

(defn- test-clj-files
  "Find all .clj files under libs/*/test/, test/, and boundary-tools/test/."
  []
  (let [root      (io/file (System/getProperty "user.dir"))
        libs-dir  (io/file root "libs")
        top-test  (io/file root "test")
        tools-test (io/file root "boundary-tools" "test")
        lib-tests (when (.exists libs-dir)
                    (->> (.listFiles libs-dir)
                         (filter #(.isDirectory %))
                         (mapcat (fn [lib-dir]
                                   (let [test-dir (io/file lib-dir "test")]
                                     (when (.exists test-dir)
                                       (file-seq test-dir)))))))
        top-tests (when (.exists top-test)
                    (file-seq top-test))
        tools-tests (when (.exists tools-test)
                      (file-seq tools-test))]
    (->> (concat lib-tests top-tests tools-tests)
         (filter #(and (.isFile %)
                       (str/ends-with? (.getName %) ".clj"))))))

(defn- scan-file
  "Scan a file for placeholder assertions. Returns seq of match maps."
  [file]
  (let [lines (str/split-lines (slurp file))]
    (->> lines
         (map-indexed
          (fn [idx line]
            (when (some #(re-find % line) placeholder-patterns)
              {:file    (str file)
               :line    (inc idx)
               :content (str/trim line)})))
         (remove nil?))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (let [files   (test-clj-files)
        matches (mapcat scan-file files)]
    (if (seq matches)
      (do
        (println (ansi/red "Placeholder test assertions found:"))
        (println)
        (doseq [{:keys [file line content]} matches]
          (println (str "  " file ":" line ": " content)))
        (println)
        (println (str (count matches) " placeholder(s) found. Replace with meaningful assertions."))
        (System/exit 1))
      (do
        (println (ansi/green "No placeholder tests found.") (str (count files) " test file(s) scanned."))
        (System/exit 0)))))
