#!/usr/bin/env bb
;; scripts/deploy.clj
;;
;; Deploy one or more Boundary libraries to Clojars.
;;
;; Usage (via bb.edn task):
;;   bb deploy                         -- show help
;;   bb deploy --all                   -- deploy all 23 libs in dependency order
;;   bb deploy core platform user      -- deploy specific libs
;;   bb deploy --missing               -- deploy only libs not yet on Clojars
;;
;; Usage (direct):
;;   bb scripts/deploy.clj --all
;;
;; Environment:
;;   CLOJARS_USERNAME  your Clojars username
;;   CLOJARS_PASSWORD  your Clojars deploy token

(ns deploy
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.http-client :as http]
            [babashka.process :as p]))

;; =============================================================================
;; ANSI helpers
;; =============================================================================

(defn bold   [s] (str "\033[1m"  s "\033[0m"))
(defn green  [s] (str "\033[32m" s "\033[0m"))
(defn red    [s] (str "\033[31m" s "\033[0m"))
(defn yellow [s] (str "\033[33m" s "\033[0m"))
(defn dim    [s] (str "\033[2m"  s "\033[0m"))

;; =============================================================================
;; Library registry (in dependency order)
;; =============================================================================

(def all-libs
  ["core"
   "observability"
   "platform"
   "i18n"
   "user"
   "storage"
   "scaffolder"
   "cache"
   "jobs"
   "realtime"
   "email"
   "tenant"
   "workflow"
   "search"
   "external"
   "payments"
   "geo"
   "reports"
   "calendar"
   "ai"
   "ui-style"
   "admin"
   "boundary-cli"])

(def valid-libs (set all-libs))
(def root-dir (System/getProperty "user.dir"))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn lib-dir [lib]
  (str (io/file root-dir "libs" lib)))

(defn read-version [lib]
  (let [build-file (io/file (lib-dir lib) "build.clj")]
    (when (.exists build-file)
      (second (re-find #"\(def version \"([^\"]+)\"" (slurp build-file))))))

(defn published? [lib]
  (let [version  (read-version lib)
        artifact (str "boundary-" lib)
        url      (format "https://clojars.org/repo/org/boundary-app/%s/%s/%s-%s.pom"
                         artifact version artifact version)
        response (http/get url {:throw false})]
    (= 200 (:status response))))

(defn check-env! []
  (when (or (str/blank? (System/getenv "CLOJARS_USERNAME"))
            (str/blank? (System/getenv "CLOJARS_PASSWORD")))
    (println (red "Error: CLOJARS_USERNAME and CLOJARS_PASSWORD must be set."))
    (System/exit 1)))

(defn wait-for-indexing []
  (println (dim "  Waiting 30s for Clojars indexing..."))
  (Thread/sleep 30000))

;; =============================================================================
;; Catalogue patch
;; =============================================================================

(def ^:private catalogue-path
  "libs/boundary-cli/resources/boundary/cli/modules-catalogue.edn")

(defn- patch-catalogue-version!
  "Update :version for lib-name in modules-catalogue.edn after a successful deploy."
  [lib-name new-version]
  (let [f       (io/file catalogue-path)
        content (slurp f)
        pattern (re-pattern (str "(?s)(\\{[^}]*:name\\s+\"" (java.util.regex.Pattern/quote lib-name) "\"[^}]*:version\\s+\")([^\"]+)(\")"))]
    (if (re-find pattern content)
      (do (spit f (str/replace content pattern (str "$1" new-version "$3")))
          (println (green (str "  Catalogue updated: " lib-name " → " new-version))))
      (println (dim (str "  Catalogue: no entry for " lib-name " (skipping)"))))))

;; =============================================================================
;; Deploy
;; =============================================================================

(defn deploy-lib! [lib]
  (let [dir     (lib-dir lib)
        version (read-version lib)]
    (when-not version
      (println (red (str "Error: could not read version from libs/" lib "/build.clj")))
      (System/exit 1))
    (println (bold (str "\nDeploying boundary-" lib " " version "...")))
    (p/shell {:dir dir} "clojure" "-T:build" "clean")
    (p/shell {:dir dir} "clojure" "-T:build" "deploy")
    (println (green (str "✓ boundary-" lib " " version " deployed")))
    (patch-catalogue-version! lib version)))

(defn deploy-sequence! [libs]
  (doseq [[i lib] (map-indexed vector libs)]
    (deploy-lib! lib)
    (when (< i (dec (count libs)))
      (wait-for-indexing))))

;; =============================================================================
;; Commands
;; =============================================================================

(defn cmd-all []
  (check-env!)
  (println (bold (str "Deploying all " (count all-libs) " libraries...")))
  (deploy-sequence! all-libs)
  (println (green "\n✓ All libraries deployed.")))

(defn cmd-missing []
  (check-env!)
  (println "Checking Clojars for already-published versions...")
  (let [missing (filterv (fn [lib]
                           (let [exists? (published? lib)]
                             (if exists?
                               (println (dim (str "  ⏭  boundary-" lib " " (read-version lib) " already published")))
                               (println (yellow (str "  •  boundary-" lib " " (read-version lib) " not yet published"))))
                             (not exists?)))
                         all-libs)]
    (if (empty? missing)
      (println (green "\nAll libraries already published. Nothing to do."))
      (do
        (println (bold (str "\nDeploying " (count missing) " missing libraries...")))
        (deploy-sequence! missing)
        (println (green "\n✓ Done."))))))

(defn cmd-specific [libs]
  (check-env!)
  (let [unknown (remove valid-libs libs)]
    (when (seq unknown)
      (println (red (str "Unknown libraries: " (str/join ", " unknown))))
      (println (str "Valid: " (str/join ", " all-libs)))
      (System/exit 1)))
  (deploy-sequence! libs))

(defn print-help []
  (println (bold "bb deploy") "— Deploy Boundary libraries to Clojars")
  (println)
  (println "Usage:")
  (println "  bb deploy --all              Deploy all 23 libraries in dependency order")
  (println "  bb deploy --missing          Deploy only libraries not yet on Clojars")
  (println "  bb deploy <lib> [lib...]     Deploy specific libraries")
  (println)
  (println "Available libraries:")
  (doseq [lib all-libs]
    (println (str "  " lib)))
  (println)
  (println "Environment:")
  (println "  CLOJARS_USERNAME  your Clojars username")
  (println "  CLOJARS_PASSWORD  your Clojars deploy token"))

;; =============================================================================
;; Entry point
;; =============================================================================

(defn -main [& args]
  (cond
    (or (empty? args) (contains? (set args) "--help")) (print-help)
    (= args ["--all"])                                  (cmd-all)
    (= args ["--missing"])                              (cmd-missing)
    :else                                               (cmd-specific args)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
