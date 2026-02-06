(ns boundary.tenant.shell.service-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [boundary.tenant.shell.service :as sut]
            [boundary.tenant.ports :as ports]
            [boundary.observability.errors.ports :as error-ports])
  (:import (java.time Instant)
           (java.util UUID)))

(def ^:dynamic *tenant-repository* nil)

;; Mock observability services
(def mock-logger
  (reify
    Object
    (toString [_] "MockLogger")))

(def mock-metrics-emitter
  (reify
    Object
    (toString [_] "MockMetricsEmitter")))

(def mock-error-reporter
  (reify
    error-ports/IErrorContext
    (add-breadcrumb! [_ _breadcrumb] nil)
    (with-context [_ _context-map f] (f))
    Object
    (toString [_] "MockErrorReporter")))

(defrecord MockTenantRepository [state]
  ports/ITenantRepository

  (find-tenant-by-id [_ tenant-id]
    (get @state tenant-id))

  (find-tenant-by-slug [_ slug]
    (->> @state
         vals
         (filter #(= slug (:slug %)))
         first))

  (find-all-tenants [_ options]
    (vals @state))

  (create-tenant [_ tenant-entity]
    (swap! state assoc (:id tenant-entity) tenant-entity)
    tenant-entity)

  (update-tenant [_ tenant-entity]
    (swap! state assoc (:id tenant-entity) tenant-entity)
    tenant-entity)

  (delete-tenant [_ tenant-id]
    (swap! state dissoc tenant-id)
    nil)

  (tenant-slug-exists? [_ slug]
    (boolean (some #(= slug (:slug %)) (vals @state))))

  (create-tenant-schema [_ schema-name]
    nil)

  (drop-tenant-schema [_ schema-name]
    nil))

(defn setup-mock-repository []
  (alter-var-root #'*tenant-repository*
                  (constantly (->MockTenantRepository (atom {})))))

(defn teardown-mock-repository []
  (alter-var-root #'*tenant-repository* (constantly nil)))

(use-fixtures :each
  (fn [f]
    (setup-mock-repository)
    (f)
    (teardown-mock-repository)))

(deftest create-new-tenant-test
  (testing "creates new tenant successfully"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          result (ports/create-new-tenant service
                                          {:slug "acme-corp"
                                           :name "ACME Corporation"
                                           :settings {:features {:mfa-enabled true}}})]
      (is (uuid? (:id result)))
      (is (= "acme-corp" (:slug result)))
      (is (= "ACME Corporation" (:name result)))
      (is (= "tenant_acme_corp" (:schema-name result)))
      (is (= :active (:status result)))))

  (testing "rejects duplicate slug"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)]
      (ports/create-new-tenant service {:slug "acme-corp" :name "ACME"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant slug already exists"
                            (ports/create-new-tenant service {:slug "acme-corp" :name "Duplicate"}))))))

(deftest get-tenant-test
  (testing "retrieves existing tenant by ID"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "test-tenant" :name "Test"})
          retrieved (ports/get-tenant service (:id created))]
      (is (= created retrieved))))

  (testing "throws error for non-existent tenant"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tenant not found"
                            (ports/get-tenant service (UUID/randomUUID)))))))

(deftest update-existing-tenant-test
  (testing "updates tenant name and status"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "test" :name "Old Name"})
          updated (ports/update-existing-tenant service (:id created) {:name "New Name" :status :suspended})]
      (is (= "New Name" (:name updated)))
      (is (= :suspended (:status updated))))))

(deftest suspend-and-activate-tenant-test
  (testing "suspends active tenant"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "test" :name "Test"})
          suspended (ports/suspend-tenant service (:id created))]
      (is (= :suspended (:status suspended)))))

  (testing "activates suspended tenant"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "test" :name "Test"})
          suspended (ports/suspend-tenant service (:id created))
          activated (ports/activate-tenant service (:id created))]
      (is (= :active (:status activated))))))

(deftest delete-existing-tenant-test
  (testing "soft deletes tenant"
    (let [service (sut/create-tenant-service *tenant-repository* {} mock-logger mock-metrics-emitter mock-error-reporter)
          created (ports/create-new-tenant service {:slug "test" :name "Test"})]
      (ports/delete-existing-tenant service (:id created))
      (let [retrieved (ports/get-tenant service (:id created))]
        (is (= :deleted (:status retrieved)))
        (is (some? (:deleted-at retrieved)))))))
