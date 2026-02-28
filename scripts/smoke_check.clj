#!/usr/bin/env bb
;; scripts/smoke_check.clj
;;
;; Command smoke checks: verify deps.edn aliases and key tool entrypoints.
;;
;; Usage (babashka):
;;   bb scripts/smoke_check.clj
;;   bb smoke-check              (via bb.edn task)

(ns smoke-check
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [babashka.process :refer [shell]]))

(def root-dir (System/getProperty "user.dir"))

(def required-aliases [:migrate :test :repl-clj :docs-lint])

(defn load-deps-aliases []
  (let [deps-file (io/file root-dir "deps.edn")
        content (slurp deps-file)
        deps (edn/read-string content)]
    (set (keys (:aliases deps)))))

(defn check-aliases []
  (println "[smoke] Verifying required aliases exist in deps.edn")
  (let [known (load-deps-aliases)]
    (doseq [a required-aliases]
      (if (contains? known a)
        (println (str "[smoke] OK alias " a))
        (do
          (binding [*out* *err*]
            (println (str "[smoke] Missing required alias in deps.edn: " a)))
          (System/exit 1))))))

(defn run-check [label & cmd]
  (println (str "[smoke] " label))
  (apply shell {:out :string} cmd))

(defn -main []
  (check-aliases)
  (run-check "Checking migrate CLI entrypoint" "clojure" "-M:migrate" "--help")
  (run-check "Checking test runner entrypoint" "clojure" "-M:test" "--help")
  (run-check "Running docs lint" "clojure" "-M:docs-lint")
  (run-check "Running AGENTS link check" "bb" "scripts/check_agents_links.clj")
  (println "[smoke] Command smoke checks passed"))

;; Run when executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
