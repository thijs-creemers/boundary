(ns boundary.config-test
  (:require
    [clojure.test :refer :all]
    [boundary.config :refer [dot-env-for-profile read-config]]))

(deftest dot-env-for-profile-parses-dev
  (let [env (dot-env-for-profile "dev")]
    (is (= "localhost" (get env "POSTGRES_HOST")))
    (is (= "postgres"  (get env "POSTGRES_PORT")))
    (is (= "ixfn_dev"  (get env "POSTGRES_DB")))
    (is (= "postgres"  (get env "POSTGRES_USER")))
    (is (= "postgres"  (get env "POSTGRES_PASSWORD")))))

(deftest read-config-produces-active-map
  (let [config (read-config "dev")
        settings (:boundary/settings config)
        pg       (:boundary/postgresql config)]
    ;; check general app settings from config.edn
    (is (= "isfx-dev" (get settings :name)))
    (is (= "0.1.0"    (get settings :version)))
    ;; check DB settings resolved via #env
    (is (= "localhost" (:host pg)))
    (is (= "postgres"  (:port pg)))
    (is (= "ixfn_dev"  (:dbname pg)))
    (is (= "postgres"  (:user pg)))
    (is (= "postgres"  (:password pg)))
    (is (= 15          (:max-pool-size pg)))))

(deftest read-config-missing-profile-throws
  (is (thrown? Exception (read-config "nonexistent"))))