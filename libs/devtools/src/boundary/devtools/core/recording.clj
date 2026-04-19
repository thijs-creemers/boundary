(ns boundary.devtools.core.recording
  "Pure functions for recording session data structures.
   No I/O, no atoms — just data transformations."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

(defn create-session [] {:entries [] :started-at (java.util.Date.) :stopped-at nil})

(defn add-entry [session request response duration-ms]
  (let [idx (count (:entries session))]
    (update session :entries conj
            {:idx idx :request request :response response
             :duration-ms duration-ms :timestamp (java.util.Date.)})))

(defn get-entry [session idx] (get (:entries session) idx))

(defn stop-session [session] (assoc session :stopped-at (java.util.Date.)))

(defn entry-count [session] (count (:entries session)))

(defn merge-request-modifications [request overrides]
  (update request :body merge overrides))

(defn- map-diff [a b]
  (let [all-keys (set (concat (keys a) (keys b)))]
    (reduce
     (fn [acc k]
       (let [va (get a k) vb (get b k)]
         (cond
           (and (nil? va) (some? vb)) (conj acc [:added k vb])
           (and (some? va) (nil? vb)) (conj acc [:removed k va])
           (not= va vb) (conj acc [:changed k va vb])
           :else acc)))
     [] (sort all-keys))))

(defn diff-entries [session idx-a idx-b]
  (let [a (get-entry session idx-a) b (get-entry session idx-b)]
    (when (and a b)
      {:request-diff (map-diff (:request a) (:request b))
       :response-diff (map-diff (:response a) (:response b))})))

(defn format-entry-table [session]
  (let [entries (:entries session)
        header (format "%-5s %-7s %-30s %-8s %-10s" "IDX" "METHOD" "PATH" "STATUS" "DURATION")
        sep (apply str (repeat (count header) "─"))
        rows (map (fn [{:keys [idx request response duration-ms]}]
                    (format "%-5d %-7s %-30s %-8d %-10s"
                            idx (str/upper-case (name (:method request)))
                            (:uri request) (:status response) (str duration-ms "ms")))
                  entries)]
    (str/join "\n" (concat [header sep] rows))))

(defn serialize-session [session] (pr-str session))
(defn deserialize-session [s] (edn/read-string s))
