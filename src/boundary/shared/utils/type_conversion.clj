(ns boundary.shared.utils.type-conversion
  "Generic type and case conversion utilities."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]
           [java.time Instant]))

(defn uuid->string
  "Convert UUID to string for storage (nil-safe)."
  [uuid]
  (when uuid (.toString uuid)))

(defn string->uuid
  "Convert string to UUID from storage (nil-safe)."
  [s]
  (when (and s (not= s ""))
    (try
      (UUID/fromString s)
      (catch IllegalArgumentException e
        (log/warn "Invalid UUID string" {:uuid-string s :error (.getMessage e)})
        nil))))

(defn instant->string
  "Convert Instant to ISO 8601 string for storage (nil-safe)."
  [instant]
  (when instant (.toString instant)))

(defn string->instant
  "Convert ISO 8601 string to Instant from storage (nil-safe)."
  [s]
  (when (and s (not= s ""))
    (try
      (Instant/parse s)
      (catch Exception e
        (log/warn "Invalid ISO 8601 timestamp string" {:timestamp-string s :error (.getMessage e)})
        nil))))

(defn keyword->string
  "Convert keyword to string for storage (nil-safe)."
  [kw]
  (when kw (name kw)))

(defn string->keyword
  "Convert string to keyword from storage (nil-safe)."
  [s]
  (when (and s (not= s "")) (keyword s)))

(defn boolean->int
  "Convert boolean to integer for storage."
  [b]
  (if b 1 0))

(defn int->boolean
  "Convert integer to boolean from storage."
  [i]
  (= i 1))

(defn snake-case->kebab-case
  "Convert snake_case keys to kebab-case keys in a map (nil-safe)."
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (let [new-key (-> k name (str/replace #"_" "-") keyword)]
                   (assoc acc new-key v)))
               {} m)))

(defn kebab-case->snake-case
  "Convert kebab-case keys to snake_case keys in a map (nil-safe)."
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (let [new-key (-> k name (str/replace #"-" "_") keyword)]
                   (assoc acc new-key v)))
               {} m)))

(defn string->boolean
  "Convert string values to booleans with support for various formats."
  [value]
  (cond
    (boolean? value) value
    (string? value) (case (.toLowerCase value)
                      "true" true
                      "false" false
                      "1" true
                      "0" false
                      "yes" true
                      "no" false
                      "on" true
                      "off" false
                      value)
    :else value))

(defn string->int
  "Convert string values to integers."
  [value]
  (cond
    (int? value) value
    (string? value) (try
                      (Integer/parseInt value)
                      (catch NumberFormatException _
                        value))
    :else value))

(defn string->enum
  "Converts string values to keywords for enums."
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

(defn generate-uuid
  "Generate a new random UUID."
  []
  (UUID/randomUUID))

(defn current-instant
  "Get current timestamp as Instant."
  []
  (Instant/now))
