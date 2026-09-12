(ns wagoe.audience.shell.cache
  "DB-backed cache for evaluated audience results.

   L1 — audience_segments.cached_at + audience_memberships table.
         A result is fresh if cached_at is within TTL minutes.
         put-cached writes memberships and stamps cached_at + member_count.
         get-cached reads memberships when stamp is fresh, else nil.
         invalidate / invalidate-all clear cached_at and memberships.

   L2 — wagoe-cache (Redis / in-memory) can be layered in later.
         The wagoe-cache param is accepted but not yet wired."
  (:require [clojure.string :as str]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.persistence :as persistence]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.sql Timestamp]
           [java.time Instant]
           [java.time.temporal ChronoUnit]))

;; =============================================================================
;; Internal DB helpers
;; =============================================================================

(defn- kw->str [k] (when k (name k)))

(defn- stamp-segment!
  "Update cached_at and member_count for a segment row."
  [datasource audience-id member-count]
  (jdbc/execute-one!
   datasource
   (sql/format {:update :audience_segments
                ;; Written from here, not by the database clock. A DB-side
                ;; CURRENT_TIMESTAMP is wall time in the server's zone, and the
                ;; driver converts it back using the JVM's — two hours of skew
                ;; on a MySQL container, so every entry looked expired the
                ;; moment it was written. Writing an Instant makes the write and
                ;; the read cancel out, whatever either zone is (BOU-419 review).
                :set    {:cached_at    (Timestamp/from (Instant/now))
                         :member_count member-count}
                :where  [:= :audience_id (kw->str audience-id)]})
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn- clear-stamp!
  "Set cached_at = NULL and member_count = 0 for a segment."
  [datasource audience-id]
  (jdbc/execute-one!
   datasource
   (sql/format {:update :audience_segments
                :set    {:cached_at    nil
                         :member_count 0}
                :where  [:= :audience_id (kw->str audience-id)]})
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn- clear-all-stamps!
  "Set cached_at = NULL and member_count = 0 for all segments."
  [datasource]
  (jdbc/execute!
   datasource
   (sql/format {:update :audience_segments
                :set    {:cached_at    nil
                         :member_count 0}})
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn- load-segment-cache-row
  "Return the raw cache-relevant columns for a segment, or nil.
   Includes cache_config so get-cached can derive the TTL."
  [datasource audience-id]
  (jdbc/execute-one!
   datasource
   (sql/format {:select [:cached_at :member_count :cache_config]
                :from   [:audience_segments]
                :where  [:= :audience_id (kw->str audience-id)]})
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn- ->instant
  "A `cached_at` column value as an Instant.

   H2 and PostgreSQL hand back an `OffsetDateTime` for a column that carries a
   zone and a `java.sql.Timestamp` for one that does not; SQLite has no
   timestamp type and returns the string it stored, which the cast to Timestamp
   threw on (BOU-419 review)."
  [v]
  (cond
    (instance? java.sql.Timestamp v) (.toInstant ^java.sql.Timestamp v)
    (instance? Instant v)            v
    ;; The column carries a zone since BOU-431, so H2 and PostgreSQL hand back
    ;; an OffsetDateTime rather than a Timestamp.
    (instance? java.time.OffsetDateTime v)
    (.toInstant ^java.time.OffsetDateTime v)
    (instance? java.time.ZonedDateTime v)
    (.toInstant ^java.time.ZonedDateTime v)
    ;; SQLite has no timestamp type: the driver stores the Instant it was
    ;; given as epoch millis, which is the one representation with no zone in
    ;; it at all.
    (number? v)                      (Instant/ofEpochMilli (long v))
    ;; SQLite has no timestamp type and hands back the bare
    ;; "yyyy-MM-dd HH:mm:ss" string the driver wrote — wall time in the JVM's
    ;; zone, since `stamp-segment!` writes an Instant through that driver. Read
    ;; it back the same way, so the round trip is exact.
    (string? v)                      (try
                                       (.toInstant
                                        (java.time.LocalDateTime/parse
                                         (str/replace v " " "T"))
                                        (.getOffset (java.time.ZoneId/systemDefault)
                                                    (java.time.LocalDateTime/parse
                                                     (str/replace v " " "T"))))
                                       (catch Exception _
                                         (try (Instant/parse v)
                                              (catch Exception _ nil))))
    :else                            nil))

(defn- fresh?
  "Return true if cached-at instant is within ttl-minutes from now."
  [cached-at ttl-minutes]
  (when-let [cached-inst (->instant cached-at)]
    (let [expiry-inst (.plus cached-inst ttl-minutes ChronoUnit/MINUTES)
          now         (Instant/now)]
      (.isBefore now expiry-inst))))

;; =============================================================================
;; IAudienceCache implementation — L1 only (DB-backed)
;; =============================================================================

(defrecord AudienceCache [datasource wagoe-cache]
  ports/IAudienceCache

  (put-cached [_ audience-id result ttl-minutes]
    (when-not ttl-minutes
      (throw (ex-info "put-cached requires non-nil ttl-minutes"
                      {:type :validation-error :audience-id audience-id})))
    (log/debug "Caching audience result"
               {:audience-id audience-id :count (:count result) :ttl-minutes ttl-minutes})
    (let [user-ids (:user-ids result)]
      ;; Write memberships
      (persistence/clear-memberships! datasource audience-id)
      (persistence/save-memberships! datasource audience-id user-ids)
      ;; Stamp the segment row
      (stamp-segment! datasource audience-id (count user-ids))
      ;; Store TTL in cache_config so get-cached can read it back
      (jdbc/execute-one!
       datasource
       (sql/format {:update :audience_segments
                    ;; Cast for PostgreSQL, where this column is JSONB and a
                    ;; string parameter is rejected (BOU-419 review).
                    :set    {:cache_config (persistence/json-param
                                            (persistence/dialect datasource)
                                            (json/generate-string {:ttl-minutes ttl-minutes}))}
                    :where  [:= :audience_id (kw->str audience-id)]})
       {:builder-fn rs/as-unqualified-lower-maps}))
    result)

  (get-cached [_ audience-id]
    (log/debug "Checking cache for audience" {:audience-id audience-id})
    (let [row (load-segment-cache-row datasource audience-id)]
      (when row
        (let [cached-at   (:cached_at row)
              ;; The shared decoder, which also handles the PGobject a JSONB
              ;; column returns. The copy that used to live here returned nil
              ;; for one, so PostgreSQL never read a TTL (BOU-419 review).
              cache-cfg   (persistence/<-json (:cache_config row))
              ttl-minutes (get cache-cfg :ttl-minutes)]
          (when (and cached-at ttl-minutes
                     (fresh? cached-at ttl-minutes))
            (let [user-ids (set (persistence/get-memberships datasource audience-id))]
              {:user-ids     user-ids
               :count        (count user-ids)
               :cached?      true
               :evaluated-at (->instant cached-at)}))))))

  (invalidate [_ audience-id]
    (log/debug "Invalidating cache for audience" {:audience-id audience-id})
    (clear-stamp! datasource audience-id)
    (persistence/clear-memberships! datasource audience-id)
    nil)

  (invalidate-all [_]
    (log/debug "Invalidating all audience caches")
    (clear-all-stamps! datasource)
    ;; memberships are cleared by the service; here we leave them (stamp=nil
    ;; signals stale). For correctness we also delete memberships.
    (jdbc/execute!
     datasource
     (sql/format {:delete-from :audience_memberships})
     {:builder-fn rs/as-unqualified-lower-maps})
    nil))

;; =============================================================================
;; TTL-aware helper used by the service
;; =============================================================================

(defn get-cached-with-ttl
  "Check the DB cache for audience-id using explicit ttl-minutes.

   Returns a SegmentResult map (with :cached? true) if fresh, else nil.

   Args:
     cache       - AudienceCache instance
     audience-id - keyword
     ttl-minutes - integer (0 = always stale)"
  [^AudienceCache cache audience-id ttl-minutes]
  (let [datasource (:datasource cache)
        row        (load-segment-cache-row datasource audience-id)]
    (when row
      (let [cached-at (:cached_at row)]
        (when (and cached-at (fresh? cached-at ttl-minutes))
          (let [user-ids (set (persistence/get-memberships datasource audience-id))]
            {:user-ids     user-ids
             :count        (count user-ids)
             :cached?      true
             :evaluated-at (->instant cached-at)}))))))

;; =============================================================================
;; Factory
;; =============================================================================

(defn create-audience-cache
  "Create a DB-backed AudienceCache.

   Args:
     datasource      - javax.sql.DataSource
     wagoe-cache  - optional wagoe-cache instance for L2 (ignored for now)

   Returns:
     AudienceCache implementing IAudienceCache"
  ([datasource]
   (create-audience-cache datasource nil))
  ([datasource wagoe-cache]
   (->AudienceCache datasource wagoe-cache)))
