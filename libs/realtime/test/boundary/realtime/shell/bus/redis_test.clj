(ns boundary.realtime.shell.bus.redis-test
  {:kaocha.testable/meta {:integration true :redis true :realtime true}}
  (:require [clojure.test :refer [deftest testing is]]
            [boundary.realtime.ports :as ports]
            [boundary.realtime.shell.bus.redis :as rbus])
  (:import [redis.clients.jedis JedisPool Jedis]))

(defn redis-available? []
  (try
    (let [pool (JedisPool. "localhost" 6379)]
      (with-open [^Jedis j (.getResource pool)] (= "PONG" (.ping j)))
      (.close pool) true)
    (catch Exception _ false)))

(defn- await-count [a n ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (>= (count @a) n) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(deftest cross-bus-delivery-test
  (when (redis-available?)
    (let [chan "rt-test:bus"
          received (atom [])
          pub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})
          sub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})]
      (try
        (ports/start-subscriber! sub (fn [env] (swap! received conj env) 1))
        (testing "envelope published on one bus reaches a subscriber on another"
          (ports/publish pub {:route :broadcast :target nil
                              :message {:type :x :payload {:n 1}}})
          (is (await-count received 1 2000))
          (is (= :broadcast (:route (first @received))))
          (is (= {:type :x :payload {:n 1}} (:message (first @received)))))
        (finally
          (ports/stop-subscriber! sub)
          (ports/stop-subscriber! pub))))))

(deftest idempotent-subscriber-test
  (when (redis-available?)
    (let [chan "rt-test:bus2"
          received (atom [])
          sub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})
          pub (rbus/create-redis-bus {:host "localhost" :port 6379 :channel chan})]
      (try
        (ports/start-subscriber! sub (fn [_] (swap! received conj :got) 1))
        (ports/start-subscriber! sub (fn [_] (swap! received conj :got) 1)) ; no-op
        (ports/publish pub {:route :broadcast :message {:type :y}})
        (Thread/sleep 500)
        (testing "second start-subscriber! did not create a second subscription"
          (is (= 1 (count @received))))
        (finally
          (ports/stop-subscriber! sub)
          (ports/stop-subscriber! pub))))))
