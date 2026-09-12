(ns wagoe.user.shell.session-pruner
  "Removes expired sessions on a schedule.

   Expiry only hides a session: `find-session-by-token` filters on `expires_at`
   and `revoked_at`, so the reads stay correct while the table grows with every
   login, forever. Nothing deleted a row until BOU-429.

   The schedule lives here rather than in the jobs module because jobs is
   optional and the user module is not — a deployment that never enables jobs
   still has the table. One timer per process: every replica prunes, which is
   harmless, because a DELETE that finds nothing costs a single indexed scan
   and two replicas deleting the same rows is the same DELETE twice."
  (:require [clojure.tools.logging :as log]
            [wagoe.user.core.session :as session-core]
            [wagoe.user.ports :as ports])
  (:import [java.time Instant]
           [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(def default-settings
  "Retention is counted from expiry. Thirty days leaves enough history to answer
   \"where was this account signed in\" after an incident without keeping the
   row for ever."
  {:enabled?       true
   :retention-days 30
   :interval-hours 6})

(defn prune-once!
  "Delete sessions that expired more than `:retention-days` ago.

   Returns the number of rows removed, or nil when the prune threw — this runs
   on a timer, and a failed prune must not take the schedule down with it."
  [session-repository {:keys [retention-days] :as _settings}]
  (let [cutoff (session-core/prune-cutoff (Instant/now)
                                          (or retention-days
                                              (:retention-days default-settings)))]
    (try
      (let [removed (ports/delete-sessions-expired-before session-repository cutoff)]
        (when (pos? (or removed 0))
          (log/info "Pruned expired sessions" {:removed removed :expired-before cutoff}))
        removed)
      (catch Exception e
        (log/error e "Session prune failed; the schedule continues"
                   {:expired-before cutoff})
        nil))))

(defn start!
  "Begin pruning on the configured interval. Returns a handle for `stop!`.

   The first run is one interval away, not at boot: a process that restarts in a
   crash loop would otherwise issue a DELETE per restart."
  [session-repository settings]
  (let [{:keys [interval-hours] :as settings} (merge default-settings settings)
        hours (or interval-hours (:interval-hours default-settings))
        ^ScheduledExecutorService executor
        (Executors/newSingleThreadScheduledExecutor
         (reify java.util.concurrent.ThreadFactory
           (newThread [_ runnable]
             (doto (Thread. runnable "wagoe-session-pruner")
               (.setDaemon true)))))]
    (.scheduleAtFixedRate executor
                          ^Runnable #(prune-once! session-repository settings)
                          hours hours TimeUnit/HOURS)
    (log/info "Session pruner started"
              {:retention-days (:retention-days settings) :interval-hours hours})
    {:executor executor :settings settings}))

(defn stop!
  "Stop the schedule. Safe to call on a nil handle."
  [{:keys [^ScheduledExecutorService executor]}]
  (when executor
    (.shutdownNow executor)
    (log/info "Session pruner stopped"))
  nil)
