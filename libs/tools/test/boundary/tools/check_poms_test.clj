(ns boundary.tools.check-poms-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [boundary.tools.check-poms :as poms])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-dir []
  (.toFile (Files/createTempDirectory "check-poms-test" (make-array FileAttribute 0))))

(defn- spit-file [dir name content]
  (let [f (io/file dir name)]
    (spit f content)
    f))

;; ---------------------------------------------------------------------------
;; boundary-dep->coord — mirrors build-shared/rewrite-boundary-deps
;; ---------------------------------------------------------------------------

(deftest ^:unit boundary-dep->coord-maps-to-published-artifact
  (is (= 'org.boundary-app/boundary-user (poms/boundary-dep->coord 'boundary/user)))
  (is (= 'org.boundary-app/boundary-ui-style (poms/boundary-dep->coord 'boundary/ui-style)))
  (is (= 'org.boundary-app/boundary-shared-ui (poms/boundary-dep->coord 'boundary/shared-ui))))

;; ---------------------------------------------------------------------------
;; boundary-local-deps — only :local/root boundary deps, mapped to coords
;; ---------------------------------------------------------------------------

(deftest ^:unit boundary-local-deps-selects-only-local-root-boundary-deps
  (let [dir (tmp-dir)]
    (spit-file dir "deps.edn"
               (pr-str {:deps {'boundary/core     {:local/root "../core"}
                               'boundary/platform {:local/root "../platform"}
                               ;; already-published boundary coord — not local/root, ignored
                               'org.boundary-app/boundary-i18n {:mvn/version "1.0.0"}
                               ;; third-party mvn dep — ignored
                               'ring/ring-core    {:mvn/version "1.15.4"}}}))
    (is (= #{'org.boundary-app/boundary-core 'org.boundary-app/boundary-platform}
           (set (poms/boundary-local-deps dir))))))

(deftest ^:unit boundary-local-deps-empty-when-no-deps-file
  (is (empty? (poms/boundary-local-deps (tmp-dir)))))

;; ---------------------------------------------------------------------------
;; check-lib — regression mode B (publishable build.clj must use pom-basis)
;; ---------------------------------------------------------------------------

(deftest ^:unit check-lib-flags-write-pom-without-pom-basis
  (testing "write-pom fed a raw basis is a violation"
    (let [dir (tmp-dir)]
      (spit-file dir "deps.edn" (pr-str {:deps {'boundary/core {:local/root "../core"}}}))
      (spit-file dir "build.clj"
                 "(def basis (b/create-basis {:project \"deps.edn\"}))
                  (defn jar [_] (b/write-pom {:basis basis}))")
      (let [r (poms/check-lib ["straggler" dir])]
        (is (:publishable? r))
        (is (not (:uses-pom-basis? r)))
        (is (:violation? r))))))

(deftest ^:unit check-lib-passes-write-pom-with-pom-basis
  (let [dir (tmp-dir)]
    (spit-file dir "deps.edn" (pr-str {:deps {'boundary/core {:local/root "../core"}}}))
    (spit-file dir "build.clj"
               "(def basis (build-shared/pom-basis version))
                (defn jar [_] (b/write-pom {:basis basis}))")
    (let [r (poms/check-lib ["good" dir])]
      (is (:publishable? r))
      (is (:uses-pom-basis? r))
      (is (not (:violation? r)))
      (is (= #{'org.boundary-app/boundary-core} (set (:boundary-deps r)))))))

(deftest ^:unit check-lib-exempts-non-publishable-lib
  (testing "a build.clj with no write-pom (e.g. uberjar CLI) is exempt"
    (let [dir (tmp-dir)]
      (spit-file dir "deps.edn" (pr-str {:deps {}}))
      (spit-file dir "build.clj" "(defn uber [_] (b/uber {}))")
      (let [r (poms/check-lib ["cli" dir])]
        (is (not (:publishable? r)))
        (is (not (:violation? r)))))))

;; ---------------------------------------------------------------------------
;; check-build-shared — regression mode A (rewrite must remain in build_shared)
;; ---------------------------------------------------------------------------

(deftest ^:unit check-build-shared-healthy-when-rewrite-present
  (let [dir (tmp-dir)
        f   (spit-file dir "build_shared.clj"
                       "(defn pom-basis [version]
                          ;; rewrites boundary/<x> :local/root -> org.boundary-app coords
                          (symbol \"org.boundary-app\" ...))")]
    (is (empty? (poms/check-build-shared f)))))

(deftest ^:unit check-build-shared-flags-missing-file
  (is (= [:build-shared-missing]
         (map :type (poms/check-build-shared (io/file (tmp-dir) "nope.clj"))))))

(deftest ^:unit check-build-shared-flags-lost-rewrite
  (testing "build_shared present but no longer rewrites local/root deps"
    (let [dir (tmp-dir)
          f   (spit-file dir "build_shared.clj"
                         "(defn pom-basis [version] (b/create-basis {:project (slurp \"deps.edn\")}))")]
      (is (= [:build-shared-no-rewrite]
             (map :type (poms/check-build-shared f)))))))

;; ---------------------------------------------------------------------------
;; unpublishable-deps — regression mode C (referenced dep must be publishable)
;; ---------------------------------------------------------------------------

(deftest ^:unit coord->lib-name-strips-artifact-prefix
  (is (= "shared-ui" (poms/coord->lib-name 'org.boundary-app/boundary-shared-ui)))
  (is (= "user" (poms/coord->lib-name 'org.boundary-app/boundary-user))))

(deftest ^:unit unpublishable-deps-flags-referenced-non-publishable-lib
  (testing "the shared-ui failure: user's POM references boundary-shared-ui but shared-ui has no build.clj"
    (let [results [{:lib "user" :publishable? true
                    :boundary-deps [(poms/boundary-dep->coord 'boundary/shared-ui)]}
                   {:lib "shared-ui" :publishable? false :boundary-deps []}]]
      (is (= [{:lib "user" :dep 'org.boundary-app/boundary-shared-ui}]
             (poms/unpublishable-deps results))))))

(deftest ^:unit unpublishable-deps-passes-when-all-deps-publishable
  (let [results [{:lib "user" :publishable? true
                  :boundary-deps [(poms/boundary-dep->coord 'boundary/shared-ui)]}
                 {:lib "shared-ui" :publishable? true :boundary-deps []}]]
    (is (empty? (poms/unpublishable-deps results)))))

(deftest ^:unit unpublishable-deps-ignores-non-publishable-referrer
  (testing "a non-publishable harness (no build.clj) emits no POM, so its deps are exempt"
    (let [results [{:lib "e2e" :publishable? false
                    :boundary-deps [(poms/boundary-dep->coord 'boundary/user)]}
                   {:lib "user" :publishable? true :boundary-deps []}]]
      (is (empty? (poms/unpublishable-deps results))))))
