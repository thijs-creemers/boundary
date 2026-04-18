(ns boundary.devtools.shell.fcis-checker-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.devtools.shell.fcis-checker :as fcis]))

(deftest ^:unit core-ns-and-shell-ns-test
  (testing "is-core-ns? identifies core namespaces"
    (is (true? (fcis/core-ns? "boundary.product.core.validation")))
    (is (true? (fcis/core-ns? "boundary.user.core.service")))
    (is (false? (fcis/core-ns? "boundary.product.shell.persistence")))
    (is (false? (fcis/core-ns? "boundary.platform.core.http"))))

  (testing "is-shell-ns? identifies shell namespaces"
    (is (true? (fcis/shell-ns? "boundary.product.shell.persistence")))
    (is (false? (fcis/shell-ns? "boundary.product.core.validation")))
    (is (false? (fcis/shell-ns? "boundary.platform.shell.interceptors")))))
