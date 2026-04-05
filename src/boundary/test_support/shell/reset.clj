(ns boundary.test-support.shell.reset
  "Side-effecting reset of the H2 test database.
   Safe to call only in the :test profile."
  (:require [next.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [boundary.test-support.core :as core]
            [boundary.tenant.ports :as tenant-ports]
            [boundary.user.ports :as user-ports]))

(def ^:private tables-in-truncation-order
  ;; Order does not matter since we disable referential integrity,
  ;; but listing child tables first documents the foreign-key graph.
  ;; Names match the real production tables created by
  ;; `boundary.user.shell.persistence/initialize-user-schema!` and
  ;; `boundary.tenant.shell.persistence/initialize-tenant-schema!`.
  ["user_sessions"
   "user_audit_log"
   "tenant_memberships"
   "tenant_member_invites"
   "users"
   "auth_users"
   "tenants"])

(defn truncate-all!
  "Truncates every table the e2e suite might touch. Uses H2's
   SET REFERENTIAL_INTEGRITY FALSE because H2 does not support
   TRUNCATE ... CASCADE."
  [ds]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute! tx ["SET REFERENTIAL_INTEGRITY FALSE"])
    (try
      (doseq [t tables-in-truncation-order]
        (jdbc/execute! tx [(str "TRUNCATE TABLE " t)]))
      (finally
        (jdbc/execute! tx ["SET REFERENTIAL_INTEGRITY TRUE"]))))
  (log/debug "test-support: truncated all tables"))

(defn- strip-password-hash
  "Defensive: production services are supposed to strip :password-hash on the
   way out, but we dissoc anyway so a regression in the service can never
   leak the hash to test consumers."
  [entity]
  (dissoc entity :password-hash))

(defn seed-baseline!
  "Creates the baseline test fixture described by
   `boundary.test-support.core/baseline-seed-spec` by calling the real
   production services: the tenant is created via
   `boundary.tenant.ports/create-new-tenant` and the users are registered via
   `boundary.user.ports/register-user`. Going through the production write
   path is deliberate: any schema drift between the seed spec and what the
   services actually accept will surface here as a contract test failure.

   Arguments:
     {:user-service   instance implementing boundary.user.ports/IUserService
      :tenant-service instance implementing boundary.tenant.ports/ITenantService}

   Returns:
     {:tenant <tenant-entity>
      :admin  <user-entity with :password re-attached, :password-hash removed>
      :user   <user-entity with :password re-attached, :password-hash removed>}"
  [{:keys [user-service tenant-service]}]
  (let [spec            (core/baseline-seed-spec)
        tenant-input    (select-keys (:tenant spec) [:slug :name])
        tenant          (tenant-ports/create-new-tenant tenant-service tenant-input)
        admin-spec      (:admin spec)
        user-spec       (:user spec)
        created-admin   (user-ports/register-user user-service admin-spec)
        created-user    (user-ports/register-user user-service user-spec)]
    (log/debug "test-support: seeded baseline tenant + users"
               {:tenant-id (:id tenant)
                :admin-id  (:id created-admin)
                :user-id   (:id created-user)})
    {:tenant tenant
     :admin  (-> created-admin
                 strip-password-hash
                 (assoc :password (:password admin-spec)))
     :user   (-> created-user
                 strip-password-hash
                 (assoc :password (:password user-spec)))}))
