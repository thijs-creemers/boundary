(ns boundary.platform.migrations.core.checksums
  "Pure functions for calculating and verifying migration file checksums.
   
   This namespace handles:
   - Checksum calculation (SHA-256)
   - Checksum verification
   - Checksum comparison
   
   All functions are pure - no I/O, no side effects."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

;; Checksum calculation

(defn calculate-checksum
  "Calculate SHA-256 checksum of content.
   
   Args:
     content - String content to hash
     
   Returns:
     Hex-encoded SHA-256 checksum string
     
   Pure: true
   
   Examples:
     (calculate-checksum \"SELECT * FROM users\")
     => \"a3b2c1d4e5f6...\" (64 characters)"
  [content]
  (when content
    (let [digest (MessageDigest/getInstance "SHA-256")
          hash-bytes (.digest digest (.getBytes content StandardCharsets/UTF_8))]
      (apply str (map #(format "%02x" %) hash-bytes)))))

(defn calculate-file-checksum
  "Calculate checksum for a migration file entry.
   
   Args:
     file-content - Migration file content map with :content key
     
   Returns:
     Hex-encoded SHA-256 checksum
     
   Pure: true"
  [file-content]
  (calculate-checksum (:content file-content)))

;; Checksum verification

(defn verify-checksum
  "Verify that content matches expected checksum.
   
   Args:
     content - String content to verify
     expected-checksum - Expected SHA-256 checksum
     
   Returns:
     Boolean indicating if checksums match
     
   Pure: true"
  [content expected-checksum]
  (when (and content expected-checksum)
    (= (calculate-checksum content) expected-checksum)))

(defn verify-migration-checksum
  "Verify migration file checksum against recorded checksum.
   
   Args:
     migration - Migration map with :content key
     recorded-checksum - Checksum from database
     
   Returns:
     Map with :valid? boolean and optional :expected/:actual keys
     
   Pure: true"
  [migration recorded-checksum]
  (let [actual (calculate-file-checksum migration)]
    (if (= actual recorded-checksum)
      {:valid? true
       :checksum actual}
      {:valid? false
       :expected recorded-checksum
       :actual actual
       :migration (:version migration)})))

;; Batch checksum operations

(defn calculate-checksums-batch
  "Calculate checksums for multiple migration files.
   
   Args:
     migrations - Collection of migration maps with :content key
     
   Returns:
     Map of version -> checksum
     
   Pure: true"
  [migrations]
  (into {}
        (map (fn [migration]
               [(:version migration)
                (calculate-file-checksum migration)])
             migrations)))

(defn verify-checksums-batch
  "Verify checksums for multiple migrations.
   
   Args:
     migrations - Collection of migrations with :content key
     recorded-checksums - Map of version -> recorded checksum
     
   Returns:
     Map with :valid? and :mismatches keys
     
   Pure: true"
  [migrations recorded-checksums]
  (let [results (for [migration migrations]
                  (let [version (:version migration)
                        recorded (get recorded-checksums version)]
                    (when recorded
                      (verify-migration-checksum migration recorded))))
        mismatches (filter #(and % (not (:valid? %))) results)]
    {:valid? (empty? mismatches)
     :mismatches (vec mismatches)
     :checked (count (filter some? results))
     :total (count migrations)}))

;; Checksum comparison

(defn checksums-differ?
  "Check if two checksums are different.
   
   Args:
     checksum1 - First checksum
     checksum2 - Second checksum
     
   Returns:
     Boolean indicating if checksums differ
     
   Pure: true"
  [checksum1 checksum2]
  (and checksum1 checksum2 (not= checksum1 checksum2)))

(defn find-changed-migrations
  "Find migrations whose content has changed (checksum mismatch).
   
   Args:
     migrations - Collection of current migration files with :content
     recorded-checksums - Map of version -> recorded checksum
     
   Returns:
     Vector of changed migration maps with :expected/:actual checksums
     
   Pure: true"
  [migrations recorded-checksums]
  (let [changed (for [migration migrations
                      :let [version (:version migration)
                            recorded (get recorded-checksums version)]
                      :when recorded
                      :let [actual (calculate-file-checksum migration)]
                      :when (checksums-differ? actual recorded)]
                  {:version version
                   :name (:name migration)
                   :module (:module migration)
                   :expected recorded
                   :actual actual
                   :path (:path migration)})]
    (vec changed)))

;; Content normalization

(defn normalize-content-for-checksum
  "Normalize content before checksum calculation.
   
   Normalization includes:
   - Trim trailing whitespace from each line
   - Ensure single trailing newline
   - Convert line endings to LF
   
   Args:
     content - Raw content string
     
   Returns:
     Normalized content string
     
   Pure: true"
  [content]
  (when content
    (let [lines (clojure.string/split-lines content)
          trimmed-lines (map clojure.string/trimr lines)
          normalized (clojure.string/join "\n" trimmed-lines)]
      (str normalized "\n"))))

(defn calculate-normalized-checksum
  "Calculate checksum with content normalization.
   
   Args:
     content - Raw content string
     
   Returns:
     Hex-encoded SHA-256 checksum of normalized content
     
   Pure: true"
  [content]
  (calculate-checksum (normalize-content-for-checksum content)))

;; Validation helpers

(defn valid-checksum-format?
  "Check if a string is a valid SHA-256 checksum format.
   
   Args:
     checksum - String to validate
     
   Returns:
     Boolean indicating if format is valid (64 hex characters)
     
   Pure: true"
  [checksum]
  (boolean
   (and checksum
        (string? checksum)
        (= 64 (count checksum))
        (re-matches #"^[0-9a-f]{64}$" checksum))))

(defn validate-checksum
  "Validate checksum format and provide detailed error.
   
   Args:
     checksum - Checksum string to validate
     
   Returns:
     Map with :valid? boolean and optional :error key
     
   Pure: true"
  [checksum]
  (cond
    (nil? checksum)
    {:valid? false :error "Checksum is nil"}
    
    (not (string? checksum))
    {:valid? false :error "Checksum must be a string"}
    
    (not= 64 (count checksum))
    {:valid? false 
     :error (format "Checksum must be 64 characters, got %d" (count checksum))}
    
    (not (re-matches #"^[0-9a-f]{64}$" checksum))
    {:valid? false :error "Checksum must be lowercase hexadecimal"}
    
    :else
    {:valid? true}))

;; Checksum metadata

(defn create-checksum-metadata
  "Create metadata map for a checksum calculation.
   
   Args:
     content - Content that was hashed
     opts - Optional map with :algorithm :normalized? keys
     
   Returns:
     Map with checksum and metadata
     
   Pure: true"
  [content opts]
  (let [algorithm (get opts :algorithm "SHA-256")
        normalized? (get opts :normalized? false)
        content-to-hash (if normalized?
                         (normalize-content-for-checksum content)
                         content)
        checksum (calculate-checksum content-to-hash)]
    {:checksum checksum
     :algorithm algorithm
     :normalized? normalized?
     :length (count content)
     :calculated-at (java.time.Instant/now)}))
