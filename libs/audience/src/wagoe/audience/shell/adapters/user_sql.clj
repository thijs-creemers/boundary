(ns wagoe.audience.shell.adapters.user-sql
  "IUserDataSource over the application's users table.

   The audience service needs one to run at all — its init-key throws without a
   `:user-data-source` — and the protocol had no implementation anywhere, so the
   module could not boot and no application ever activated it (BOU-419).

   Queried by table name rather than through the user module: audience depends
   on no other Wagoe library, and reaching into `wagoe.user.shell.*` would be
   the cross-module coupling `bb check:ports` exists to prevent. `:table` is
   configurable for an application whose users live elsewhere."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.audience.ports :as ports])
  (:import [java.util UUID]))

(def ^:private opts {:builder-fn rs/as-unqualified-lower-maps})

(defn- ->uuid
  "Ids come back as UUID on PostgreSQL and as a String on some adapters."
  [v]
  (cond
    (uuid? v)   v
    (string? v) (try (UUID/fromString v) (catch IllegalArgumentException _ v))
    :else       v))

(defn- kebab-row
  "Snake_case columns to kebab-case keys, at the persistence boundary and
   nowhere else. A local walk rather than `wagoe.core.utils.case-conversion`
   because this library depends on no other Wagoe library, as its siblings do
   not either."
  [row]
  (reduce-kv (fn [m k v]
               (assoc m (keyword (str/replace (name k) \_ \-)) v))
             {}
             row))

(defrecord SqlUserDataSource [datasource table]
  ports/IUserDataSource

  (query-users-sql [_ honeysql-clause]
    ;; A nil clause is "every user", not "no users" — an empty filter is the
    ;; universe, and returning nothing for it silently empties every audience
    ;; whose filters are all predicate-phase (libs/audience/AGENTS.md).
    (let [q (cond-> {:select [:id] :from [table]}
              (some? honeysql-clause) (assoc :where honeysql-clause))]
      (mapv (comp ->uuid :id) (jdbc/execute! datasource (sql/format q) opts))))

  (load-users [_ user-ids]
    (if (empty? user-ids)
      []
      (->> (jdbc/execute! datasource
                          (sql/format {:select [:*]
                                       :from   [table]
                                       :where  [:in :id (mapv ->uuid user-ids)]})
                          opts)
           (mapv (fn [row] (update (kebab-row row) :id ->uuid)))))))

(defn create-sql-user-data-source
  "An IUserDataSource reading `table` (default `:users`) from `datasource`."
  ([datasource] (create-sql-user-data-source datasource :users))
  ([datasource table] (->SqlUserDataSource datasource (or table :users))))
