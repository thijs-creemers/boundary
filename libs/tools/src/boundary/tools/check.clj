#!/usr/bin/env bb
;; libs/tools/src/boundary/tools/check.clj
;;
;; Unified Quality Check — aggregates all quality checks into one command.
;;
;; Usage (via bb.edn task):
;;   bb check                      # Run all quality checks
;;   bb check --quick              # Run only FC/IS and dependency checks
;;   bb check --fix                # Pass --fix to kondo linter
;;   bb check --ci                 # Exit non-zero on any check failure
;;   bb check --help               # Print usage

(ns boundary.tools.check
  (:require [boundary.tools.ansi :refer [bold green red dim]]
            [babashka.process :as process]
            [clojure.string :as str]))

;; =============================================================================
;; Check definitions
;; =============================================================================

(def all-checks
  [{:id    :fcis
    :label "FC/IS boundaries"
    :cmd   ["bb" "check:fcis"]}
   {:id    :deps
    :label "Dependency direction"
    :cmd   ["bb" "check:deps"]}
   {:id    :placeholder-tests
    :label "Placeholder tests"
    :cmd   ["bb" "check:placeholder-tests"]}
   {:id    :linting
    :label "Linting"
    :cmd   ["clojure" "-M:clj-kondo" "--lint" "src" "test" "libs/*/src" "libs/*/test"]}
   {:id    :doctor
    :label "Config doctor"
    :cmd   ["bb" "doctor"]}])

(def quick-check-ids
  "Check IDs included in --quick mode."
  #{:fcis :deps})

;; =============================================================================
;; Check execution
;; =============================================================================

(defn- run-check
  "Run a single check subprocess. Returns a result map with :id, :label, :exit, :duration-ms, and :output."
  [{:keys [id label cmd]} opts]
  (let [cmd (if (and (= id :linting) (:fix opts))
              (conj (vec cmd) "--fix")
              cmd)
        start   (System/currentTimeMillis)
        result  (process/shell {:continue true
                                :out :string
                                :err :string}
                               (str/join " " cmd))
        end     (System/currentTimeMillis)]
    {:id          id
     :label       label
     :exit        (:exit result)
     :duration-ms (- end start)
     :output      (str (:out result) (:err result))}))

(defn- format-duration
  "Format milliseconds as seconds with one decimal place."
  [ms]
  (format "%.1fs" (/ ms 1000.0)))

(defn- print-check-result
  "Print the result of a single check as it completes."
  [{:keys [label exit duration-ms output]}]
  (let [icon     (if (zero? exit) (green "✓") (red "✗"))
        duration (format-duration duration-ms)
        padded   (format "%-22s" label)]
    (println (str icon " " padded " (" duration ")"))
    (when (not (zero? exit))
      (let [lines    (str/split-lines (str/trim output))
            ;; Show a brief summary — last few meaningful lines
            summary  (->> lines
                          (remove str/blank?)
                          (take-last 10))]
        (doseq [line summary]
          (println (dim (str "  " line))))))))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(defn- parse-args [args]
  (loop [[flag & more :as remaining] args
         opts {:quick false :fix false :ci false}]
    (cond
      (empty? remaining) opts
      (or (= flag "--help") (= flag "-h")) (assoc opts :help true)
      (= flag "--quick") (recur more (assoc opts :quick true))
      (= flag "--fix")   (recur more (assoc opts :fix true))
      (= flag "--ci")    (recur more (assoc opts :ci true))
      :else              (recur more opts))))

(defn- print-help []
  (println (bold "bb check") " — Unified quality check for Boundary")
  (println)
  (println "Usage:")
  (println "  bb check                 Run all quality checks")
  (println "  bb check --quick         Run only FC/IS and dependency checks (fast)")
  (println "  bb check --fix           Pass --fix to kondo linter")
  (println "  bb check --ci            Exit non-zero on any check failure")
  (println)
  (println "Checks:")
  (println "  fcis               FC/IS boundary enforcement")
  (println "  deps               Library dependency direction + cycle detection")
  (println "  placeholder-tests  Detect (is true) placeholder assertions")
  (println "  linting            clj-kondo lint across all sources")
  (println "  doctor             Config doctor validation"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (let [opts (parse-args args)]
    (when (:help opts)
      (print-help)
      (System/exit 0))
    (let [checks  (if (:quick opts)
                    (filter #(contains? quick-check-ids (:id %)) all-checks)
                    all-checks)
          _       (do (println)
                      (println (bold "Boundary Quality Check"))
                      (println (dim "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"))
                      (println))
          start   (System/currentTimeMillis)
          results (mapv (fn [check]
                          (let [result (run-check check opts)]
                            (print-check-result result)
                            result))
                        checks)
          end     (System/currentTimeMillis)
          passed  (count (filter #(zero? (:exit %)) results))
          failed  (count (filter #(not (zero? (:exit %))) results))
          total   (format-duration (- end start))]
      (println)
      (println (str "Summary: " (green (str passed " passed"))
                    ", " (red (str failed " failed"))
                    " (" total " total)"))
      (println)
      (when (and (:ci opts) (pos? failed))
        (System/exit 1)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
