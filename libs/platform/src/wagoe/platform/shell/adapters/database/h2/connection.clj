(ns wagoe.platform.shell.adapters.database.h2.connection
  "H2 connection settings and JDBC URL.")

(def ^:private h2-connection-options
  "PostgreSQL compatibility, so H2 behaves like the database it stands in for."
  "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")

(defn build-jdbc-url
  "Build the H2 JDBC URL from `db-config`."
  [db-config]
  (str "jdbc:h2:" (:database-path db-config) ";" h2-connection-options))

(defn session-statements
  "UTC and foreign keys. Each runs on its own — see
   common.adapter/run-session-statements!."
  [_db-config]
  [["SET TIME ZONE '+00:00'"]
   ["SET REFERENTIAL_INTEGRITY TRUE"]])

(def pool-defaults
  "HikariCP defaults for an embedded database."
  {:minimum-idle          1
   :maximum-pool-size     10
   :connection-timeout-ms 30000
   :idle-timeout-ms       600000
   :max-lifetime-ms       1800000})
