(ns notification.notification.shell.http
  "HTTP handlers for notification API."
  (:require [notification.notification.ports :as ports]
            [notification.notification.core.notification :as notif-core]
            [cheshire.core :as json]
            [ring.util.response :as response])
  (:import [java.util UUID]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- str->uuid [s]
  (try (UUID/fromString s)
       (catch Exception _ nil)))

(defn- json-response [data status]
  (-> (response/response (json/generate-string data))
      (response/status status)
      (response/content-type "application/json")))

;; =============================================================================
;; Handlers
;; =============================================================================

(defn list-notifications-handler
  "GET /api/notifications - List notifications."
  [notification-service]
  (fn [request]
    (let [params (:query-params request)
          options {:status (some-> (get params "status") keyword)
                   :channel (some-> (get params "channel") keyword)
                   :limit (some-> (get params "limit") parse-long)
                   :offset (some-> (get params "offset") parse-long)}
          result (ports/list-notifications notification-service options)
          {:keys [notifications total]} (:ok result)]
      (json-response {:data (notif-core/notifications->api notifications)
                      :meta {:total total
                             :limit (or (:limit options) 50)
                             :offset (or (:offset options) 0)}}
                     200))))

(defn get-notification-handler
  "GET /api/notifications/:id - Get notification by ID."
  [notification-service]
  (fn [request]
    (let [notification-id (str->uuid (get-in request [:path-params :id]))]
      (if notification-id
        (let [result (ports/get-notification notification-service notification-id)]
          (if (:ok result)
            (json-response {:data (notif-core/notification->api (:ok result))} 200)
            (json-response {:error {:code "not_found"
                                    :message "Notification not found"}}
                           404)))
        (json-response {:error {:code "bad_request"
                                :message "Invalid notification ID"}}
                       400)))))

(defn retry-notification-handler
  "POST /api/notifications/:id/retry - Retry a failed notification."
  [notification-service]
  (fn [request]
    (let [notification-id (str->uuid (get-in request [:path-params :id]))]
      (if notification-id
        (let [result (ports/retry-notification notification-service notification-id)]
          (cond
            (:ok result)
            (json-response {:data (notif-core/notification->api (:ok result))
                            :message "Notification retried"}
                           200)
            
            (= :retry-exhausted (:error result))
            (json-response {:error {:code "retry_exhausted"
                                    :message "Maximum retry attempts reached"
                                    :attempts (:attempts result)
                                    :max-attempts (:max-attempts result)}}
                           422)
            
            :else
            (json-response {:error {:code "not_found"
                                    :message "Notification not found"}}
                           404)))
        (json-response {:error {:code "bad_request"
                                :message "Invalid notification ID"}}
                       400)))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  "Notification API routes."
  [notification-service]
  [["/api/notifications"
    {:get {:handler (list-notifications-handler notification-service)
           :summary "List notifications"}}]
   ["/api/notifications/:id"
    {:get {:handler (get-notification-handler notification-service)
           :summary "Get notification by ID"}}]
   ["/api/notifications/:id/retry"
    {:post {:handler (retry-notification-handler notification-service)
            :summary "Retry failed notification"}}]])
