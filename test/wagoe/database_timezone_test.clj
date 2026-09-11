(ns wagoe.database-timezone-test
  "The JVM's timezone is the database session's timezone.

   Every JDBC driver here sends the client's zone when it opens a connection, so
   a process running in local time gives PostgreSQL a local `CURRENT_DATE` and
   `date_trunc`, and reads a column declared without a zone two hours off
   (BOU-431). `-Duser.timezone=UTC` is therefore not a preference — it is what
   makes a timestamp mean the same thing to every process touching the database."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [support.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.factory :as factory])
  (:import [java.sql Timestamp]
           [java.time Instant]))

(def ^:private utc-opt "-Duser.timezone=UTC")

(def ^:private jdbc-drivers
  '#{org.xerial/sqlite-jdbc org.postgresql/postgresql
     com.h2database/h2 com.mysql/mysql-connector-j})

(defn- aliases-that-can-open-a-connection
  "Aliases declaring a JDBC driver — the ones from which a database is reachable."
  [deps]
  (for [[alias-name m] (:aliases deps)
        :when (some jdbc-drivers (concat (keys (:extra-deps m))
                                         (keys (:replace-deps m))))]
    [alias-name (vec (:jvm-opts m))]))

(deftest ^:unit every-alias-that-reaches-a-database-runs-in-utc
  (testing "deps.edn"
    (let [missing (for [[a opts] (aliases-that-can-open-a-connection
                                  (edn/read-string (slurp "deps.edn")))
                        :when (not (some #{utc-opt} opts))]
                    a)]
      (is (empty? missing)
          (str "these aliases can open a connection but not in UTC: " (vec missing))))))

(deftest ^:unit a-generated-project-runs-in-utc-too
  (testing "the CLI's deps.edn template — the fix has to travel downstream"
    (let [tmpl (slurp (io/file "libs/wagoe-cli/resources/wagoe/cli/templates/deps.edn.tmpl"))]
      (doseq [alias-name [":run" ":test" ":migrate" ":seed" ":repl" ":user-cli"]]
        (testing alias-name
          (is (re-find (re-pattern (str "(?s)" alias-name "\\s.*?-Duser\\.timezone=UTC"))
                       tmpl))))))

  (testing "and its Dockerfile"
    (is (re-find #"-Duser\.timezone=UTC"
                 (slurp (io/file "libs/wagoe-cli/resources/wagoe/cli/templates/Dockerfile.tmpl"))))))

(deftest ^:unit the-image-this-repo-builds-runs-in-utc
  (is (re-find #"exec java -Duser\.timezone=UTC \$JAVA_OPTS" (slurp "Dockerfile"))
      "the flag goes before $JAVA_OPTS, so a deployment can still override it"))

;; =============================================================================
;; What the flag buys, measured
;; =============================================================================

(deftest ^:unit this-suite-itself-runs-in-utc
  (is (= "UTC" (.getID (java.util.TimeZone/getDefault)))
      "the :test alias sets it; without that the assertions below prove nothing"))

(deftest ^:integration postgresql-sessions-report-utc
  (let [pg (epg/start!)
        ctx (epg/db-context pg)]
    (try
      (with-open [c (jdbc/get-connection (:datasource ctx))]
        (let [q (fn [sql] (first (jdbc/execute! c [sql]
                                                {:builder-fn rs/as-unqualified-lower-maps})))]

          (testing "the session zone follows the JVM, so it is UTC"
            (is (= "UTC" (:timezone (q "SHOW TimeZone")))))

          (testing "a column without a zone survives the round trip"
            ;; With the JVM in local time and the session in UTC these disagree
            ;; by the offset — the shape BOU-431 measured before the flag.
            (jdbc/execute! c ["DROP TABLE IF EXISTS tz_probe"])
            (jdbc/execute! c ["CREATE TABLE tz_probe (id INT,
                                                      db_clock TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                                      app_written TIMESTAMP)"])
            (let [now (Instant/now)]
              (jdbc/execute! c ["INSERT INTO tz_probe (id, app_written) VALUES (?, ?)"
                                1 (Timestamp/from now)])
              (let [row (first (jdbc/execute! c ["SELECT * FROM tz_probe WHERE id = 1"]
                                              {:builder-fn rs/as-unqualified-lower-maps}))
                    drift (fn [^Timestamp t]
                            (abs (- (.getTime t) (.toEpochMilli now))))]
                (is (< (drift (:db_clock row)) 60000)
                    "a timestamp the database clock wrote reads back as the same moment")
                (is (< (drift (:app_written row)) 60000)
                    "and so does one the application wrote"))))))
      (finally
        (factory/close-db-context! ctx)
        (epg/stop! pg)))))
