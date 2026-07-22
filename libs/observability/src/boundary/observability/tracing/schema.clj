(ns boundary.observability.tracing.schema
  "Malli schemas for tracing configuration.")

(def TracingProvider
  "Supported tracer backends. `:otlp` is reserved for the OpenTelemetry exporter
   (a following slice); `:no-op` (default) and `:logging` ship today."
  [:enum :no-op :logging :otlp])

(def TracingConfig
  [:map
   [:provider {:optional true} TracingProvider]
   ;; Logical service name attached to spans (OTel `service.name`).
   [:service-name {:optional true} [:string {:min 1 :max 200}]]])

(def default-tracing-config
  {:provider :no-op})
