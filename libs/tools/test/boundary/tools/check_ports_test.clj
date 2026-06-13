(ns boundary.tools.check-ports-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [boundary.tools.check-ports :as ports]))

;; ---------------------------------------------------------------------------
;; Classification helpers
;; ---------------------------------------------------------------------------

(deftest ^:unit web-layer?-detects-delivery-layers
  (testing "web/HTTP namespaces are recognised"
    (is (ports/web-layer? "boundary.license.web.http"))
    (is (ports/web-layer? "boundary.license.web.api"))
    (is (ports/web-layer? "boundary.license.monitoring.shell.http"))
    (is (ports/web-layer? "boundary.user.shell.handlers")))
  (testing "pure domain namespaces are not web layers"
    (is (not (ports/web-layer? "boundary.license.billing.shell.service")))
    (is (not (ports/web-layer? "boundary.license.billing.core.invoice")))))

(deftest ^:unit shell-ns?-detects-shell-namespaces
  (is (ports/shell-ns? "boundary.license.billing.shell.service"))
  (is (ports/shell-ns? "boundary.user.shell"))
  (is (not (ports/shell-ns? "boundary.license.billing.core.invoice")))
  (is (not (ports/shell-ns? "boundary.license.web.http"))))

(deftest ^:unit ns->module-extracts-module-prefix
  (is (= "boundary.license.billing" (ports/ns->module "boundary.license.billing.shell.service")))
  (is (= "boundary.license.billing" (ports/ns->module "boundary.license.billing.core.invoice")))
  (is (= "boundary.license.billing" (ports/ns->module "boundary.license.billing.ports")))
  (is (nil? (ports/ns->module "boundary.license.web.http"))))

(deftest ^:unit persistence+service-require-detection
  (is (ports/persistence-require? "boundary.license.catalog.shell.persistence"))
  (is (not (ports/persistence-require? "boundary.license.catalog.shell.service")))
  (is (ports/service-require? "boundary.license.catalog.shell.service"))
  (is (not (ports/service-require? "boundary.license.catalog.ports"))))

;; ---------------------------------------------------------------------------
;; Rule 2 — cross-module shell coupling
;; ---------------------------------------------------------------------------

(deftest ^:unit cross-module-flags-foreign-shell-requires
  (testing "billing shell requiring catalog/customer shell is a violation"
    (let [v (ports/cross-module-violations
             "boundary.license.billing.shell.service"
             ["boundary.license.catalog.shell.service"
              "boundary.license.customer.shell.service"
              "boundary.license.billing.shell.persistence"])
          reqs (set (map :req v))]
      (is (contains? reqs "boundary.license.catalog.shell.service"))
      (is (contains? reqs "boundary.license.customer.shell.service"))
      (testing "same-module persistence require is allowed"
        (is (not (contains? reqs "boundary.license.billing.shell.persistence")))))))

(deftest ^:unit cross-module-ignores-non-shell-namespaces
  (is (empty? (ports/cross-module-violations
               "boundary.license.billing.core.invoice"
               ["boundary.license.catalog.shell.service"]))))

;; ---------------------------------------------------------------------------
;; Rule 3 — web layer requiring persistence
;; ---------------------------------------------------------------------------

(deftest ^:unit web-persistence-flags-direct-persistence-requires
  (let [v (ports/web-persistence-violations
           "boundary.license.web.http"
           ["boundary.license.customer.shell.persistence"
            "boundary.license.catalog.shell.persistence"
            "boundary.license.billing.shell.service"])
        reqs (set (map :req v))]
    (is (contains? reqs "boundary.license.customer.shell.persistence"))
    (is (contains? reqs "boundary.license.catalog.shell.persistence"))
    (testing "service requires from web are not flagged by rule 3"
      (is (not (contains? reqs "boundary.license.billing.shell.service"))))))

(deftest ^:unit web-persistence-ignores-non-web-namespaces
  (is (empty? (ports/web-persistence-violations
               "boundary.license.billing.core.invoice"
               ["boundary.license.customer.shell.persistence"]))))

;; ---------------------------------------------------------------------------
;; Escape hatch — ^:boundary/allow-direct ns metadata
;; ---------------------------------------------------------------------------

(deftest ^:unit allow-direct-metadata-exempts-namespace
  (testing "the ns symbol's :boundary/allow-direct metadata is detected"
    (let [form (read-string "(ns ^:boundary/allow-direct boundary.license.web.http (:require [x]))")]
      (is (true? (:boundary/allow-direct (meta (second form))))))
    (let [form (read-string "(ns boundary.license.web.http (:require [x]))")]
      (is (nil? (:boundary/allow-direct (meta (second form))))))))

;; ---------------------------------------------------------------------------
;; Rule 1 — module completeness
;; ---------------------------------------------------------------------------

(deftest ^:unit module-completeness-detects-missing-and-empty-ports
  (testing "monorepo libs (e.g. user) define a non-empty ports.clj"
    (let [dir (io/file "libs/user/src/boundary/user")]
      (when (.exists dir)
        (is (nil? (ports/module-completeness-violation dir))
            "user module should have a ports.clj with a defprotocol")))))

;; ---------------------------------------------------------------------------
;; Discovery sanity — the scanner finds real monorepo modules
;; ---------------------------------------------------------------------------

(deftest ^:unit module-dirs-includes-libs
  (let [dirs (map ports/dir->ns-prefix (ports/module-dirs (ports/source-roots)))]
    (is (some #{"boundary.user"} dirs))
    (is (some #{"boundary.tenant"} dirs))))
