(ns boundary.platform.shell.system.wiring-test
  (:require [boundary.platform.shell.system.wiring]
            [boundary.platform.ports.http]
            [boundary.platform.shell.http.reitit-router]
            [boundary.platform.shell.http.versioning]
            [boundary.platform.shell.interfaces.http.common]
            [boundary.platform.shell.interfaces.http.tenant-middleware]
            [boundary.tenant.shell.membership-middleware]
            [boundary.i18n.shell.middleware]
            [boundary.observability.logging.shell.adapters.no-op]
            [boundary.observability.metrics.shell.adapters.no-op]
            [boundary.observability.errors.shell.adapters.no-op]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

(deftest router-init-falls-back-to-reitit-for-unknown-adapters
  (with-redefs [boundary.platform.shell.http.reitit-router/create-reitit-router
                (fn [] ::reitit-router)]
    (is (= ::reitit-router
           (ig/init-key :boundary/router {:adapter :unknown})))))

(deftest http-handler-includes-membership-routes-and-optional-middleware
  (let [captured-routes (atom nil)
        captured-config (atom nil)
        compiled-handler (fn [request] {:status 200 :body request})
        config {:active {:boundary/settings {:name "Boundary"
                                            :version "1.2.3"}}}
        handler (with-redefs [boundary.platform.ports.http/compile-routes
                              (fn [_router routes router-config]
                                (reset! captured-routes routes)
                                (reset! captured-config router-config)
                                compiled-handler)
                              boundary.platform.shell.interfaces.http.tenant-middleware/wrap-multi-tenant
                              (fn [wrapped-handler tenant-service db-context opts]
                                (fn [request]
                                  (wrapped-handler
                                   (assoc request
                                          :tenant-service tenant-service
                                          :db-context db-context
                                          :tenant-opts opts))))
                              boundary.tenant.shell.membership-middleware/wrap-tenant-membership
                              (fn [membership-service wrapped-handler]
                                (fn [request]
                                  (wrapped-handler
                                   (assoc request :membership-service membership-service))))
                              boundary.platform.shell.interfaces.http.common/health-check-handler
                              (fn [_app-name _version _details]
                                (fn [_request] {:status 200}))
                              boundary.platform.shell.http.versioning/apply-versioning
                              (fn [routes _config] (vec routes))
                              boundary.platform.shell.http.versioning/wrap-handler-with-version-headers
                              (fn [wrapped-handler _config] wrapped-handler)
                              boundary.i18n.shell.middleware/wrap-i18n
                              (fn [wrapped-handler _i18n]
                                (fn [request]
                                  (wrapped-handler (assoc request :i18n true))))]
                  (ig/init-key :boundary/http-handler
                               {:user-routes {:api [{:path "/users" :methods {:get {:handler identity}}}]}
                                :tenant-routes {:api [{:path "/tenants" :methods {:get {:handler identity}}}]}
                                :membership-routes {:api [{:path "/tenants/:tenant-id/memberships"
                                                           :methods {:get {:handler identity}}}]}
                                :router ::router
                                :logger ::logger
                                :metrics-emitter ::metrics
                                :error-reporter ::error-reporter
                                :config config
                                :tenant-service ::tenant-service
                                :membership-service ::membership-service
                                :db-context ::db-context
                                :i18n ::i18n}))]
    (testing "the compiled route set includes membership endpoints"
      (is (some #(= "/tenants/:tenant-id/memberships" (:path %)) @captured-routes))
      (is (some #(= "/tenants" (:path %)) @captured-routes))
      (is (some #(= "/users" (:path %)) @captured-routes)))

    (testing "router config receives tenant, membership, i18n, and method override middleware"
      (is (= 4 (count (:middleware @captured-config))))
      (is (= {:logger ::logger
              :metrics-emitter ::metrics
              :error-reporter ::error-reporter}
             (:system @captured-config))))

    (testing "the returned handler is the compiled handler after wrapping"
      (is (= {:status 200 :body {:request-method :get}}
             (handler {:request-method :get}))))))

(deftest http-handler-normalizes-web-routes-and-skips-optional-middleware-when-disabled
  (let [captured-routes (atom nil)
        captured-config (atom nil)
        compiled-handler (fn [request] {:status 200 :body request})
        handler (with-redefs [boundary.platform.ports.http/compile-routes
                              (fn [_router routes router-config]
                                (reset! captured-routes routes)
                                (reset! captured-config router-config)
                                compiled-handler)
                              boundary.platform.shell.interfaces.http.common/health-check-handler
                              (fn [_app-name _version _details]
                                (fn [_request] {:status 200}))
                              boundary.platform.shell.http.versioning/apply-versioning
                              (fn [routes _config] (vec routes))
                              boundary.platform.shell.http.versioning/wrap-handler-with-version-headers
                              (fn [wrapped-handler _config] wrapped-handler)]
                  (ig/init-key :boundary/http-handler
                               {:user-routes {:web [{:path "/profile"
                                                     :meta {:middleware [:user-mw]}
                                                     :methods {:get {:handler identity}}}]}
                                :admin-routes {:web [{:path "/users"
                                                      :meta {:middleware [:admin-mw]}
                                                      :methods {:get {:handler identity}}}]}
                                :workflow-routes {:web [{:path "/workflow"
                                                         :meta {:middleware [:workflow-mw]}
                                                         :methods {:get {:handler identity}}}]}
                                :search-routes {:web [{:path "/search"
                                                       :meta {:middleware [:search-mw]}
                                                       :methods {:get {:handler identity}}}]}
                                :router ::router
                                :logger ::logger
                                :metrics-emitter ::metrics
                                :error-reporter ::error-reporter
                                :config {:active {:boundary/settings {:name "Boundary"
                                                                      :version "1.2.3"}}}}))]
    (testing "web routes are prefixed and route meta is merged at the route root"
      (is (some #(and (= "/web/profile" (:path %))
                      (= [:user-mw] (:middleware %))
                      (= true (:no-doc %)))
                @captured-routes))
      (is (some #(and (= "/web/admin/users" (:path %))
                      (= [:admin-mw] (:middleware %))
                      (= true (:no-doc %)))
                @captured-routes))
      (is (some #(and (= "/web/admin/workflow" (:path %))
                      (= [:workflow-mw] (:middleware %))
                      (= true (:no-doc %)))
                @captured-routes))
      (is (some #(and (= "/web/admin/search" (:path %))
                      (= [:search-mw] (:middleware %))
                      (= true (:no-doc %)))
                @captured-routes)))

    (testing "only method override middleware is configured when tenant, membership, and i18n are absent"
      (is (= 1 (count (:middleware @captured-config))))
      (is (= {:logger ::logger
              :metrics-emitter ::metrics
              :error-reporter ::error-reporter}
             (:system @captured-config))))

    (testing "method override middleware rewrites POST requests to the requested verb"
      (let [wrapped-handler ((first (:middleware @captured-config)) compiled-handler)]
        (is (= {:status 200 :body {:request-method :delete
                                   :form-params {"_method" "DELETE"}}}
               (wrapped-handler {:request-method :post
                                 :form-params {"_method" "DELETE"}})))
        (is (= {:status 200 :body {:request-method :patch
                                   :params {"_method" "PATCH"}}}
               (wrapped-handler {:request-method :post
                                 :params {"_method" "PATCH"}})))
        (is (= {:status 200 :body {:request-method :get}}
               (handler {:request-method :get})))))))

(deftest component-init-falls-back-to-no-op-providers-for-unknown-adapters
  (with-redefs [boundary.observability.logging.shell.adapters.no-op/create-logging-component
                (fn [config] [:logging-no-op config])
                boundary.observability.metrics.shell.adapters.no-op/create-metrics-component
                (fn [config] [:metrics-no-op config])
                boundary.observability.errors.shell.adapters.no-op/create-error-reporting-component
                (fn [config] [:errors-no-op config])]
    (is (= [:logging-no-op {:provider :mystery}]
           (ig/init-key :boundary/logging {:provider :mystery})))
    (is (= [:metrics-no-op {:provider :mystery}]
           (ig/init-key :boundary/metrics {:provider :mystery})))
    (is (= [:errors-no-op {:provider :mystery}]
           (ig/init-key :boundary/error-reporting {:provider :mystery})))))
