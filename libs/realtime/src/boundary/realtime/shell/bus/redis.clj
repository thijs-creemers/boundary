(ns boundary.realtime.shell.bus.redis
  "Redis-backed IMessageBus: routing envelopes travel as Nippy-frozen bytes over
   a binary Redis pub/sub channel. publish borrows a pooled connection; the
   subscriber owns one dedicated blocking connection on a daemon thread.

   Concurrency:
   - Singleton subscriber: start-subscriber! is idempotent (running? guard) so a
     node never holds two channel subscriptions (which would double-deliver).
   - Reconnect: the daemon loops with backoff, fully tearing down the old
     BinaryJedisPubSub before re-subscribing. Gap messages are missed
     (at-most-once, accepted)."
  (:require [boundary.realtime.ports :as ports]
            [clojure.tools.logging :as log]
            [taoensso.nippy :as nippy])
  (:import [redis.clients.jedis JedisPool JedisPoolConfig Jedis BinaryJedisPubSub]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.nio.charset StandardCharsets]))

(defn- ->bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- make-pool ^JedisPool [{:keys [host port]}]
  (JedisPool. (JedisPoolConfig.) ^String (or host "localhost") (int (or port 6379))))

(defrecord RedisMessageBus [^JedisPool pool channel-bytes state]
  ;; state: atom of {:running? bool :pubsub BinaryJedisPubSub :thread Thread
  ;;                 :sub-conn Jedis :delivery-fn fn}
  ports/IMessageBus

  (publish [_this envelope]
    (try
      (with-open [^Jedis j (.getResource pool)]
        (.publish j ^bytes channel-bytes ^bytes (nippy/freeze envelope)))
      (catch Exception e
        (log/error e "Redis bus publish failed")))
    nil)

  (start-subscriber! [_this delivery-fn]
    (when-not (:running? @state)
      (let [latch  (CountDownLatch. 1)
            pubsub (proxy [BinaryJedisPubSub] []
                     (onSubscribe [_chan _cnt]
                       (.countDown latch))
                     (onMessage [_chan ^bytes message]
                       (try
                         (delivery-fn (nippy/thaw message))
                         (catch Exception e
                           (log/error e "Redis bus delivery failed")))))
            thread (Thread.
                    ^Runnable
                    (fn []
                      (loop [backoff 100]
                        (when (:running? @state)
                          (let [conn (.getResource pool)]
                            (swap! state assoc :sub-conn conn)
                            (try
                              (.subscribe conn
                                          ^BinaryJedisPubSub pubsub
                                          ^"[[B" (into-array (Class/forName "[B") [channel-bytes]))
                              (catch Exception e
                                (when (:running? @state)
                                  (log/warn e "Redis subscriber dropped; reconnecting")))
                              (finally
                                (try (.close conn) (catch Exception _ nil))))
                            (when (:running? @state)
                              (Thread/sleep backoff)
                              (recur (min 5000 (* 2 backoff)))))))))]
        (swap! state assoc :running? true :pubsub pubsub :thread thread :delivery-fn delivery-fn)
        (.setDaemon thread true)
        (.start thread)
        (.await latch 5 TimeUnit/SECONDS)))
    nil)

  (stop-subscriber! [_this]
    (when (:running? @state)
      (swap! state assoc :running? false)
      (let [{:keys [^BinaryJedisPubSub pubsub ^Thread thread]} @state]
        (try
          (when (and pubsub (.isSubscribed pubsub))
            (.unsubscribe pubsub))
          (catch Exception e
            (log/warn e "Redis unsubscribe failed")))
        (when thread
          (try (.join thread 2000) (catch Exception _ nil)))))
    nil)

  java.io.Closeable
  (close [this]
    (ports/stop-subscriber! this)
    (try (.close pool) (catch Exception _ nil))))

(defn create-redis-bus
  "Create a Redis message bus.
   Config: {:host :port :channel}. :channel defaults to \"boundary:realtime:bus\"."
  [{:keys [channel] :as config}]
  (->RedisMessageBus (make-pool config)
                     (->bytes (or channel "boundary:realtime:bus"))
                     (atom {:running? false})))
