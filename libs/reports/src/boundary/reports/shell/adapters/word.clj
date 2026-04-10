(ns boundary.reports.shell.adapters.word
  "Word (DOCX) generation adapter using Apache POI XWPF.

   Apache POI is already on the classpath as a transitive dependency
   via docjure → poi-ooxml. No new Maven dependencies are needed.

   Supports the same declarative :sections API as the Excel adapter.
   The :template key is PDF-only and is ignored here.

   Usage:
     (def wd-gen (create-word-generator))
     (generate! wd-gen my-report-def data {})
     ;; => {:bytes #bytes[...] :type :word :filename \"my-report.docx\"}"
  (:require [boundary.reports.core.report :as core]
            [boundary.reports.ports :as ports]
            [clojure.tools.logging :as log])
  (:import [java.io ByteArrayOutputStream]
           [org.apache.poi.xwpf.usermodel XWPFDocument]))

;; =============================================================================
;; Internal helpers
;; =============================================================================

(defn- add-heading! [^XWPFDocument doc text]
  (let [p (.createParagraph doc)
        r (.createRun p)]
    (.setStyle p "Heading1")
    (.setText r (str text))
    p))

(defn- add-paragraph! [^XWPFDocument doc text]
  (let [p (.createParagraph doc)
        r (.createRun p)]
    (.setText r (str text))
    p))

(defn- add-footer-para! [^XWPFDocument doc text]
  (let [p (.createParagraph doc)
        r (.createRun p)]
    (.setItalic r true)
    (.setFontSize r 8)
    (.setText r (str text))
    p))

(defn- add-table! [^XWPFDocument doc columns data formatting-context]
  (let [n-cols (count columns)
        n-rows (inc (count data))
        table  (.createTable doc n-rows n-cols)]
    ;; Header row
    (doseq [[col-idx {:keys [label]}] (map-indexed vector columns)]
      (-> (.getRow table 0)
          (.getCell col-idx)
          (.setText (str label))))
    ;; Data rows
    (doseq [[row-idx record] (map-indexed vector data)]
      (doseq [[col-idx value] (map-indexed vector (core/map-columns* columns record formatting-context))]
        (-> (.getRow table (inc row-idx))
            (.getCell col-idx)
            (.setText (str value)))))
    table))

(defn- render-section! [^XWPFDocument doc {:keys [type content columns]} data formatting-context]
  (case type
    :header    (add-heading!    doc content)
    :paragraph (add-paragraph!  doc content)
    :footer    (add-footer-para! doc content)
    :table     (add-table!      doc columns data formatting-context)
    :spacer    (add-paragraph!  doc "")))

;; =============================================================================
;; XWPF Word generator record
;; =============================================================================

(defrecord XwpfWordGenerator [])

(extend-protocol ports/ReportGeneratorProtocol
  XwpfWordGenerator

  (generate! [_this report-def data opts]
    (log/debug "Generating Word report" {:id (:id report-def)})
    (let [formatting-context {:zone-id (or (:zone-id opts) (java.time.ZoneId/systemDefault))}
          doc      (XWPFDocument.)
          sections (or (:sections report-def) [])]
      (doseq [section sections]
        (render-section! doc section data formatting-context))
      (let [baos   (ByteArrayOutputStream.)
            _      (.write doc baos)
            result {:bytes    (.toByteArray baos)
                    :type     :word
                    :filename (or (:filename report-def)
                                  (str (name (:id report-def)) ".docx"))}]
        (log/debug "Word report generated"
                   {:id       (:id report-def)
                    :filename (:filename result)
                    :bytes    (count (:bytes result))})
        result)))

  (supported-type? [_ t] (= t :word)))

(defn create-word-generator
  "Create a new POI XWPF-backed Word generator."
  []
  (->XwpfWordGenerator))
