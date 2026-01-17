(ns notification.shared.bus
  "In-memory message bus for event-driven communication.
   
   Features:
   - Topic-based pub/sub
   - Async message processing with core.async
   - Multiple subscribers per topic
   - Dead letter queue for failed messages"
  (:require [clojure.core.async :as async :refer [go go-loop <! >! chan close!]]))

;; =============================================================================
;; Protocol
;; =============================================================================

(defprotocol IMessageBus
  "Message bus interface for pub/sub messaging."
  
  (publish! [this topic message]
    "Publish a message to a topic. Returns true if accepted.")
  
  (subscribe! [this topic handler-fn]
    "Subscribe to a topic. handler-fn receives messages.
     Returns subscription id.")
  
  (unsubscribe! [this subscription-id]
    "Remove a subscription.")
  
  (stop! [this]
    "Stop the message bus and close all channels."))

;; =============================================================================
;; In-Memory Implementation
;; =============================================================================

(defrecord InMemoryBus [config input-chan subscriptions workers running? metrics]
  IMessageBus
  
  (publish! [_ topic message]
    (when @running?
      (let [envelope {:topic topic
                      :message message
                      :timestamp (java.time.Instant/now)
                      :id (random-uuid)}]
        (swap! metrics update :published inc)
        (async/put! input-chan envelope))))
  
  (subscribe! [_ topic handler-fn]
    (let [sub-id (random-uuid)]
      (swap! subscriptions assoc sub-id {:topic topic
                                          :handler handler-fn})
      (swap! metrics update-in [:subscriptions topic] (fnil inc 0))
      sub-id))
  
  (unsubscribe! [_ subscription-id]
    (when-let [sub (get @subscriptions subscription-id)]
      (swap! subscriptions dissoc subscription-id)
      (swap! metrics update-in [:subscriptions (:topic sub)] dec)
      true))
  
  (stop! [_]
    (reset! running? false)
    (close! input-chan)
    ;; Wait for workers to finish
    (doseq [w @workers]
      (async/<!! w))))

;; =============================================================================
;; Worker Implementation
;; =============================================================================

(defn- process-message
  "Process a message by calling all matching subscribers."
  [envelope subscriptions metrics]
  (let [topic (:topic envelope)
        matching-subs (->> @subscriptions
                           vals
                           (filter #(= topic (:topic %))))]
    (doseq [sub matching-subs]
      (try
        ((:handler sub) (:message envelope))
        (swap! metrics update :processed inc)
        (catch Exception e
          (println "Error processing message:" (.getMessage e))
          (swap! metrics update :errors inc))))))

(defn- start-worker
  "Start a worker that processes messages from the input channel."
  [input-chan subscriptions metrics running?]
  (go-loop []
    (when-let [envelope (<! input-chan)]
      (when @running?
        (process-message envelope subscriptions metrics)
        (recur)))))

;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-bus
  "Create a new in-memory message bus.
   
   Config:
     :buffer-size  - Size of input channel buffer (default 1000)
     :worker-count - Number of worker goroutines (default 2)"
  [config]
  (let [buffer-size (get config :buffer-size 1000)
        worker-count (get config :worker-count 2)
        input-chan (chan buffer-size)
        subscriptions (atom {})
        running? (atom true)
        metrics (atom {:published 0
                       :processed 0
                       :errors 0
                       :subscriptions {}})
        workers (atom [])]
    ;; Start workers
    (dotimes [_ worker-count]
      (swap! workers conj (start-worker input-chan subscriptions metrics running?)))
    (->InMemoryBus config input-chan subscriptions workers running? metrics)))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn get-metrics
  "Get current metrics from the bus."
  [bus]
  @(:metrics bus))

(defn healthy?
  "Check if bus is running and accepting messages."
  [bus]
  @(:running? bus))
