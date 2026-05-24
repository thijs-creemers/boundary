(ns boundary.push.shell.service-test
  (:require [clojure.test :refer [deftest use-fixtures is]]
            [boundary.push.shell.service :as service]
            [boundary.push.shell.adapters.mock :as mock]
            [boundary.push.shell.persistence :as p]
            [boundary.push.shell.persistence-test :as pt]
            [boundary.push.core.notification :as notif]
            [boundary.push.ports :as ports]
            [boundary.jobs.ports]))

(use-fixtures :each
  (fn [f]
    (notif/clear-registry!)
    (binding [pt/*db* (pt/create-test-db)]
      (f))))

(deftest ^:integration send-push-enqueues-job
  (notif/register-push!
   {:id :test-push :title "Hello" :body "World" :channels #{:fcm}})

  (let [jobs-atom    (atom [])
        mock-queue   (reify boundary.jobs.ports/IJobQueue
                       (enqueue-job! [_ queue-name job]
                         (swap! jobs-atom conj {:queue queue-name :job job})
                         (:id job)))
        device-store (p/->DeviceTokenStore pt/*db*)
        analytics    (p/->PushAnalyticsStore pt/*db*)
        svc          (service/->PushService
                      device-store analytics
                      (mock/->MockFCMProvider)
                      (mock/->MockAPNsProvider)
                      mock-queue
                      "test-callback-secret")]
    (ports/send-push! svc :test-push {:order-id "1"} {:user-id (random-uuid) :locale :en})
    (is (= 1 (count @jobs-atom)))
    (is (= :push/send (:job-type (:job (first @jobs-atom)))))))
