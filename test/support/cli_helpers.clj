(ns support.cli-helpers
  "Helper utilities for CLI testing.
   
   Provides:
   - Output capture (stdout/stderr)
   - Sample fixtures for users and sessions
   - Assertion helpers for table format"
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.io StringWriter]
           [java.time Instant]
           [java.util UUID]))

;; =============================================================================
;; Output Capture
;; =============================================================================

(defmacro with-out-str-and-err
  "Captures both stdout and stderr during body execution.
   
   Returns:
     {:out stdout-string :err stderr-string :result body-result}"
  [& body]
  `(let [out-writer# (StringWriter.)
         err-writer# (StringWriter.)
         result# (binding [*out* out-writer#
                           *err* err-writer#]
                   ~@body)]
     {:out (str out-writer#)
      :err (str err-writer#)
      :result result#}))

(defn capture-cli-output
  "Execute function and capture its output.
   
   Args:
     f: Function to execute
     
   Returns:
     {:out string :err string :status integer}"
  [f]
  (let [out-writer (StringWriter.)
        err-writer (StringWriter.)
        status (binding [*out* out-writer
                         *err* err-writer]
                 (f))]
    {:out (str out-writer)
     :err (str err-writer)
     :status status}))

;; =============================================================================
;; JSON Parsing
;; =============================================================================

(defn parse-json
  "Parse JSON string, returning nil on error."
  [s]
  (try
    (json/parse-string s true)
    (catch Exception _
      nil)))

(defn json-output?
  "Check if string is valid JSON."
  [s]
  (some? (parse-json s)))

;; =============================================================================
;; Sample Fixtures
;; =============================================================================

(defn sample-user
  "Create a sample user entity for testing.
   
   Args:
     overrides: Map of fields to override defaults
     
   Returns:
     User entity map"
  [& [overrides]]
  (merge {:id (UUID/randomUUID)
          :email "test@example.com"
          :name "Test User"
          :role :user
          :active true
          :created-at (Instant/now)
          :updated-at nil
          :deleted-at nil}
         overrides))

(defn sample-session
  "Create a sample session entity for testing.
   
   Args:
     overrides: Map of fields to override defaults
     
   Returns:
     Session entity map"
  [& [overrides]]
  (let [now (Instant/now)]
    (merge {:id (UUID/randomUUID)
            :user-id (UUID/randomUUID)
            :session-token "test-token-123"
            :created-at now
            :expires-at (.plusSeconds now 3600)
            :last-accessed-at nil
            :revoked-at nil
            :user-agent "Test User Agent"
            :ip-address "127.0.0.1"}
           overrides)))

;; =============================================================================
;; Table Assertions
;; =============================================================================

(defn table-has-header?
  "Check if table output has expected headers."
  [output headers]
  (let [lines (str/split-lines output)
        header-line (when (>= (count lines) 2) (nth lines 1))]
    (every? #(str/includes? header-line %) headers)))

(defn table-has-data?
  "Check if table output contains expected data values."
  [output values]
  (every? #(str/includes? output (str %)) values))

(defn table-row-count
  "Count number of data rows in table (excluding headers and separators)."
  [output]
  (let [lines (str/split-lines output)
        ;; Data rows are between separators, not including header
        data-lines (filter #(and (str/starts-with? % "| ")
                                 (not (str/starts-with? % "+-")))
                           lines)]
    (max 0 (- (count data-lines) 1)))) ; Subtract header row

(defn assert-table-contains
  "Assert that table output contains expected elements.
   
   Args:
     output: Table string output
     expected: Map with optional keys:
               :headers - Vector of expected header strings
               :values - Vector of expected data values
               :row-count - Expected number of data rows"
  [output expected]
  (when-let [headers (:headers expected)]
    (assert (table-has-header? output headers)
            (str "Table missing headers: " headers)))
  (when-let [values (:values expected)]
    (assert (table-has-data? output values)
            (str "Table missing values: " values)))
  (when-let [row-count (:row-count expected)]
    (assert (= row-count (table-row-count output))
            (str "Expected " row-count " rows, found " (table-row-count output)))))

;; =============================================================================
;; Error Assertions
;; =============================================================================

(defn error-contains?
  "Check if error output contains expected message."
  [output message]
  (str/includes? output message))

(defn error-type-matches?
  "Check if error JSON has expected type."
  [output expected-type]
  (when-let [parsed (parse-json output)]
    (= expected-type (get-in parsed [:error :type]))))

;; =============================================================================
;; Mock Service Builder
;; =============================================================================

(defn mock-service-fn
  "Create a mock service that records calls.
   
   Returns:
     {:service mock-service-instance
      :calls atom-of-call-records}
     
   Each call is recorded as:
     {:fn function-name :args arguments-vector}"
  []
  (let [calls (atom [])]
    {:service (reify
                Object
                (toString [_] "MockService"))
     :calls calls
     :record! (fn [fn-name & args]
                (swap! calls conj {:fn fn-name :args args}))}))

(defn called-with?
  "Check if mock was called with specific function and args.
   
   Args:
     calls: Calls atom from mock-service-fn
     fn-name: Keyword function name
     args-pred: Predicate function for arguments
     
   Returns:
     Boolean indicating if call was made"
  [calls fn-name args-pred]
  (some #(and (= fn-name (:fn %))
              (args-pred (:args %)))
        @calls))
