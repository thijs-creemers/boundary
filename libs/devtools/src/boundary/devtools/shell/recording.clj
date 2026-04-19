(ns boundary.devtools.shell.recording
  "Stateful recording management: session atom, capture middleware, file I/O."
  (:require [boundary.devtools.core.recording :as core]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io InputStream]))

(def ^:private default-dir ".boundary/recordings")

(defonce ^:private session-atom (atom nil))

;; Stores the handler that was live before recording started, so we can
;; restore it on stop.  Managed exclusively by user.clj's recording helper.
(defonce ^:private pre-recording-handler (atom nil))

(defn active-session [] @session-atom)
(defn reset-session! []
  (reset! session-atom nil)
  (reset! pre-recording-handler nil))

(defn store-pre-recording-handler!
  "Save the current live handler so it can be restored when recording stops."
  [handler]
  (reset! pre-recording-handler handler))

(defn restore-pre-recording-handler!
  "Return and clear the stored pre-recording handler."
  []
  (let [h @pre-recording-handler]
    (reset! pre-recording-handler nil)
    h))

(defn start-recording! []
  (reset! session-atom (core/create-session))
  (println "Recording started. Requests will be captured.")
  nil)

(defn stop-recording! []
  (when @session-atom
    (swap! session-atom core/stop-session)
    (let [cnt (core/entry-count @session-atom)]
      (println (format "Recording stopped. %d request(s) captured." cnt))))
  nil)

(defn- read-body
  "Read a request/response body into a serializable form.
   InputStreams are slurped into strings; everything else passes through."
  [body]
  (cond
    (instance? InputStream body) (slurp body)
    :else body))

(defn capture-middleware
  "Returns a Ring middleware that captures request/response pairs into the session atom.
   Normalizes :request-method to :method for consistent data model.
   InputStream bodies are materialized to strings so they survive serialization."
  []
  (fn [handler]
    (fn [request]
      ;; Materialize the body before passing to handler, since InputStreams
      ;; can only be read once.  Replace the original body with a
      ;; ByteArrayInputStream so downstream handlers still work.
      (let [raw-body    (:body request)
            body-str    (read-body raw-body)
            request     (if (instance? InputStream raw-body)
                          (assoc request :body (java.io.ByteArrayInputStream.
                                                (.getBytes (str body-str) "UTF-8")))
                          request)
            start       (System/nanoTime)
            response    (handler request)
            duration    (/ (- (System/nanoTime) start) 1e6)]
        (when @session-atom
          (let [req-data (-> (select-keys request [:uri :headers :params])
                             (assoc :method (:request-method request))
                             (assoc :body body-str))]
            (swap! session-atom core/add-entry
                   req-data
                   (-> (select-keys response [:status :headers])
                       (assoc :body (read-body (:body response))))
                   (long duration))))
        response))))

(defn replay-entry!
  "Replay a recorded entry. simulate-fn should be the repl/simulate-request function."
  [idx simulate-fn & [overrides]]
  (if-let [session @session-atom]
    (if-let [entry (core/get-entry session idx)]
      (let [request (if overrides
                      (core/merge-request-modifications (:request entry) overrides)
                      (:request entry))]
        (simulate-fn (:method request) (:uri request) {:body (:body request)}))
      (println (format "Entry %d not found. Session has %d entries (0 to %d)."
                       idx (core/entry-count session) (dec (core/entry-count session)))))
    (println "No active recording session. Use (recording :start) or (recording :load \"name\").")))

(defn save-session!
  ([name] (save-session! name default-dir))
  ([name dir]
   (if-let [session @session-atom]
     (let [file (io/file dir (str name ".edn"))]
       (io/make-parents file)
       (spit file (core/serialize-session session))
       (println (format "Recording saved to %s" (.getPath file))))
     (println "No active recording session."))))

(defn load-session!
  ([name] (load-session! name default-dir))
  ([name dir]
   (let [file (io/file dir (str name ".edn"))]
     (if (.exists file)
       (do
         (reset! session-atom (core/deserialize-session (slurp file)))
         (println (format "Loaded recording '%s' (%d entries)."
                          name (core/entry-count @session-atom)))
         nil)
       (let [available (when (.exists (io/file dir))
                         (->> (.listFiles (io/file dir))
                              (filter #(.endsWith (.getName %) ".edn"))
                              (map #(subs (.getName %) 0 (- (count (.getName %)) 4)))))]
         (if (seq available)
           (println (format "Recording '%s' not found. Available: %s"
                            name (str/join ", " available)))
           (println (format "Recording '%s' not found. No saved recordings in %s."
                            name dir)))
         {:error :not-found})))))

(defn list-entries []
  (if-let [session @session-atom]
    (println (core/format-entry-table session))
    (println "No active recording session.")))

(defn diff-entries [idx-a idx-b]
  (if-let [session @session-atom]
    (if-let [diff (core/diff-entries session idx-a idx-b)]
      diff
      (println "One or both entry indices are out of bounds."))
    (println "No active recording session.")))
