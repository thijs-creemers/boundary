(ns notification.system
  "System component configuration using Integrant."
  (:require [integrant.core :as ig]
            [notification.shared.bus :as bus]
            [notification.event.shell.store :as event-store]
            [notification.event.shell.service :as event-service]
            [notification.notification.shell.store :as notif-store]
            [notification.notification.shell.sender :as notif-sender]
            [notification.notification.shell.service :as notif-service]
            [notification.handler.order :as order-handler]
            [notification.handler.payment :as payment-handler]
            [notification.handler.shipment :as shipment-handler]
            [notification.event.shell.http :as event-http]
            [notification.notification.shell.http :as notif-http]
            [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [cheshire.core :as json]
            [ring.util.response :as response]))

;; =============================================================================
;; Config
;; =============================================================================

(defn config
  "System configuration map."
  [env-config]
  {;; Message Bus
   :notification/bus {}
   
   ;; Stores
   :notification/event-store {}
   :notification/notification-store {}
   
   ;; Notification Sender
   :notification/sender
   {:config {:channels {:email {:enabled true}
                        :sms {:enabled true}
                        :push {:enabled true}}}}
   
   ;; Services
   :notification/event-service
   {:store (ig/ref :notification/event-store)
    :bus (ig/ref :notification/bus)}
   
   :notification/notification-service
   {:store (ig/ref :notification/notification-store)
    :sender (ig/ref :notification/sender)
    :config {:retry {:max-attempts 3
                     :base-delay-ms 1000
                     :max-delay-ms 60000}}}
   
   ;; Event Handlers
   :notification/handlers
   {:bus (ig/ref :notification/bus)
    :notification-service (ig/ref :notification/notification-service)}
   
   ;; HTTP Server
   :notification/http-server
   {:port (get env-config :port 3003)
    :event-service (ig/ref :notification/event-service)
    :notification-service (ig/ref :notification/notification-service)}})

;; =============================================================================
;; Integrant Init Methods
;; =============================================================================

(defmethod ig/init-key :notification/bus [_ _]
  (println "Starting message bus...")
  (bus/create-bus))

(defmethod ig/init-key :notification/event-store [_ _]
  (println "Starting event store...")
  (event-store/create-store))

(defmethod ig/init-key :notification/notification-store [_ _]
  (println "Starting notification store...")
  (notif-store/create-store))

(defmethod ig/init-key :notification/sender [_ {:keys [config]}]
  (println "Starting notification sender...")
  (notif-sender/create-sender config))

(defmethod ig/init-key :notification/event-service [_ {:keys [store bus]}]
  (println "Starting event service...")
  (event-service/create-service store bus))

(defmethod ig/init-key :notification/notification-service [_ {:keys [store sender config]}]
  (println "Starting notification service...")
  (notif-service/create-service store sender config))

(defmethod ig/init-key :notification/handlers [_ {:keys [bus notification-service]}]
  (println "Registering event handlers...")
  (order-handler/register-handlers bus notification-service)
  (payment-handler/register-handlers bus notification-service)
  (shipment-handler/register-handlers bus notification-service)
  {:registered [:order :payment :shipment]})

(defmethod ig/init-key :notification/http-server [_ {:keys [port event-service notification-service]}]
  (println (str "Starting HTTP server on port " port "..."))
  (let [routes (concat
                (event-http/routes event-service)
                (notif-http/routes notification-service)
                [["/health" {:get {:handler (fn [_]
                                              (-> (response/response 
                                                   (json/generate-string {:status "ok"}))
                                                  (response/content-type "application/json")))}}]])
        router (ring/router routes)
        handler (-> (ring/ring-handler router)
                    wrap-keyword-params
                    wrap-params)
        server (jetty/run-jetty handler {:port port :join? false})]
    (println (str "Server running at http://localhost:" port))
    server))

;; =============================================================================
;; Integrant Halt Methods
;; =============================================================================

(defmethod ig/halt-key! :notification/bus [_ bus]
  (println "Stopping message bus...")
  (bus/stop! bus))

(defmethod ig/halt-key! :notification/http-server [_ server]
  (println "Stopping HTTP server...")
  (.stop server))

(defmethod ig/halt-key! :notification/handlers [_ _]
  (println "Unregistering event handlers..."))

(defmethod ig/halt-key! :notification/event-store [_ _]
  (println "Stopping event store..."))

(defmethod ig/halt-key! :notification/notification-store [_ _]
  (println "Stopping notification store..."))

(defmethod ig/halt-key! :notification/sender [_ _]
  (println "Stopping notification sender..."))

(defmethod ig/halt-key! :notification/event-service [_ _]
  (println "Stopping event service..."))

(defmethod ig/halt-key! :notification/notification-service [_ _]
  (println "Stopping notification service..."))

;; =============================================================================
;; System Lifecycle
;; =============================================================================

(defn start-system
  "Start the system with the given configuration."
  [env-config]
  (ig/init (config env-config)))

(defn stop-system
  "Stop the system."
  [system]
  (ig/halt! system))
