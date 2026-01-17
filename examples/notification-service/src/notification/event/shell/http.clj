(ns notification.event.shell.http
  "HTTP handlers for event API."
  (:require [notification.event.ports :as ports]
            [notification.event.core.event :as event-core]
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

(defn publish-event-handler
  "POST /api/events - Receive and publish an event."
  [event-service]
  (fn [request]
    (let [body (:json-body request)
          ;; Convert string UUIDs to actual UUIDs
          event-data (-> body
                         (update :aggregate-id #(if (string? %) (str->uuid %) %))
                         (update :type keyword)
                         (update :aggregate-type keyword))]
      (if (and (:type event-data) (:aggregate-id event-data))
        (let [result (ports/publish-event event-service event-data)]
          (if (:ok result)
            (json-response {:data (event-core/event->api (:ok result))} 201)
            (json-response {:error {:code "validation_error"
                                    :message "Invalid event data"
                                    :details (:details result)}}
                           400)))
        (json-response {:error {:code "bad_request"
                                :message "type and aggregate-id are required"}}
                       400)))))

(defn get-event-handler
  "GET /api/events/:id - Get event by ID."
  [event-service]
  (fn [request]
    (let [event-id (str->uuid (get-in request [:path-params :id]))]
      (if event-id
        (let [result (ports/get-event event-service event-id)]
          (if (:ok result)
            (json-response {:data (event-core/event->api (:ok result))} 200)
            (json-response {:error {:code "not_found"
                                    :message "Event not found"}}
                           404)))
        (json-response {:error {:code "bad_request"
                                :message "Invalid event ID"}}
                       400)))))

(defn list-events-handler
  "GET /api/events - List events."
  [event-service]
  (fn [request]
    (let [params (:query-params request)
          options {:type (some-> (get params "type") keyword)
                   :limit (some-> (get params "limit") parse-long)
                   :offset (some-> (get params "offset") parse-long)}
          result (ports/list-recent-events event-service options)
          {:keys [events total]} (:ok result)]
      (json-response {:data (event-core/events->api events)
                      :meta {:total total
                             :limit (or (:limit options) 50)
                             :offset (or (:offset options) 0)}}
                     200))))

;; =============================================================================
;; Routes
;; =============================================================================

(defn routes
  "Event API routes."
  [event-service]
  [["/api/events"
    {:get {:handler (list-events-handler event-service)
           :summary "List events"}
     :post {:handler (publish-event-handler event-service)
            :summary "Publish new event"}}]
   ["/api/events/:id"
    {:get {:handler (get-event-handler event-service)
           :summary "Get event by ID"}}]])
