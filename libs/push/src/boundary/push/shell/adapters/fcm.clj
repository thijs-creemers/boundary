(ns boundary.push.shell.adapters.fcm
  (:require [boundary.push.ports :as ports]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [com.google.auth.oauth2 GoogleCredentials]
           [java.io FileInputStream]))

(defn- load-credentials [credentials-path]
  (-> (FileInputStream. ^String credentials-path)
      (GoogleCredentials/fromStream)
      (.createScoped ["https://www.googleapis.com/auth/firebase.messaging"])))

(defn- get-access-token [^GoogleCredentials credentials]
  (.refreshIfExpired credentials)
  (-> credentials .getAccessToken .getTokenValue))

(defn- fcm-url [project-id]
  (str "https://fcm.googleapis.com/v1/projects/" project-id "/messages:send"))

(defn- http-post [url body access-token]
  (let [client  (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " access-token))
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (json/generate-string body)
                            StandardCharsets/UTF_8))
                    (.timeout (Duration/ofSeconds 10))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body   (json/parse-string (.body response) true)}))

(defrecord FCMProvider [project-id credentials]
  ports/IFCMProvider

  (fcm-send! [_ payload]
    (let [token        (get-in payload [:message :token])
          access-token (get-access-token credentials)
          response     (http-post (fcm-url project-id) payload access-token)]
      (if (<= 200 (:status response) 299)
        {:success?     true
         :message-id   (get-in response [:body :name])
         :device-token token
         :platform     :fcm}
        (let [error-code (get-in response [:body :error :status])]
          (log/warnf "FCM send failed: %s %s" (:status response) error-code)
          {:success?        false
           :device-token    token
           :platform        :fcm
           :error           error-code
           :token-invalid?  (contains? #{"UNREGISTERED" "INVALID_ARGUMENT"} error-code)}))))

  (fcm-send-multicast! [this payload tokens]
    (mapv (fn [token]
            (ports/fcm-send! this (assoc-in payload [:message :token] token)))
          tokens))

  (fcm-validate-token [this token]
    (let [payload {:message {:token token :data {"validate_only" "true"}}}
          result  (ports/fcm-send! this payload)]
      {:valid? (:success? result) :token token})))

(defn make-fcm-provider [project-id credentials-path]
  (let [creds (load-credentials credentials-path)]
    (->FCMProvider project-id creds)))
