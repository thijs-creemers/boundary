(ns wagoe.session-pruning-test
  "An expired session leaves the table, not just the query results.

   `find-session-by-token` filters on `expires_at` and `revoked_at`, so an
   expired session is invisible while its row stays. Nothing deleted one until
   BOU-429, and every login added another. These assertions count rows, because
   counting what the reads return would have passed the whole time."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [wagoe.platform.shell.adapters.database.factory :as factory]
            [wagoe.user.core.session :as session-core]
            [wagoe.user.ports :as ports]
            [wagoe.user.shell.persistence :as persistence]
            [wagoe.user.shell.session-pruner :as pruner])
  (:import [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; The pure part
;; =============================================================================

(deftest ^:unit retention-counts-from-expiry
  (let [now (Instant/parse "2026-09-12T12:00:00Z")]

    (testing "a retention window moves the cutoff back by that many days"
      (is (= (Instant/parse "2026-08-13T12:00:00Z") (session-core/prune-cutoff now 30)))
      (is (= (Instant/parse "2026-09-05T12:00:00Z") (session-core/prune-cutoff now 7))))

    (testing "zero retention deletes a session the moment it expires"
      (is (= now (session-core/prune-cutoff now 0))))

    (testing "a negative retention does not reach into the future"
      (is (= now (session-core/prune-cutoff now -5))))))

;; =============================================================================
;; The row
;; =============================================================================

(defn- session-count [ds]
  (:n (first (jdbc/execute! ds ["SELECT COUNT(*) AS n FROM user_sessions"]
                            {:builder-fn rs/as-unqualified-lower-maps}))))

(defn- session-at [user-id expires-at]
  {:id (UUID/randomUUID)
   :user-id user-id
   :session-token (str (UUID/randomUUID))
   :created-at (Instant/now)
   :expires-at expires-at})

(defn- fixture
  "A real db-context, not a hand-built one: the adapter's JDBC URL carries
   DATABASE_TO_LOWER, without which H2 cannot find information_schema."
  []
  (let [ctx (factory/db-context
             (factory/h2-config (str "mem:prune_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")))]
    (persistence/initialize-user-schema! ctx)
    [(:datasource ctx) (persistence/->DatabaseUserSessionRepository ctx)]))

(deftest ^:integration pruning-removes-the-row-not-just-the-result
  (let [[ds repo] (fixture)
        user-id (UUID/randomUUID)
        now (Instant/now)
        long-expired (.minusSeconds now (* 40 24 3600))
        recently-expired (.minusSeconds now (* 2 24 3600))
        live (.plusSeconds now 3600)]

    (doseq [s [(session-at user-id long-expired)
               (session-at user-id recently-expired)
               (session-at user-id live)]]
      (ports/create-session repo s))

    (testing "all three are rows, and only the live one is readable"
      (is (= 3 (session-count ds)))
      (is (= 1 (count (ports/find-sessions-by-user repo user-id)))
          "the two expired ones are already invisible — which is why the reads
           could never have caught this"))

    (testing "a 30-day retention removes the long-expired one and nothing else"
      (let [removed (pruner/prune-once! repo {:retention-days 30})]
        (is (= 1 removed))
        (is (= 2 (session-count ds)))))

    (testing "running it again removes nothing"
      (is (zero? (pruner/prune-once! repo {:retention-days 30})))
      (is (= 2 (session-count ds))))

    (testing "zero retention takes every expired session, and leaves the live one"
      (is (= 1 (pruner/prune-once! repo {:retention-days 0})))
      (is (= 1 (session-count ds)))
      (is (= 1 (count (ports/find-sessions-by-user repo user-id)))))))

(deftest ^:integration a-revoked-session-is-pruned-on-the-same-terms
  (testing "revoking hides a session; only expiry plus retention removes it"
    (let [[ds repo] (fixture)
          user-id (UUID/randomUUID)
          now (Instant/now)
          session (session-at user-id (.plusSeconds now 3600))]
      (ports/create-session repo session)
      (ports/invalidate-session repo (:session-token session))

      (testing "revoked, invisible, still a row"
        (is (empty? (ports/find-sessions-by-user repo user-id)))
        (is (= 1 (session-count ds))))

      (testing "and it stays one until it has expired"
        (is (zero? (pruner/prune-once! repo {:retention-days 0})))
        (is (= 1 (session-count ds)))))))

(deftest ^:integration a-prune-that-throws-does-not-take-the-schedule-down
  (testing "this runs on a timer, so a failure must be logged and survived"
    ;; Only the one method is reached; the pruner calls nothing else.
    (let [exploding #_{:clj-kondo/ignore [:missing-protocol-method]}
          (reify ports/IUserSessionRepository
            (delete-sessions-expired-before [_ _]
              (throw (ex-info "database is away" {}))))]
      (is (nil? (pruner/prune-once! exploding {:retention-days 30}))))))
