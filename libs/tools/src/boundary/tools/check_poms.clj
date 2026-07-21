#!/usr/bin/env bb
;; libs/tools/src/boundary/tools/check_poms.clj
;;
;; POM dependency completeness: verifies that published library POMs will carry
;; their inter-Boundary dependencies.
;;
;; tools.build's `write-pom` omits `:local/root` deps from the generated pom, so
;; each lib's build.clj must feed write-pom a *rewritten* basis (produced by
;; `build-shared/pom-basis`) that translates `boundary/<x> {:local/root ...}`
;; into the published `org.boundary-app/boundary-<x> {:mvn/version ...}` coord.
;;
;; Without that rewrite a published lib's pom lists none of its boundary deps and
;; downstream consumers must hand-enumerate the whole closure (BOU-202).
;;
;; This gate is static (no tools.build / network): it guards the two regression
;; modes that would silently drop boundary deps from a POM again —
;;   A. build_shared.clj loses the rewrite, or
;;   B. a publishable build.clj feeds write-pom a raw basis instead of pom-basis.
;; It also reports, per lib, the boundary closure that will land in its POM.

(ns boundary.tools.check-poms
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.tools.ansi :as ansi]))

;; ---------------------------------------------------------------------------
;; Discovery
;; ---------------------------------------------------------------------------

(defn- lib-dirs
  "Seq of [lib-name lib-dir] for all libraries under libs/, sorted by name."
  []
  (let [libs (io/file (System/getProperty "user.dir") "libs")]
    (when (.exists libs)
      (->> (.listFiles libs)
           (filter #(.isDirectory %))
           (map (fn [d] [(.getName d) d]))
           (sort-by first)))))

(defn boundary-dep->coord
  "Published Maven coordinate symbol for a `boundary/<x>` dep symbol, mirroring
   `build-shared/rewrite-boundary-deps`."
  [dep]
  (symbol "org.boundary-app" (str "boundary-" (name dep))))

(defn boundary-local-deps
  "Set of published coordinate symbols for every `boundary/<x> {:local/root ...}`
   dep declared in a lib's deps.edn (empty if the file is missing/unreadable)."
  [lib-dir]
  (let [deps-file (io/file lib-dir "deps.edn")]
    (if (.exists deps-file)
      (->> (:deps (edn/read-string (slurp deps-file)))
           (keep (fn [[dep coord]]
                   (when (and (map? coord)
                              (contains? coord :local/root)
                              (= "boundary" (namespace dep)))
                     (boundary-dep->coord dep))))
           (into (sorted-set)))
      (sorted-set))))

;; ---------------------------------------------------------------------------
;; Checks
;; ---------------------------------------------------------------------------

(defn check-build-shared
  "Regression mode A: build_shared.clj must still perform the local/root -> mvn
   rewrite. Returns a seq of violation maps (empty when healthy)."
  ([] (check-build-shared (io/file (System/getProperty "user.dir") "libs" "build_shared.clj")))
  ([f]
   (cond
     (not (.exists f))
     [{:type :build-shared-missing}]

     (let [src (slurp f)]
       (not (and (str/includes? src "pom-basis")
                 (str/includes? src "org.boundary-app")
                 (str/includes? src ":local/root"))))
     [{:type :build-shared-no-rewrite}]

     :else nil)))

(defn check-lib
  "Regression mode B: a build.clj that generates a pom (calls write-pom) must
   feed it the rewritten basis via build-shared/pom-basis. Returns a map:
   {:lib :publishable? :uses-pom-basis? :boundary-deps :violation?}."
  [[lib-name lib-dir]]
  (let [build-file    (io/file lib-dir "build.clj")
        src           (when (.exists build-file) (slurp build-file))
        publishable?  (boolean (and src (str/includes? src "write-pom")))
        uses-pom-basis? (boolean (and src (str/includes? src "build-shared/pom-basis")))
        boundary-deps (boundary-local-deps lib-dir)]
    {:lib             lib-name
     :publishable?    publishable?
     :uses-pom-basis? uses-pom-basis?
     :boundary-deps   boundary-deps
     ;; A publishable lib that skips pom-basis will silently drop its boundary
     ;; deps from the pom. A non-publishable lib (no write-pom) is exempt.
     :violation?      (and publishable? (not uses-pom-basis?))}))

(defn coord->lib-name
  "Reverse of boundary-dep->coord: 'org.boundary-app/boundary-shared-ui -> \"shared-ui\"."
  [coord]
  (subs (name coord) (count "boundary-")))

(defn unpublishable-deps
  "Regression mode C: every boundary dep a POM declares must itself be a
   publishable lib, else downstream resolution fails on an unresolvable artifact
   (the shared-ui failure: referenced by user/admin/... but no build.clj).
   Returns a seq of {:lib :dep} violations."
  [results]
  (let [publishable (into #{} (comp (filter :publishable?) (map :lib)) results)]
    (for [{:keys [lib publishable? boundary-deps]} results
          :when publishable?                         ; only published libs emit a POM
          coord boundary-deps
          :when (not (contains? publishable (coord->lib-name coord)))]
      {:lib lib :dep coord})))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (let [libs           (lib-dirs)
        shared-issues  (check-build-shared)
        results        (map check-lib libs)
        violations     (filter :violation? results)
        unpublishable  (unpublishable-deps results)
        with-deps      (filter #(and (:publishable? %) (seq (:boundary-deps %))) results)]
    ;; Informational: the boundary closure each pom will declare.
    (when (seq with-deps)
      (println (ansi/dim "Boundary dependencies each published POM will declare:"))
      (doseq [{:keys [lib boundary-deps]} with-deps]
        (println (str "  " lib " -> " (str/join ", " (map str boundary-deps)))))
      (println))
    (if (or (seq shared-issues) (seq violations) (seq unpublishable))
      (do
        (println (ansi/red "POM dependency completeness violations found:"))
        (println)
        (doseq [v shared-issues]
          (case (:type v)
            :build-shared-missing
            (println (str "  VIOLATION: " (ansi/red "libs/build_shared.clj")
                          " is missing — published POMs will omit all boundary deps"))
            :build-shared-no-rewrite
            (println (str "  VIOLATION: " (ansi/red "libs/build_shared.clj")
                          " no longer rewrites :local/root boundary deps to published coords"))))
        (doseq [{:keys [lib]} violations]
          (println (str "  VIOLATION: " (ansi/red lib)
                        "/build.clj calls write-pom without build-shared/pom-basis"
                        " — its boundary deps will be omitted from the published POM")))
        (doseq [{:keys [lib dep]} unpublishable]
          (println (str "  VIOLATION: " (ansi/red lib) "'s POM declares " (ansi/red (str dep))
                        " but that lib is not publishable (no build.clj with write-pom+pom-basis)"
                        " — downstream resolution will fail")))
        (println)
        (println (str (+ (count shared-issues) (count violations) (count unpublishable))
                      " violation(s) found."))
        (System/exit 1))
      (do
        (println (ansi/green "POM dependency check passed.")
                 (str (count libs) " libraries scanned, "
                      (count with-deps) " declare boundary deps, 0 violations."))
        (System/exit 0)))))
