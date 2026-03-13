(ns boundary.calendar.shell.adapters.ical-test
  "Integration tests for the ical4j adapter — round-trip export/import."
  (:require [boundary.calendar.shell.service :as service]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str])
  (:import [java.time Instant]))

;; =============================================================================
;; Test data
;; =============================================================================

(def ^:private appointment
  {:id       (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")
   :title    "Team Standup"
   :start    (Instant/parse "2026-03-10T09:00:00Z")
   :end      (Instant/parse "2026-03-10T09:30:00Z")
   :timezone "Europe/Amsterdam"})

(def ^:private recurring-meeting
  {:id         (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440001")
   :title      "Weekly Sync"
   :start      (Instant/parse "2026-03-02T08:00:00Z")
   :end        (Instant/parse "2026-03-02T08:30:00Z")
   :timezone   "Europe/Amsterdam"
   :recurrence "FREQ=WEEKLY;BYDAY=MO,WE,FR"})

;; =============================================================================
;; export-ical
;; =============================================================================

(deftest export-ical-basic-test
  ^:integration
  (testing "exported string starts with VCALENDAR"
    (let [ical (service/export-ical [appointment])]
      (is (str/includes? ical "BEGIN:VCALENDAR"))
      (is (str/includes? ical "END:VCALENDAR"))))
  (testing "exported string contains VEVENT"
    (let [ical (service/export-ical [appointment])]
      (is (str/includes? ical "BEGIN:VEVENT"))
      (is (str/includes? ical "END:VEVENT"))))
  (testing "exported string contains event title"
    (let [ical (service/export-ical [appointment])]
      (is (str/includes? ical "Team Standup"))))
  (testing "exported string contains UID"
    (let [ical (service/export-ical [appointment])]
      (is (str/includes? ical "550e8400-e29b-41d4-a716-446655440000"))))
  (testing "exported string contains VERSION:2.0"
    (let [ical (service/export-ical [appointment])]
      (is (str/includes? ical "VERSION:2.0")))))

(deftest export-ical-rrule-test
  ^:integration
  (testing "recurring event RRULE is included in export"
    (let [ical (service/export-ical [recurring-meeting])]
      (is (str/includes? ical "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR")))))

(deftest export-ical-multi-event-test
  ^:integration
  (testing "multi-event feed contains both VEVENTs"
    (let [ical (service/export-ical [appointment recurring-meeting])]
      (is (= 2 (count (re-seq #"BEGIN:VEVENT" ical)))))))

(deftest export-ical-custom-prodid-test
  ^:integration
  (testing "custom :product-id is used"
    (let [ical (service/export-ical [appointment] {:product-id "-//MyApp//EN"})]
      (is (str/includes? ical "-//MyApp//EN")))))

;; =============================================================================
;; import-ical
;; =============================================================================

(deftest import-ical-basic-test
  ^:integration
  (testing "imported events have correct field count"
    (let [ical   (service/export-ical [appointment])
          events (service/import-ical ical)]
      (is (= 1 (count events)))))
  (testing "imported event has correct title"
    (let [ical   (service/export-ical [appointment])
          event  (first (service/import-ical ical))]
      (is (= "Team Standup" (:title event)))))
  (testing "imported event has correct timezone"
    (let [ical   (service/export-ical [appointment])
          event  (first (service/import-ical ical))]
      (is (= "Europe/Amsterdam" (:timezone event))))))

;; =============================================================================
;; Round-trip
;; =============================================================================

(deftest round-trip-non-recurring-test
  ^:integration
  (testing "non-recurring event round-trips with same title and UID"
    (let [ical       (service/export-ical [appointment])
          events     (service/import-ical ical)
          ev         (first events)]
      (is (= "Team Standup" (:title ev)))
      (is (= (java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")
             (:id ev))))))

(deftest round-trip-rrule-test
  ^:integration
  (testing "RRULE survives round-trip"
    (let [ical   (service/export-ical [recurring-meeting])
          events (service/import-ical ical)
          ev     (first events)]
      (is (= "FREQ=WEEKLY;BYDAY=MO,WE,FR" (:recurrence ev))))))

(deftest round-trip-multi-event-test
  ^:integration
  (testing "multi-event round-trip preserves event count"
    (let [ical   (service/export-ical [appointment recurring-meeting])
          events (service/import-ical ical)]
      (is (= 2 (count events))))))

;; =============================================================================
;; ical-feed-response
;; =============================================================================

(deftest ical-feed-response-test
  ^:integration
  (testing "returns status 200"
    (let [resp (service/ical-feed-response [appointment])]
      (is (= 200 (:status resp)))))
  (testing "Content-Type is text/calendar"
    (let [resp (service/ical-feed-response [appointment])]
      (is (= "text/calendar; charset=utf-8"
             (get-in resp [:headers "Content-Type"])))))
  (testing "Content-Disposition includes filename"
    (let [resp (service/ical-feed-response [appointment])]
      (is (str/includes? (get-in resp [:headers "Content-Disposition"])
                         "calendar.ics"))))
  (testing "body is a VCALENDAR string"
    (let [resp (service/ical-feed-response [appointment])]
      (is (str/includes? (:body resp) "BEGIN:VCALENDAR"))))
  (testing "custom filename via opts"
    (let [resp (service/ical-feed-response [appointment] {:filename "my-feed.ics"})]
      (is (str/includes? (get-in resp [:headers "Content-Disposition"])
                         "my-feed.ics")))))
