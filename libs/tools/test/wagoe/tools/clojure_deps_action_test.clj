(ns wagoe.tools.clojure-deps-action-test
  "The dependency retry has to clear Maven's record of the failure first.

   Maven writes a `*.lastUpdated` marker beside an artifact it could not
   download, and its update policy for releases is `never` — so a later
   resolution reads the marker instead of the network. The four retries in
   `.github/actions/clojure-deps` waited 140 seconds between them and achieved
   nothing, and `~/.m2/repository` is what actions/cache saves, so one 403
   during warm-deps was restored into all 30 isolation cells (BOU-440).

   Asserted by running the shell function against a fixture, not by grepping the
   YAML for a word: what matters is which markers it removes."
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- action-file []
  (let [cwd (System/getProperty "user.dir")]
    (or (first (filter #(.exists ^java.io.File %)
                       [(io/file cwd ".github" "actions" "clojure-deps" "action.yml")
                        (io/file cwd ".." ".." ".github" "actions" "clojure-deps" "action.yml")]))
        (throw (ex-info "clojure-deps action not found" {:cwd cwd})))))

(defn- prefetch-script []
  (->> (get-in (yaml/parse-string (slurp (action-file))) [:runs :steps])
       (filter #(str/includes? (or (:name %) "") "Prefetch"))
       first
       :run))

(deftest ^:unit the-retry-clears-the-failure-before-trying-again
  (let [script (prefetch-script)]

    (testing "the prefetch step was found"
      (is (some? script))
      (is (str/includes? script "for attempt in")))

    (testing "and it clears cached failures inside the retry loop, before resolving"
      (let [loop-start (str/index-of script "for attempt in")
            clear-call (str/index-of script "clear_failed_downloads\n" loop-start)
            resolve-at (str/index-of script "clojure -Sthreads 1 -P" loop-start)]
        (is (and clear-call resolve-at (< clear-call resolve-at))
            "a retry that does not clear the marker re-reads it and fails the same way")))))

(deftest ^:unit it-clears-a-transfer-failure-and-keeps-a-genuine-absence
  (let [script (prefetch-script)
        fn-start (str/index-of script "clear_failed_downloads() {")
        fn-end (str/index-of script "for alias in")
        fn-src (subs script fn-start fn-end)
        home (str (System/getProperty "java.io.tmpdir") "/wagoe-m2-" (System/nanoTime))
        poisoned (io/file home ".m2/repository/org/slf4j/slf4j-api/1.7.36"
                          "slf4j-api-1.7.36.jar.lastUpdated")
        absent (io/file home ".m2/repository/foo/bar/1.0" "bar-1.0-sources.jar.lastUpdated")]
    (try
      (io/make-parents poisoned)
      (io/make-parents absent)
      ;; What Maven writes when the download failed…
      (spit poisoned (str "#NOTE\n"
                          "https\\://repo.maven.apache.org/maven2/.error="
                          "Could not transfer artifact: status code: 403\n"))
      ;; …and when the artifact simply was never published. Every -sources.jar
      ;; that does not exist has one of these, so clearing them all would add a
      ;; round-trip per artifact on every attempt.
      (spit absent "#NOTE\nhttps\\://repo.maven.apache.org/maven2/.error=\n")

      (let [{:keys [exit out]} (shell/sh "bash" "-c" (str fn-src "\nclear_failed_downloads")
                                         :env {"HOME" home "PATH" (System/getenv "PATH")})]
        (testing "it runs"
          (is (zero? exit)))

        (testing "the transfer failure is gone, so the next attempt reaches the network"
          (is (not (.exists poisoned))))

        (testing "the genuine absence is kept"
          (is (.exists absent)))

        (testing "and it says how many it cleared"
          (is (str/includes? out "cleared 1"))))

      (finally
        (doseq [f (reverse (file-seq (io/file home)))]
          (.delete ^java.io.File f))))))
