(ns boundary.observability.tracing.shell.adapters.logging
  "Logging tracer: records spans to the log (start, end + duration, events,
   exceptions). Useful for local development and for seeing the trace shape
   without standing up an OpenTelemetry collector. Not a sampled/exportable
   tracer — for real distributed tracing use the OTLP adapter.

   Spans are plain immutable maps carrying a generated trace-id/span-id and a
   start timestamp; `set-attributes!` therefore only logs (a real backend
   mutates its span object)."
  (:require [boundary.observability.tracing.ports :as ports]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(defn- hex-id [n]
  (subs (str/replace (str (UUID/randomUUID)) "-" "") 0 n))

(defrecord LoggingTracer []
  ports/ITracer
  (start-span! [this name] (ports/start-span! this name {}))
  (start-span! [_ name attributes]
    (let [span {:name     (if (keyword? name) (subs (str name) 1) (str name))
                :trace-id (hex-id 32)
                :span-id  (hex-id 16)
                :start-ns (System/nanoTime)
                :attrs    (or attributes {})}]
      (log/info "span.start" (dissoc span :start-ns))
      span))

  (end-span! [_ span]
    (when span
      (log/info "span.end"
                {:name        (:name span)
                 :trace-id    (:trace-id span)
                 :span-id     (:span-id span)
                 :duration-ms (/ (double (- (System/nanoTime) (:start-ns span))) 1e6)
                 :attrs       (:attrs span)}))
    nil)

  (add-event! [this span name] (ports/add-event! this span name {}))
  (add-event! [_ span name attributes]
    (log/info "span.event" {:name (str name) :span-id (:span-id span) :attrs attributes})
    nil)

  (set-attributes! [_ span attributes]
    (log/debug "span.attrs" {:span-id (:span-id span) :attrs attributes})
    nil)

  (record-exception! [_ span throwable]
    (log/warn throwable "span.exception" {:span-id (:span-id span)})
    nil)

  (span-context [_ span]
    {:trace-id (:trace-id span) :span-id (:span-id span)})

  (with-span* [this name attributes f]
    (let [span (ports/start-span! this name attributes)]
      (try
        (f span)
        (catch Throwable t
          (ports/record-exception! this span t)
          (throw t))
        (finally
          (ports/end-span! this span))))))

(defn create-tracer
  ([] (->LoggingTracer))
  ([_config] (->LoggingTracer)))

(defn create-tracing-component
  ([] (->LoggingTracer))
  ([_config] (->LoggingTracer)))
