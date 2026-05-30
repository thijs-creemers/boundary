(ns boundary.push.shell.module-wiring
  (:require [boundary.push.shell.service :as service]
            [boundary.push.shell.persistence :as persistence]
            [boundary.push.shell.adapters.mock :as mock]
            [boundary.push.shell.adapters.fcm :as fcm]
            [boundary.push.shell.adapters.apns :as apns]
            [boundary.push.shell.handlers :as handlers]
            [boundary.push.shell.jobs :as jobs]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defmethod ig/init-key :boundary.push/fcm-provider
  [_ {:keys [provider project-id credentials-path]}]
  (case (or provider :mock)
    :mock (do (log/info "Push: using mock FCM provider")
              (mock/->MockFCMProvider))
    :fcm  (do (log/info "Push: initializing FCM provider" {:project-id project-id})
              (fcm/make-fcm-provider project-id credentials-path))))

(defmethod ig/init-key :boundary.push/apns-provider
  [_ {:keys [provider team-id key-id key-path bundle-id sandbox?]}]
  (case (or provider :mock)
    :mock (do (log/info "Push: using mock APNs provider")
              (mock/->MockAPNsProvider))
    :apns (do (log/info "Push: initializing APNs provider" {:team-id team-id :sandbox? sandbox?})
              (apns/make-apns-provider team-id key-id key-path bundle-id sandbox?))))

(defmethod ig/init-key :boundary.push/device-store
  [_ {:keys [db]}]
  (log/info "Push: initializing device token store")
  (persistence/->DeviceTokenStore db))

(defmethod ig/init-key :boundary.push/analytics-store
  [_ {:keys [db]}]
  (log/info "Push: initializing analytics store")
  (persistence/->PushAnalyticsStore db))

(defmethod ig/init-key :boundary.push/service
  [_ {:keys [device-store analytics-store fcm-provider apns-provider job-queue callback-secret]}]
  (log/info "Push: initializing push service")
  (service/->PushService device-store analytics-store fcm-provider apns-provider job-queue callback-secret))

(defmethod ig/init-key :boundary.push/job-handlers
  [_ {:keys [push-service _job-registry]}]
  (let [deps {:push-service    push-service
              :device-store    (:device-store push-service)
              :fcm-provider    (:fcm-provider push-service)
              :apns-provider   (:apns-provider push-service)
              :analytics-store (:analytics-store push-service)
              :callback-secret (:callback-secret push-service)}]
    (log/info "Push: registering job handlers")
    {:push/send      (partial jobs/handle-send-push deps)
     :push/broadcast (partial jobs/handle-broadcast deps)}))

(defmethod ig/init-key :boundary.push/routes
  [_ {:keys [device-store analytics-store callback-secret]}]
  (handlers/push-routes {:device-store    device-store
                         :analytics-store analytics-store
                         :callback-secret callback-secret}))
