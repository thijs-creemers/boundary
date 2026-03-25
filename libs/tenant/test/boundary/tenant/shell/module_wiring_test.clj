(ns boundary.tenant.shell.module-wiring-test
  (:require [boundary.tenant.shell.module-wiring]
            [boundary.tenant.shell.http]
            [boundary.tenant.shell.membership-http]
            [boundary.tenant.shell.persistence]
            [boundary.tenant.shell.service]
            [boundary.tenant.shell.membership-persistence]
            [boundary.tenant.shell.membership-service]
            [boundary.tenant.shell.invite-persistence]
            [boundary.tenant.shell.invite-service]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

(deftest tenant-module-init-keys-delegate-to-constructor-functions
  (let [ctx {:datasource ::ds}
        logger ::logger
        error-reporter ::error-reporter
        metrics ::metrics
        tenant-repo ::tenant-repo
        membership-repo ::membership-repo
        invite-repo ::invite-repo
        tenant-service ::tenant-service
        membership-service ::membership-service
        db-context {:datasource ::db}
        config {:active {:boundary/settings {:name "Boundary"}}}]
    (with-redefs [boundary.tenant.shell.persistence/initialize-tenant-schema! (fn [arg]
                                                                                (is (= ctx arg))
                                                                                :initialized)
                  boundary.tenant.shell.persistence/create-tenant-repository (fn [arg log err]
                                                                              (is (= ctx arg))
                                                                              (is (= logger log))
                                                                              (is (= error-reporter err))
                                                                              tenant-repo)
                  boundary.tenant.shell.service/create-tenant-service (fn [repo validation-cfg log metrics-emitter err]
                                                                        (is (= tenant-repo repo))
                                                                        (is (= {:password-policy {:min-length 12}} validation-cfg))
                                                                        (is (= logger log))
                                                                        (is (= metrics metrics-emitter))
                                                                        (is (= error-reporter err))
                                                                        tenant-service)
                  boundary.tenant.shell.membership-persistence/create-membership-repository (fn [arg log err]
                                                                                              (is (= ctx arg))
                                                                                              (is (= logger log))
                                                                                              (is (= error-reporter err))
                                                                                              membership-repo)
                  boundary.tenant.shell.membership-service/create-membership-service (fn [repo log metrics-emitter err]
                                                                                       (is (= membership-repo repo))
                                                                                       (is (= logger log))
                                                                                       (is (= metrics metrics-emitter))
                                                                                       (is (= error-reporter err))
                                                                                       membership-service)
                  boundary.tenant.shell.invite-persistence/create-invite-repository (fn [arg log err]
                                                                                      (is (= ctx arg))
                                                                                      (is (= logger log))
                                                                                      (is (= error-reporter err))
                                                                                      invite-repo)
                  boundary.tenant.shell.invite-service/create-invite-service (fn [repo membership log metrics-emitter err]
                                                                               (is (= invite-repo repo))
                                                                               (is (= membership-repo membership))
                                                                               (is (= logger log))
                                                                               (is (= metrics metrics-emitter))
                                                                               (is (= error-reporter err))
                                                                               ::invite-service)
                  boundary.tenant.shell.http/tenant-routes-normalized (fn [service db-ctx cfg]
                                                                        (is (= tenant-service service))
                                                                        (is (= db-context db-ctx))
                                                                        (is (= config cfg))
                                                                        {:api [{:path "/tenants"}]})
                  boundary.tenant.shell.membership-http/membership-routes-normalized (fn [service]
                                                                                       (is (= membership-service service))
                                                                                       {:api [{:path "/tenants/:tenant-id/memberships"}]})]
      (testing "schema, repositories, services, and routes initialize through their constructors"
        (is (= {:status :initialized}
               (ig/init-key :boundary/tenant-db-schema {:ctx ctx})))
        (is (= tenant-repo
               (ig/init-key :boundary/tenant-repository {:ctx ctx
                                                         :logger logger
                                                         :error-reporter error-reporter})))
        (is (= tenant-service
               (ig/init-key :boundary/tenant-service {:tenant-repository tenant-repo
                                                      :validation-config {:password-policy {:min-length 12}}
                                                      :logger logger
                                                      :metrics-emitter metrics
                                                      :error-reporter error-reporter})))
        (is (= membership-repo
               (ig/init-key :boundary/membership-repository {:ctx ctx
                                                             :logger logger
                                                             :error-reporter error-reporter})))
        (is (= membership-service
               (ig/init-key :boundary/membership-service {:repository membership-repo
                                                          :logger logger
                                                          :metrics-emitter metrics
                                                          :error-reporter error-reporter})))
        (is (= invite-repo
               (ig/init-key :boundary/invite-repository {:ctx ctx
                                                         :logger logger
                                                         :error-reporter error-reporter})))
        (is (= ::invite-service
               (ig/init-key :boundary/invite-service {:repository invite-repo
                                                      :membership-repository membership-repo
                                                      :logger logger
                                                      :metrics-emitter metrics
                                                      :error-reporter error-reporter})))
        (is (= {:api [{:path "/tenants"}]}
               (ig/init-key :boundary/tenant-routes {:tenant-service tenant-service
                                                     :db-context db-context
                                                     :config config})))
        (is (= {:api [{:path "/tenants/:tenant-id/memberships"}]}
               (ig/init-key :boundary/membership-routes {:service membership-service})))))))
