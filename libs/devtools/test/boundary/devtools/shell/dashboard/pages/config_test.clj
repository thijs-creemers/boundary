(ns boundary.devtools.shell.dashboard.pages.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.devtools.shell.dashboard.pages.config :as config-page]
            [clojure.string :as str]))

(deftest ^:unit renders-config-page
  (testing "renders config tree with redacted secrets"
    (let [html (config-page/render {:config {:boundary/http {:port 3000}
                                             :boundary/db {:password "secret"}}})]
      (is (string? html))
      (is (str/includes? html "Config"))
      (is (str/includes? html "3000"))
      (is (not (str/includes? html "secret"))))))

(deftest ^:unit renders-empty-when-no-config
  (testing "renders empty state without config"
    (let [html (config-page/render {})]
      (is (string? html))
      (is (str/includes? html "No config")))))
