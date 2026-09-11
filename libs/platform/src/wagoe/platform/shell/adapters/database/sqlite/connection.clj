(ns wagoe.platform.shell.adapters.database.sqlite.connection
  "SQLite connection settings and JDBC URL.")

(def ^:private mmap-size-bytes
  "Memory-mapped I/O size in bytes (256MB)."
  268435456)

(def ^:private cache-size-pages
  "Page cache size in pages (~10MB with 1KB pages)."
  10000)

(def ^:private busy-timeout-ms
  "Busy timeout in milliseconds (5 seconds)."
  5000)

(defn build-jdbc-url
  "Build the SQLite JDBC URL from `db-config`."
  [db-config]
  (str "jdbc:sqlite:" (:database-path db-config)))

(defn session-statements
  "PRAGMAs for concurrency, durability and referential integrity. Each runs on
   its own — see common.adapter/run-session-statements!."
  [db-config]
  (into [["PRAGMA journal_mode=WAL"]        ; Write-Ahead Logging, better concurrency
         ["PRAGMA synchronous=NORMAL"]      ; balance safety against speed
         ["PRAGMA foreign_keys=ON"]
         ["PRAGMA temp_store=MEMORY"]
         [(str "PRAGMA mmap_size=" mmap-size-bytes)]
         [(str "PRAGMA cache_size=" cache-size-pages)]
         [(str "PRAGMA busy_timeout=" busy-timeout-ms)]]
        (map vector (:pragmas db-config))))

(def pool-defaults
  "HikariCP defaults for an embedded database. SQLite serialises writes, so the
   pool stays small."
  {:minimum-idle          1
   :maximum-pool-size     5
   :connection-timeout-ms 30000
   :idle-timeout-ms       600000
   :max-lifetime-ms       1800000})
