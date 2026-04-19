(ns boundary.devtools.shell.dashboard.middleware
  (:require [clojure.string :as str])
  (:import [java.time Instant]))

(def ^:private max-entries 200)

(defonce ^:private request-log* (atom []))

(def ^:private sensitive-headers
  #{"authorization" "cookie" "x-api-key" "x-auth-token"})

(defn request-log
  "Return captured requests, newest first."
  []
  @request-log*)

(defn clear-request-log! []
  (reset! request-log* []))

(defn- sanitize-headers [headers]
  (reduce-kv (fn [m k v]
               (assoc m k (if (contains? sensitive-headers (str/lower-case (str k)))
                            "[REDACTED]"
                            v)))
             {} headers))

(defn- truncate-body [body]
  (when body
    (let [s (str body)]
      (if (> (count s) 2000)
        (subs s 0 2000)
        s))))

(defn- log-entry! [request response duration]
  (swap! request-log*
         (fn [log]
           (let [entry {:id          (random-uuid)
                        :timestamp   (Instant/now)
                        :method      (:request-method request)
                        :path        (:uri request)
                        :status      (or (:status response) 500)
                        :duration-ms (Math/round ^double duration)
                        :request     {:headers     (sanitize-headers (:headers request))
                                      :params      (:params request)
                                      :body-params (:body-params request)}
                        :response    {:status  (or (:status response) 500)
                                      :headers (:headers response)
                                      :body    (truncate-body (:body response))}}
                 new-log (into [entry] log)]
             (if (> (count new-log) max-entries)
               (subvec new-log 0 max-entries)
               new-log)))))

(defn wrap-request-capture
  "Ring middleware that captures request/response pairs into a bounded log.
   Records both successful responses and exceptions (as 500)."
  [handler]
  (fn [request]
    (let [start-ns (System/nanoTime)]
      (try
        (let [response (handler request)
              duration (/ (- (System/nanoTime) start-ns) 1e6)]
          (log-entry! request response duration)
          response)
        (catch Throwable t
          (let [duration (/ (- (System/nanoTime) start-ns) 1e6)]
            (log-entry! request {:status 500 :body (.getMessage t)} duration))
          (throw t))))))
