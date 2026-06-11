(ns boundary.tools.deploy-test
  "Release-safety helpers used by the Clojars publish flow: a pre-deploy guard
   that every lib's build.clj version matches the release tag (prevents the
   'version ahead of source' stale-artifact class), and a post-deploy check that
   every artifact actually landed on Clojars."
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.tools.deploy :as deploy]))

(deftest ^:unit artifact-name-test
  (testing "normal libs get the boundary- prefix"
    (is (= "boundary-platform" (deploy/artifact-name "platform")))
    (is (= "boundary-core" (deploy/artifact-name "core"))))

  (testing "a lib dir already starting with boundary- is not double-prefixed"
    ;; libs/boundary-cli publishes org.boundary-app/boundary-cli, NOT
    ;; boundary-boundary-cli — the Clojars check must use the real coordinate.
    (is (= "boundary-cli" (deploy/artifact-name "boundary-cli")))))

(deftest ^:unit version-mismatches-test
  (testing "no mismatches when expected equals the suite's current build.clj version"
    (let [current (deploy/read-version "core")]
      (is (string? current) "read-version should find the core lib version")
      (is (empty? (deploy/version-mismatches current))
          "every lib's build.clj should be in lockstep with core")))

  (testing "every lib mismatches a bogus expected version"
    (let [ms (deploy/version-mismatches "9.9.9-nope")]
      (is (= (count deploy/all-libs) (count ms))
          "all libs should be reported as mismatched")
      (is (every? :lib ms))
      (is (every? #(= "9.9.9-nope" (:expected %)) ms)))))

(deftest ^:unit unpublished-libs-test
  (testing "none unpublished when the predicate reports all present"
    (is (empty? (deploy/unpublished-libs ["a" "b"] (constantly true)))))

  (testing "all unpublished when the predicate reports none present"
    (is (= ["a" "b"] (deploy/unpublished-libs ["a" "b"] (constantly false)))))

  (testing "only the unpublished libs are returned, in order"
    (is (= ["b"] (deploy/unpublished-libs ["a" "b" "c"]
                                          (fn [lib] (not= "b" lib)))))))
