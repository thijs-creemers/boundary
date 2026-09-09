(ns wagoe.audience-runtime-test
  "Can an application actually enable audience?

   Until BOU-419 it could not. `:wagoe/audience`'s init-key throws without a
   `:user-data-source`, and `IUserDataSource` had no implementation anywhere —
   the only one in the repository was a `reify` inside audience's own tests. The
   module also shipped no `ig-config`, so the platform emitted its settings map
   and built neither the service nor the routes, and no schema component, so a
   booted app had no `audience_segments` table to write to.

   Each of those is invisible until something boots the module, which nothing
   did."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [wagoe.audience.ports :as audience-ports]
            [wagoe.config :as config]
            [wagoe.system-config :as sys-config])
  (:import [java.util UUID]))

(defn- audience-config []
  (-> (config/load-config {:profile :test})
      (assoc-in [:active :wagoe/audience] {:enabled? true})))

(defn- with-system [f]
  (let [system (ig/init (sys-config/ig-config (audience-config)))]
    (try (f system) (finally (ig/halt! system)))))

(defn- insert-user!
  "A row in the users table the data source reads."
  [ds id name*]
  (jdbc/execute! ds ["INSERT INTO users (id, name, role, created_at)
                        VALUES (?,?,?,CURRENT_TIMESTAMP)"
                     id name* "user"])
  id)

(deftest ^:integration audience-boots-and-mounts-its-routes
  (with-system
    (fn [system]
      (testing "the module assembles a graph rather than a settings map"
        (is (some? (:wagoe/audience-db-schema system))   "no schema component")
        (is (some? (:wagoe/audience-user-source system)) "no user data source")
        (is (some? (:wagoe/audience system))             "no audience service"))

      (testing "the data source is the protocol the service demands"
        (is (satisfies? audience-ports/IUserDataSource
                        (:wagoe/audience-user-source system))))

      (testing "and its routes are mounted, not merely defined"
        (is (seq (:api (:wagoe/audience-routes system))))
        (is (some #{(ig/ref :wagoe/audience-routes)}
                  (get-in (sys-config/ig-config (audience-config))
                          [:wagoe/http-handler :module-routes]))
            "the routes component exists but the handler never receives it")))))

(deftest ^:integration an-audience-segments-the-real-users-table
  ;; The end of the loop: a stored definition, resolved through the service,
  ;; reading the users the application actually has. Empty filters are the
  ;; universe, so this is the whole table — the case the port's docstring warns
  ;; is easy to implement as "nobody".
  (with-system
    (fn [system]
      (let [ds    (get-in system [:wagoe/db-context :datasource])
            alice (insert-user! ds (UUID/randomUUID) "Alice")
            bob   (insert-user! ds (UUID/randomUUID) "Bob")
            store (:store (:wagoe/audience system))]

        (testing "the schema component created the tables the store writes to"
          (is (some? (audience-ports/save-audience
                      store {:id :everyone :label "Everyone" :filters []}))))

        (testing "resolving it returns the users in the database"
          (let [result (audience-ports/resolve-audience
                        (:resolver (:wagoe/audience system))
                        :everyone
                        {:force-refresh? true})]
            (is (= #{alice bob} (set (:user-ids result)))
                (str "expected both users, got " (pr-str (:user-ids result))))
            (is (= 2 (:count result)))))))))
