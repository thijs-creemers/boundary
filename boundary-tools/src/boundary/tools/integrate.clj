#!/usr/bin/env bb
;; boundary-tools/src/boundary/tools/integrate.clj
;;
;; Module Integration — wire a scaffolded module into deps.edn, tests.edn, wiring.clj.
;;
;; Usage (via bb.edn task):
;;   bb scaffold integrate product            # Wire the "product" module
;;   bb scaffold integrate product --dry-run  # Preview changes without writing

(ns boundary.tools.integrate
  (:require [boundary.tools.ansi :refer [bold green red cyan yellow dim]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Discovery
;; =============================================================================

(defn- root-dir [] (System/getProperty "user.dir"))

(defn- discover-module
  "Discover a scaffolded module under libs/<name>/.
   Returns a map of {:name :src-dir :test-dir :has-routes? :has-wiring?}
   or nil if the module doesn't exist."
  [module-name]
  (let [lib-dir  (io/file (root-dir) "libs" module-name)
        src-dir  (io/file lib-dir "src" "boundary" module-name)
        test-dir (io/file lib-dir "test" "boundary" module-name)]
    (when (.exists lib-dir)
      (let [has-routes? (or (.exists (io/file src-dir "shell" "http"))
                            (.exists (io/file src-dir "shell" "http.clj")))
            has-wiring? (.exists (io/file src-dir "shell" "module_wiring.clj"))]
        {:name        module-name
         :lib-dir     (.getPath lib-dir)
         :src-path    (str "libs/" module-name "/src")
         :test-path   (str "libs/" module-name "/test")
         :src-dir     (.getPath src-dir)
         :test-dir    (.getPath test-dir)
         :has-routes? has-routes?
         :has-wiring? has-wiring?}))))

;; =============================================================================
;; Text-based file patching
;; =============================================================================

(defn- insert-after-last
  "Insert `new-line` after the last occurrence of a line matching `pattern` in `text`.
   Returns [updated-text inserted?]."
  [text pattern new-line]
  (let [lines      (str/split-lines text)
        ;; Scan backwards to find the last matching line
        last-index (loop [i (dec (count lines))]
                     (cond
                       (< i 0) -1
                       (re-find pattern (nth lines i)) i
                       :else (recur (dec i))))]
    (if (neg? last-index)
      [text false]
      (let [before (subvec (vec lines) 0 (inc last-index))
            after  (subvec (vec lines) (inc last-index))]
        [(str/join "\n" (concat before [new-line] after)) true]))))

(defn patch-deps-edn
  "Add source and test paths for the module to deps.edn.
   Returns [updated-text changes-list]."
  [deps-text module-name]
  (let [src-entry  (str "\"libs/" module-name "/src\"")
        test-entry (str "\"libs/" module-name "/test\"")]
    (if (str/includes? deps-text src-entry)
      [deps-text []]
      ;; Find the last "libs/*/test" line and insert after it
      (let [[text1 ok1] (insert-after-last
                         deps-text
                         #"\"libs/[a-z-]+/test\""
                         (str "           " src-entry " " test-entry))
            ;; Also try "libs/*/src" pattern as fallback
            [text2 ok2] (if ok1
                          [text1 true]
                          (insert-after-last
                           deps-text
                           #"\"libs/[a-z-]+/src\""
                           (str "           " src-entry " " test-entry)))]
        (if (or ok1 ok2)
          [text2 [(str "Added " src-entry " " test-entry " to :paths")]]
          [deps-text [(red "Could not find insertion point in deps.edn — add paths manually")]])))))

(defn- insert-unit-test-path
  "Insert a test path into the :unit suite's :test-paths list.
   Inserts after the last existing libs/*/test entry in the :unit block.
   Returns [updated-text inserted?]."
  [tests-text module-name]
  (let [test-path-entry (str "\"libs/" module-name "/test\"")]
    (if (str/includes? tests-text test-path-entry)
      [tests-text true] ; already present
      ;; Find the :unit suite block and insert after the last "libs/*/test" entry
      ;; within the :test-paths vector that precedes the first :ns-patterns
      (let [lines (vec (str/split-lines tests-text))
            ;; Find the :id :unit line
            unit-idx (first (keep-indexed
                             (fn [i line]
                               (when (re-find #":id\s+:unit" line) i))
                             lines))]
        (if-not unit-idx
          [tests-text false]
          ;; From :unit line, find the last "libs/*/test" line before :ns-patterns
          (let [last-lib-test-idx
                (loop [i (inc unit-idx)
                       last-match nil]
                  (cond
                    (>= i (count lines)) last-match
                    (re-find #":ns-patterns" (nth lines i)) last-match
                    (re-find #"\"libs/[a-z0-9-]+/test\"" (nth lines i)) (recur (inc i) i)
                    :else (recur (inc i) last-match)))]
            (if-not last-lib-test-idx
              [tests-text false]
              (let [;; Match the indentation of the line we're inserting after
                    indent (re-find #"^\s+" (nth lines last-lib-test-idx))
                    new-line (str indent test-path-entry)
                    before (subvec lines 0 (inc last-lib-test-idx))
                    after (subvec lines (inc last-lib-test-idx))]
                [(str/join "\n" (concat before [new-line] after)) true]))))))))

(defn patch-tests-edn
  "Add a per-library test suite entry to tests.edn and add the test path
   to the :unit suite's :test-paths so the default test run includes it.
   Returns [updated-text changes-list]."
  [tests-text module-name]
  (let [suite-id (str ":id :" module-name)]
    (if (str/includes? tests-text suite-id)
      [tests-text []]
      (let [;; Build the new suite entry
            new-suite (str "\n"
                           "          {:id :" module-name "\n"
                           "           :test-paths [\"libs/" module-name "/test\"]\n"
                           "           :ns-patterns [\"boundary." module-name ".*-test\"]}")
            test-path-entry (str "\"libs/" module-name "/test\"")
            ;; First, add test path to the :unit suite
            [text0 ok0] (insert-unit-test-path tests-text module-name)
            ;; Then insert per-library suite after last {:id :xxx} block
            [text1 ok1] (insert-after-last
                         text0
                         #":ns-patterns \[\"boundary\."
                         new-suite)]
        (if ok1
          [text1 (cond-> [(str "Added {:id :" module-name "} test suite")]
                   ok0 (conj (str "Added " test-path-entry " to :unit test-paths"))
                   (not ok0) (conj (str "Note: Also add " test-path-entry " to the :unit test-paths list")))]
          [tests-text [(red "Could not find insertion point in tests.edn — add suite manually")]])))))

(defn patch-wiring
  "Add a module-wiring require to wiring.clj.
   Returns [updated-text changes-list]."
  [wiring-text module-name]
  (let [ns-name    (str/replace module-name "_" "-")
        require-ns (str "boundary." ns-name ".shell.module-wiring")
        entry      (str "            [" require-ns "] ;; Load " ns-name " module init/halt methods")]
    (if (str/includes? wiring-text require-ns)
      [wiring-text []]
      (let [[text ok] (insert-after-last
                       wiring-text
                       #"\[boundary\.[a-z-]+\.shell\.module-wiring\]"
                       entry)]
        (if ok
          [text [(str "Added [" require-ns "] require")]]
          [wiring-text [(red "Could not find insertion point in wiring.clj — add require manually")]])))))

;; =============================================================================
;; Config snippet generation
;; =============================================================================

(defn generate-config-snippet
  "Generate an Integrant config template snippet for a new module."
  [module-name has-routes?]
  (let [ns-name (str/replace module-name "_" "-")]
    (str "  ;; " (str/capitalize ns-name) " module\n"
         "  :boundary/" ns-name "\n"
         "  {:enabled? true"
         (when has-routes?
           (str "\n   :base-path \"/api/" ns-name "\""))
         "}")))

;; =============================================================================
;; Orchestration
;; =============================================================================

(defn integrate-module
  "Wire a module into the project. Returns a map of results."
  [module-name {:keys [dry-run?]}]
  (let [module (discover-module module-name)]
    (when-not module
      (println (red (str "Module not found: libs/" module-name "/")))
      (println (dim "Run `bb scaffold generate` first to create the module."))
      (System/exit 1))

    (println)
    (println (bold (str "Boundary Module Integration — " module-name)))
    (println)
    (println (str "Discovered: " (cyan (str "libs/" module-name "/"))))
    (println (str "  Source: " (dim (:src-path module))))
    (println (str "  Tests:  " (dim (:test-path module))))
    (println (str "  HTTP:   " (if (:has-routes? module) (green "yes") (dim "no"))))
    (println (str "  Wiring: " (if (:has-wiring? module) (green "yes") (dim "no"))))
    (println)

    (let [;; Read files
          deps-file    (io/file (root-dir) "deps.edn")
          tests-file   (io/file (root-dir) "tests.edn")
          wiring-file  (io/file (root-dir) "libs/platform/src/boundary/platform/shell/system/wiring.clj")

          deps-text    (slurp deps-file)
          tests-text   (slurp tests-file)
          wiring-text  (when (.exists wiring-file) (slurp wiring-file))

          ;; Patch
          [new-deps deps-changes]     (patch-deps-edn deps-text module-name)
          [new-tests tests-changes]   (patch-tests-edn tests-text module-name)
          [new-wiring wiring-changes] (if (and wiring-text (:has-wiring? module))
                                        (patch-wiring wiring-text module-name)
                                        [wiring-text []])]

      ;; Print results
      (doseq [change deps-changes]
        (println (str "  " (green "✓") " deps.edn      " change)))
      (doseq [change tests-changes]
        (println (str "  " (green "✓") " tests.edn     " change)))
      (doseq [change wiring-changes]
        (println (str "  " (green "✓") " wiring.clj    " change)))

      (when-not (or (seq deps-changes) (seq tests-changes) (seq wiring-changes))
        (println (dim "  No changes needed — module already integrated.")))

      ;; Write files (unless dry-run)
      (when-not dry-run?
        (when (seq deps-changes)
          (spit deps-file new-deps))
        (when (seq tests-changes)
          (spit tests-file new-tests))
        (when (and wiring-text (seq wiring-changes))
          (spit wiring-file new-wiring)))

      (when dry-run?
        (println)
        (println (yellow "  (dry-run — no files written)")))

      ;; Print manual steps
      (println)
      (println (bold "Manual steps remaining:"))
      (println (str "  1. Add Integrant config to " (cyan "resources/conf/dev/config.edn") ":"))
      (println)
      (println (dim (generate-config-snippet module-name (:has-routes? module))))
      (println)
      (println (str "  2. Add matching config to " (cyan "resources/conf/test/config.edn")))
      (println (str "  3. Run migrations: " (cyan "bb migrate up")))
      (println (str "  4. Verify: " (cyan (str "clojure -M:test:db/h2 :" module-name)))))))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(defn- parse-args [args]
  (loop [[arg & more :as remaining] args
         opts {:dry-run? false :module nil}]
    (cond
      (empty? remaining)          opts
      (= arg "--help")            (assoc opts :help true)
      (= arg "-h")               (assoc opts :help true)
      (= arg "--dry-run")        (recur more (assoc opts :dry-run? true))
      (nil? (:module opts))      (recur more (assoc opts :module arg))
      :else                      (recur more opts))))

(defn- print-help []
  (println (bold "bb scaffold integrate") " — Wire a scaffolded module into the project")
  (println)
  (println "Usage:")
  (println "  bb scaffold integrate <module>            Wire the module")
  (println "  bb scaffold integrate <module> --dry-run  Preview changes without writing")
  (println)
  (println "What it does:")
  (println "  1. Adds source/test paths to deps.edn")
  (println "  2. Adds test suite to tests.edn")
  (println "  3. Adds module-wiring require to wiring.clj")
  (println "  4. Prints Integrant config snippet for manual insertion"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (let [opts (parse-args args)]
    (cond
      (:help opts)
      (print-help)

      (nil? (:module opts))
      (do (println (red "Module name required."))
          (println)
          (print-help)
          (System/exit 1))

      :else
      (integrate-module (:module opts) opts))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
