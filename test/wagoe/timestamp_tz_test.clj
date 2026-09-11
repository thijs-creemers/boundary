(ns wagoe.timestamp-tz-test
  "Widening a zone-less timestamp column, against every engine.

   The migration behind this runs on shipped databases, so what it does on each
   engine — and what it declines to do — is asserted rather than assumed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [support.embedded-pg :as epg]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.platform.shell.database.timestamp-tz :as sut])
  (:import [java.sql Timestamp]
           [java.time Instant]))

(def ^:private mysql-port
  (parse-long (or (System/getenv "WAGOE_TEST_MYSQL_PORT") "3306")))

(defonce ^:private mysql-up?
  (delay (try
           (with-open [c (jdbc/get-connection
                          (jdbc/get-datasource
                           {:dbtype "mysql" :host "127.0.0.1" :port mysql-port
                            :dbname (or (System/getenv "WAGOE_TEST_MYSQL_DB") "audience")
                            :user "root"
                            :password (or (System/getenv "WAGOE_TEST_MYSQL_PASSWORD") "probe")}))]
             (some? (.getMetaData c)))
           (catch Exception _ false))))

(defn- backends
  "[label open widens?] — `widens?` is whether this engine has a zone-aware
   timestamp to widen to at all."
  []
  (cond-> [["h2" (fn []
                   (let [ctx (factory/db-context
                              (factory/h2-config (str "mem:tzw_" (System/nanoTime)
                                                      ";DB_CLOSE_DELAY=-1")))]
                     [ctx #(factory/close-db-context! ctx)]))
            true]
           ["sqlite" (fn []
                       (let [path (str (System/getProperty "java.io.tmpdir")
                                       "/tzw-" (System/nanoTime) ".db")
                             ctx (factory/db-context (factory/sqlite-config path))]
                         [ctx (fn []
                                (factory/close-db-context! ctx)
                                (.delete (java.io.File. path)))]))
            false]
           ["postgresql" (fn []
                           (let [pg (epg/start!)
                                 ctx (epg/db-context pg)]
                             [ctx (fn []
                                    (factory/close-db-context! ctx)
                                    (epg/stop! pg))]))
            true]]
    @mysql-up?
    (conj ["mysql"
           (fn []
             (let [ctx (factory/db-context
                        (factory/mysql-config "127.0.0.1" mysql-port
                                              (or (System/getenv "WAGOE_TEST_MYSQL_DB") "audience")
                                              "root"
                                              (or (System/getenv "WAGOE_TEST_MYSQL_PASSWORD") "probe")))]
               [ctx #(factory/close-db-context! ctx)]))
           false])))

(defn- column-type-name
  "What the engine itself calls the column's type — asked of JDBC, not of the
   code under test."
  [datasource table column]
  (with-open [c (jdbc/get-connection datasource)]
    (letfn [(lookup [t col]
              (with-open [rs (.getColumns (.getMetaData c) nil nil t col)]
                (when (.next rs) (.getString rs "TYPE_NAME"))))]
      (or (lookup table column)
          (lookup (str/upper-case table) (str/upper-case column))))))

(deftest ^:integration widening-gives-the-column-a-zone-where-the-engine-has-one
  (doseq [[label open widens?] (backends)]
    (testing label
      (let [[{:keys [datasource]} close!] (open)]
        (try
          (jdbc/execute! datasource ["DROP TABLE IF EXISTS tzw_probe"])
          (jdbc/execute! datasource ["CREATE TABLE tzw_probe (id INT, made_at TIMESTAMP)"])

          (let [now (Instant/now)]
            (jdbc/execute! datasource ["INSERT INTO tzw_probe (id, made_at) VALUES (?, ?)"
                                       1 (Timestamp/from now)])

            (let [altered (sut/widen-columns! datasource [["tzw_probe" "made_at"]])]

              (testing "it alters exactly the engines that have somewhere to go"
                (is (= (if widens? 1 0) altered)))

              (when widens?
                (testing "and the column now carries a zone"
                  ;; PostgreSQL spells it timestamptz, H2 TIMESTAMP WITH TIME
                  ;; ZONE — and pgjdbc reports both as Types/TIMESTAMP, so the
                  ;; name is the only thing that tells them apart.
                  (let [t (str/lower-case (column-type-name datasource "tzw_probe" "made_at"))]
                    (is (or (= "timestamptz" t) (str/includes? t "with time zone"))
                        (str label " reported " t)))))

              (testing "the value it held is still the same moment"
                (let [row (first (jdbc/execute! datasource
                                                ["SELECT made_at FROM tzw_probe WHERE id = 1"]))
                      v (val (first row))
                      ms (condp instance? v
                           java.sql.Timestamp (.getTime ^Timestamp v)
                           java.time.OffsetDateTime (.toEpochMilli
                                                     (.toInstant ^java.time.OffsetDateTime v))
                           java.time.LocalDateTime (.toEpochMilli
                                                    (.toInstant (.atZone ^java.time.LocalDateTime v
                                                                         (java.time.ZoneId/systemDefault))))
                           ;; SQLite has no date type; the driver hands back
                           ;; whatever storage class it put the value in.
                           String (.getTime (Timestamp/valueOf ^String v))
                           Long v
                           nil)]
                  (is (some? ms) (str label " read back " (pr-str v)))
                  (is (< (abs (- ms (.toEpochMilli now))) 60000))))

              (testing "running it again does nothing — migrations get re-run"
                (is (zero? (sut/widen-columns! datasource [["tzw_probe" "made_at"]]))))))

          (finally
            (try (jdbc/execute! datasource ["DROP TABLE IF EXISTS tzw_probe"])
                 (catch Exception _ nil))
            (close!)))))))

(deftest ^:integration a-column-that-is-not-there-is-skipped-not-failed
  (testing "a database that never ran the migration creating the table"
    (let [[{:keys [datasource]} close!] ((second (first (backends))))]
      (try
        (is (zero? (sut/widen-columns! datasource [["no_such_table" "no_such_column"]])))
        (finally (close!))))))
