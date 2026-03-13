(ns boundary.calendar.core.event-test
  "Unit tests for boundary.calendar.core.event — pure functions, no I/O."
  (:require [boundary.calendar.core.event :as sut]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.time Instant]))

;; =============================================================================
;; Fixture — clear registry between tests
;; =============================================================================

(use-fixtures :each
  (fn [f]
    (sut/clear-registry!)
    (f)
    (sut/clear-registry!)))

;; =============================================================================
;; Test data
;; =============================================================================

(def ^:private base-event
  {:id       (java.util.UUID/randomUUID)
   :title    "Team Meeting"
   :start    (Instant/parse "2026-03-10T09:00:00Z")
   :end      (Instant/parse "2026-03-10T10:00:00Z")
   :timezone "Europe/Amsterdam"})

;; =============================================================================
;; defevent macro and registry
;; =============================================================================

(sut/defevent test-appointment-event
  {:id    :test-appointment
   :label "Test Appointment"})

(deftest defevent-macro-test
  ^:unit
  (testing "defevent binds var to definition map"
    (is (= :test-appointment (:id test-appointment-event)))
    (is (= "Test Appointment" (:label test-appointment-event))))
  (testing "defevent registers in the registry"
    (sut/register-event-type! test-appointment-event)
    (is (= test-appointment-event (sut/get-event-type :test-appointment))))
  (testing "list-event-types includes registered id"
    (sut/register-event-type! test-appointment-event)
    (is (some #{:test-appointment} (sut/list-event-types)))))

(deftest register-event-type-test
  ^:unit
  (testing "programmatic registration"
    (let [defn {:id :booking :label "Booking"}]
      (sut/register-event-type! defn)
      (is (= defn (sut/get-event-type :booking)))))
  (testing "get-event-type returns nil for unknown id"
    (is (nil? (sut/get-event-type :unknown-event-xyz)))))

(deftest clear-registry-test
  ^:unit
  (testing "clear-registry! empties the registry"
    (sut/register-event-type! {:id :temp-event})
    (sut/clear-registry!)
    (is (empty? (sut/list-event-types)))))

;; =============================================================================
;; duration
;; =============================================================================

(deftest duration-test
  ^:unit
  (testing "1-hour event returns 60-minute duration"
    (let [dur (sut/duration base-event)]
      (is (= 60 (.toMinutes dur)))))
  (testing "30-minute event"
    (let [event (assoc base-event
                       :end (Instant/parse "2026-03-10T09:30:00Z"))
          dur   (sut/duration event)]
      (is (= 30 (.toMinutes dur))))))

;; =============================================================================
;; all-day?
;; =============================================================================

(deftest all-day-test
  ^:unit
  (testing "24-hour event starting at midnight UTC is all-day"
    (let [event (assoc base-event
                       :start (Instant/parse "2026-03-10T00:00:00Z")
                       :end   (Instant/parse "2026-03-11T00:00:00Z"))]
      (is (true? (sut/all-day? event)))))
  (testing "48-hour event starting at midnight UTC is all-day"
    (let [event (assoc base-event
                       :start (Instant/parse "2026-03-10T00:00:00Z")
                       :end   (Instant/parse "2026-03-12T00:00:00Z"))]
      (is (true? (sut/all-day? event)))))
  (testing "1-hour event is not all-day"
    (is (false? (sut/all-day? base-event))))
  (testing "24-hour event NOT starting at midnight is not all-day"
    (let [event (assoc base-event
                       :start (Instant/parse "2026-03-10T01:00:00Z")
                       :end   (Instant/parse "2026-03-11T01:00:00Z"))]
      (is (false? (sut/all-day? event))))))

;; =============================================================================
;; within-range?
;; =============================================================================

(deftest within-range-test
  ^:unit
  (let [range-start (Instant/parse "2026-03-10T08:00:00Z")
        range-end   (Instant/parse "2026-03-10T18:00:00Z")]
    (testing "event fully inside range"
      (is (true? (sut/within-range? base-event range-start range-end))))
    (testing "event starting before range but overlapping"
      (let [event (assoc base-event
                         :start (Instant/parse "2026-03-10T07:30:00Z")
                         :end   (Instant/parse "2026-03-10T09:30:00Z"))]
        (is (true? (sut/within-range? event range-start range-end)))))
    (testing "event entirely before range"
      (let [event (assoc base-event
                         :start (Instant/parse "2026-03-10T06:00:00Z")
                         :end   (Instant/parse "2026-03-10T07:00:00Z"))]
        (is (false? (sut/within-range? event range-start range-end)))))
    (testing "event entirely after range"
      (let [event (assoc base-event
                         :start (Instant/parse "2026-03-10T19:00:00Z")
                         :end   (Instant/parse "2026-03-10T20:00:00Z"))]
        (is (false? (sut/within-range? event range-start range-end)))))
    (testing "event ending exactly at range-start does not overlap"
      (let [event (assoc base-event
                         :start (Instant/parse "2026-03-10T07:00:00Z")
                         :end   range-start)]
        (is (false? (sut/within-range? event range-start range-end)))))))

;; =============================================================================
;; valid-event?
;; =============================================================================

(deftest valid-event-test
  ^:unit
  (testing "valid event passes schema"
    (is (true? (sut/valid-event? base-event))))
  (testing "missing required key fails"
    (is (false? (sut/valid-event? (dissoc base-event :title)))))
  (testing "valid event with optional recurrence"
    (let [event (assoc base-event :recurrence "FREQ=WEEKLY;BYDAY=MO,WE,FR")]
      (is (true? (sut/valid-event? event)))))
  (testing "invalid timezone fails validation"
    (let [event (assoc base-event :timezone "Not/A/Real/Timezone")]
      (is (false? (sut/valid-event? event)))))
  (testing "valid IANA timezone passes validation"
    (let [event (assoc base-event :timezone "America/New_York")]
      (is (true? (sut/valid-event? event))))))
