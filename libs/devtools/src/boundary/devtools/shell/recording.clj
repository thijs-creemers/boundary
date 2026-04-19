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

(defn- text-content-type?
  "Return true when the content-type header indicates a text-based body
   (JSON, EDN, XML, plain text, form data). Binary payloads (images,
   PDFs, octet-stream, multipart file uploads) return false so we don't
   corrupt them by slurping to a UTF-8 string."
  [headers]
  (let [ct (or (get headers "content-type")
               (get headers "Content-Type")
               "")]
    (boolean (re-find #"(?i)text/|json|edn|xml|urlencoded" ct))))

(defn- safe-read-body
  "Read a body into a serializable form only when it is text-based.
   Returns [serializable-body replacement-body].
   - For text InputStreams: slurps to string, builds a replacement BAIS.
   - For binary InputStreams: records :binary-stream placeholder, returns original.
   - For everything else: returns body unchanged."
  [body text?]
  (cond
    (not (instance? InputStream body))
    [body body]

    text?
    (let [s (slurp body)]
      [s (java.io.ByteArrayInputStream. (.getBytes s "UTF-8"))])

    :else
    [:binary-stream body]))

(defn capture-middleware
  "Returns a Ring middleware that captures request/response pairs into the session atom.
   Normalizes :request-method to :method for consistent data model.
   Only text-based bodies (JSON, EDN, form data) are materialized for recording.
   Binary bodies (file uploads, PDF downloads) pass through untouched."
  []
  (fn [handler]
    (fn [request]
      (let [req-text?            (text-content-type? (:headers request))
            [req-body-data req-body-replacement] (safe-read-body (:body request) req-text?)
            request              (assoc request :body req-body-replacement)
            start                (System/nanoTime)
            response             (handler request)
            duration             (/ (- (System/nanoTime) start) 1e6)
            resp-text?           (text-content-type? (:headers response))
            [resp-body-data resp-body-replacement] (safe-read-body (:body response) resp-text?)
            response             (assoc response :body resp-body-replacement)]
        (when @session-atom
          (let [req-data (-> (select-keys request [:uri :headers :params])
                             (assoc :method (:request-method request))
                             (assoc :body req-body-data))]
            (swap! session-atom core/add-entry
                   req-data
                   (-> (select-keys response [:status :headers])
                       (assoc :body resp-body-data))
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
        (simulate-fn (:method request) (:uri request)
                     (cond-> {}
                       (:body request)    (assoc :body (:body request))
                       (:headers request) (assoc :headers (:headers request))
                       (:params request)  (assoc :params (:params request)))))
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
