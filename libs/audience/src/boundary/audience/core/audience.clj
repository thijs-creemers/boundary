(ns boundary.audience.core.audience
  "Audience definition registry and defaudience macro.
   FC/IS rule: no I/O here — pure in-process atom-backed registry."
  (:require [boundary.audience.schema :as schema]
            [malli.core :as m]))

;; =============================================================================
;; In-process registry
;; =============================================================================

(defonce ^:private registry (atom {}))

(defn register-audience!
  "Register an audience definition in the in-process registry.

   Validates the definition against AudienceDefinition schema before
   registration. Throws ex-info on invalid input.

   Args:
     definition - AudienceDefinition map (must contain :id keyword)

   Returns:
     definition"
  [definition]
  (when-not (m/validate schema/AudienceDefinition definition)
    (throw (ex-info "Invalid audience definition"
                    {:errors (m/explain schema/AudienceDefinition definition)
                     :id     (:id definition)})))
  (let [id (:id definition)]
    (swap! registry assoc id definition)
    definition))

(defn get-audience
  "Retrieve a registered audience definition by id.

   Args:
     id - keyword

   Returns:
     AudienceDefinition map, or nil if not registered"
  [id]
  (get @registry id))

(defn list-audiences
  "Return the ids of all registered audience definitions.

   Returns:
     Sequence of keywords"
  []
  (keys @registry))

(defn clear-registry!
  "Remove all registered audience definitions.
   Primarily used in tests to reset state between test runs.

   Returns:
     empty map"
  []
  (reset! registry {}))

;; =============================================================================
;; defaudience macro
;; =============================================================================

(defmacro defaudience
  "Define an audience segment and register it in the in-process registry.

   Creates a Var named `sym` bound to `definition-map` and registers it
   so it can be looked up by its :id at runtime.

   Example:
     (defaudience free-users
       {:id      :free-users
        :label   \"Free plan users\"
        :filters [{:type :plan :field :plan :op :eq :value \"free\"}]})

   The defined audience is immediately accessible via get-audience."
  [sym definition-map]
  `(do
     (def ~sym ~definition-map)
     (register-audience! ~sym)
     ~sym))
