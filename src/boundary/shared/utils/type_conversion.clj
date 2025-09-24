(ns boundary.shared.utils.type-conversion
  "Generic type and case conversion utilities used across the Boundary application.
   
   This namespace provides reusable type conversion and case transformation
   utilities that were previously embedded in specific adapters. These functions
   are designed to be nil-safe and handle common data transformations needed
   when working with different storage backends and API formats.
   
   Key Features:
   - UUID <-> String conversions
   - Java Instant <-> ISO-8601 String conversions  
   - Keyword <-> String conversions
   - Boolean <-> Integer conversions (for SQLite compatibility)
   - Case conversions: kebab-case <-> snake_case for maps
   - UUID and timestamp generation utilities
   
   Usage:
   (:require [boundary.shared.utils.type-conversion :as type-conversion])
   
   (type-conversion/uuid->string some-uuid)
   (type-conversion/kebab-case->snake-case {:user-id \"123\"})"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; UUID Conversions
;; =============================================================================

(defn uuid->string
  "Convert UUID to string for storage (nil-safe).

   Args:
     uuid: java.util.UUID instance or nil

   Returns:
     String representation or nil

   Example:
     (uuid->string #uuid \"123e4567-e89b-12d3-a456-426614174000\")
     ;; => \"123e4567-e89b-12d3-a456-426614174000\""
  [uuid]
  (when uuid (.toString uuid)))

(defn string->uuid
  "Convert string to UUID from storage (nil-safe).

   Args:
     s: String UUID representation or nil/empty

   Returns:
     java.util.UUID instance or nil
     Returns nil and logs warning for invalid UUID strings

   Example:
     (string->uuid \"123e4567-e89b-12d3-a456-426614174000\")
     ;; => #uuid \"123e4567-e89b-12d3-a456-426614174000\""
  [s]
  (when (and s (not= s ""))
    (try
      (UUID/fromString s)
      (catch IllegalArgumentException e
        (log/warn "Invalid UUID string" {:uuid-string s :error (.getMessage e)})
        nil))))

;; =============================================================================
;; Instant Conversions
;; =============================================================================

(defn instant->string
  "Convert Instant to ISO 8601 string for storage (nil-safe).

   Args:
     instant: java.time.Instant instance or nil

   Returns:
     ISO 8601 string representation or nil

   Example:
     (instant->string (Instant/now))
     ;; => \"2023-12-25T14:30:00.123Z\""
  [instant]
  (when instant (.toString instant)))

(defn string->instant
  "Convert ISO 8601 string to Instant from storage (nil-safe).

   Args:
     s: ISO 8601 string or nil/empty

   Returns:
     java.time.Instant instance or nil
     Returns nil and logs warning for invalid timestamp strings

   Example:
     (string->instant \"2023-12-25T14:30:00.123Z\")
     ;; => #inst \"2023-12-25T14:30:00.123Z\""
  [s]
  (when (and s (not= s ""))
    (try
      (Instant/parse s)
      (catch Exception e
        (log/warn "Invalid ISO 8601 timestamp string" {:timestamp-string s :error (.getMessage e)})
        nil))))

;; =============================================================================
;; Keyword Conversions
;; =============================================================================

(defn keyword->string
  "Convert keyword to string for storage (nil-safe).

   Args:
     kw: Keyword or nil

   Returns:
     String name or nil

   Example:
     (keyword->string :admin) ;; => \"admin\""
  [kw]
  (when kw (name kw)))

(defn string->keyword
  "Convert string to keyword from storage (nil-safe).

   Args:
     s: String or nil/empty

   Returns:
     Keyword or nil

   Example:
     (string->keyword \"admin\") ;; => :admin"
  [s]
  (when (and s (not= s "")) (keyword s)))

;; =============================================================================
;; Boolean Conversions (for SQLite compatibility)
;; =============================================================================

(defn boolean->int
  "Convert boolean to integer for storage systems without native boolean support.

   Args:
     b: Boolean value (may be nil)

   Returns:
     1 for true, 0 for false

   Example:
     (boolean->int true)  ;; => 1
     (boolean->int false) ;; => 0
     (boolean->int nil)   ;; => 0"
  [b]
  (if b 1 0))

(defn int->boolean
  "Convert integer to boolean from storage.

   Args:
     i: Integer value (should be 0 or 1, may be nil)

   Returns:
     Boolean value - 1 becomes true, anything else becomes false

   Example:
     (int->boolean 1) ;; => true
     (int->boolean 0) ;; => false
     (int->boolean nil) ;; => false"
  [i]
  (= i 1))

;; =============================================================================
;; Case Conversions for Maps
;; =============================================================================

(defn snake-case->kebab-case
  "Convert snake_case keys to kebab-case keys in a map (nil-safe).

   Args:
     m: Map with snake_case keyword keys, or nil

   Returns:
     Map with kebab-case keyword keys, or nil if input is nil

   Example:
     (snake-case->kebab-case {:user_id \"123\" :created_at \"2023-12-25\"})
     ;; => {:user-id \"123\" :created-at \"2023-12-25\"}"
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (let [new-key (-> k name (str/replace #"_" "-") keyword)]
                   (assoc acc new-key v)))
               {} m)))

(defn kebab-case->snake-case
  "Convert kebab-case keys to snake_case keys in a map (nil-safe).

   Args:
     m: Map with kebab-case keyword keys, or nil

   Returns:
     Map with snake_case keyword keys, or nil if input is nil

   Example:
     (kebab-case->snake-case {:user-id \"123\" :created-at \"2023-12-25\"})
     ;; => {:user_id \"123\" :created_at \"2023-12-25\"}"
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (let [new-key (-> k name (str/replace #"-" "_") keyword)]
                   (assoc acc new-key v)))
               {} m)))

;; =============================================================================
;; Generator Utilities
;; =============================================================================

(defn generate-uuid
  "Generate a new random UUID.

   Returns:
     java.util.UUID instance

   Example:
     (generate-uuid) ;; => #uuid \"123e4567-e89b-12d3-a456-426614174000\""
  []
  (UUID/randomUUID))

(defn current-instant
  "Get current timestamp as Instant.

   Returns:
     java.time.Instant representing current moment

   Example:
     (current-instant) ;; => #inst \"2023-12-25T14:30:00.123Z\""
  []
  (Instant/now))
