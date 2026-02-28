(ns boundary.core.schema
  "Canonical schemas for the core library's primary domain shapes.

   Documents the two central data structures used across the framework:

   1. Validation result — returned by all core validation functions
   2. Interceptor context — the map that flows through interceptor pipelines

   These schemas are provided as documentation references. Runtime validation
   of these shapes lives in boundary.core.interceptor-context and
   boundary.core.validation.result respectively.")

;; =============================================================================
;; Validation Result Shape
;; =============================================================================

(def ValidationResult
  "Schema for the map returned by core validation functions.

   Success example:
     {:valid? true :data {:email \"user@example.com\"}}

   Failure example:
     {:valid? false :errors [{:field :email :code :invalid-format :message \"...\"}]}"
  [:map {:title "ValidationResult"}
   [:valid? :boolean]
   [:data {:optional true} :any]
   [:errors {:optional true}
    [:vector
     [:map {:closed false}
      [:field {:optional true} [:or :keyword :string]]
      [:code {:optional true} [:or :keyword :string]]
      [:message {:optional true} :string]]]]])

;; =============================================================================
;; Interceptor Context Shape
;; =============================================================================

(def InterceptorContext
  "Schema for the map that flows through the interceptor pipeline.

   Every interceptor receives and returns this map. Core interceptors add
   :result; shell interceptors handle :request, :system-deps, and I/O.

   Minimal example (CLI):
     {:request {:args [\"--help\"]} :system-deps {}}

   HTTP example:
     {:request {:headers {...} :body {...}} :system-deps {:logger ...} :result {...}}"
  [:map {:title "InterceptorContext" :closed false}
   [:request {:optional true}
    [:map {:closed false}
     [:headers {:optional true} [:map-of :string :string]]
     [:body {:optional true} :any]
     [:params {:optional true} [:map-of :keyword :any]]
     [:query {:optional true} [:map-of :string :string]]
     [:args {:optional true} [:vector :string]]
     [:options {:optional true} [:map-of :keyword :any]]]]
   [:system-deps {:optional true} [:map-of :keyword :any]]
   [:result {:optional true} ValidationResult]
   [:error {:optional true} :map]])
