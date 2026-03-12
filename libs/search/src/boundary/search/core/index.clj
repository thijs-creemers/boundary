(ns boundary.search.core.index
  "Search index definition registry and document construction.

   The defsearch macro defines a search index and registers it in the
   global in-process registry.

   Key functions:
   - defsearch macro       — define and register an index
   - register-search!      — low-level registration
   - get-search            — look up by index-id
   - build-document        — construct a SearchDocument from field values
   - filter-key->json-key  — convert a filter keyword to a snake_case JSON key"
  (:require [clojure.string :as str])
  (:import [java.util UUID]
           [java.time Instant]))

;; =============================================================================
;; In-process Registry
;; =============================================================================

(defonce ^:private registry (atom {}))

(defn register-search!
  "Register a SearchDefinition in the global registry.

   Args:
     definition - SearchDefinition map with :id keyword

   Returns:
     :id keyword"
  [definition]
  (let [id (:id definition)]
    (swap! registry assoc id definition)
    id))

(defn get-search
  "Retrieve a registered SearchDefinition by id.

   Args:
     index-id - keyword

   Returns:
     SearchDefinition map or nil"
  [index-id]
  (get @registry index-id))

(defn list-searches
  "List all registered search index ids.

   Returns:
     Vector of keyword ids"
  []
  (vec (keys @registry)))

(defn clear-registry!
  "Remove all registered definitions. Use in test fixtures.

   Returns:
     nil"
  []
  (reset! registry {})
  nil)

;; =============================================================================
;; Filter key conversion (shared with persistence layer)
;; =============================================================================

(defn filter-key->json-key
  "Convert a filter keyword to a snake_case JSON object key.

   :tenant-id -> \"tenant_id\"
   :status    -> \"status\"

   Used consistently by both build-document (storage) and the SQL
   query builders (retrieval) so that stored and queried keys always match."
  [k]
  (str/replace (name k) "-" "_"))

;; =============================================================================
;; defsearch Macro
;; =============================================================================

(defmacro defsearch
  "Define a search index configuration and register it globally.

   Binds a var and registers in the in-process search registry.

   Example:
     (defsearch product-search
       {:id          :product-search
        :entity-type :product
        :language    :english
        :fields      [{:name :title       :weight :A}
                      {:name :description :weight :B}
                      {:name :tags        :weight :C}]
        :options     {:highlight? true
                      :trigrams?  true}})

   This is equivalent to:
     (def product-search {...})
     (register-search! product-search)"
  [var-name definition]
  `(do
     (def ~var-name ~definition)
     (register-search! ~definition)
     ~var-name))

;; =============================================================================
;; Document Construction
;; =============================================================================

(defn build-document
  "Construct a SearchDocument from a SearchDefinition and field values.

   Distributes field values to the correct weight buckets (A/B/C/D).
   Concatenates all text into :content-all for fallback LIKE search.

   Args:
     definition   - SearchDefinition map
     entity-id    - UUID
     field-values - map of field-name -> string or seq value
                    Seqs are joined with a space.
     opts         - optional map with :metadata

   Returns:
     SearchDocument map ready for ISearchStore.upsert-document!"
  [definition entity-id field-values & [opts]]
  (let [index-id    (:id definition)
        entity-type (:entity-type definition)
        language    (name (or (:language definition) :english))
        fields      (:fields definition)
        ;; Accumulate values per weight bucket
        weight-map  (reduce
                     (fn [acc {:keys [name weight]}]
                       (let [weight-key (keyword (str "weight-"
                                                      (str/lower-case
                                                       (clojure.core/name weight))))
                             raw-val    (get field-values name)
                             str-val    (cond
                                          (nil? raw-val)        nil
                                          (sequential? raw-val) (str/join " " (map str raw-val))
                                          :else                 (str raw-val))]
                         (if str-val
                           (update acc weight-key
                                   (fn [existing]
                                     (if existing
                                       (str existing " " str-val)
                                       str-val)))
                           acc)))
                     {}
                     fields)
        content-all (->> [:weight-a :weight-b :weight-c :weight-d]
                         (keep #(get weight-map %))
                         (str/join " "))]
    (cond-> {:id          (str (UUID/randomUUID))
             :index-id    index-id
             :entity-type entity-type
             :entity-id   entity-id
             :language    language
             :weight-a    (get weight-map :weight-a "")
             :weight-b    (get weight-map :weight-b "")
             :weight-c    (get weight-map :weight-c "")
             :weight-d    (get weight-map :weight-d "")
             :content-all content-all
             :updated-at  (Instant/now)}
      (:metadata opts)
      (assoc :metadata (:metadata opts))
      ;; Store filter values so they can be used as WHERE-clause predicates.
      (seq (:filter-values opts))
      (assoc :filters (:filter-values opts)))))
