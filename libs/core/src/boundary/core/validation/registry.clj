(ns boundary.core.validation.registry
  "Validation rule registry for tracking and organizing validation rules.
  
   This namespace provides a central registry for validation rules across
   all modules, enabling:
   - Rule discovery and documentation
   - Coverage tracking
   - Conflict detection
   - Rule composition and reuse
   
   Rule Format:
     {:rule-id        keyword           ; Unique identifier (e.g., :user.email/required)
      :description    string            ; Human-readable description
      :category       keyword           ; :schema | :business | :cross-field | :context
      :module         keyword           ; Module name (e.g., :user, :billing)
      :fields         [keyword]         ; Affected fields
      :error-code     keyword           ; Default error code
      :validator-fn   (fn [data] ...)   ; Actual validation function
      :dependencies   [rule-id]         ; Required rules (optional)
      :metadata       map}              ; Additional metadata (optional)
   
   Design Principles:
   - Registry is atom-based for REPL-friendly development
   - Thread-safe registration
   - Immutable rule definitions
   - Forward-compatible metadata extension")

;; =============================================================================
;; Registry State
;; =============================================================================

"Global validation rule registry. Structure: {rule-id rule-definition}"
(defonce ^:private registry
  (atom {}))

"Track which rules have been executed (for coverage reporting). Structure: {rule-id execution-count}"
(defonce ^:private execution-tracking
  (atom {}))

;; =============================================================================
;; Rule Registration
;; =============================================================================

(defn register-rule!
  "Register a validation rule in the global registry.
  
   Args:
     rule: Rule definition map (must include :rule-id)
   
   Returns:
     Registered rule definition
   
   Throws:
     IllegalArgumentException if rule-id is missing or already registered
   
   Example:
     (register-rule!
       {:rule-id :user.email/required
        :description \"Email is required for user creation\"
        :category :schema
        :module :user
        :fields [:email]
        :error-code :required
        :validator-fn (fn [data] (some? (:email data)))})"
  [rule]
  (when-not (:rule-id rule)
    (throw (IllegalArgumentException. "Rule must have :rule-id")))
  (let [rule-id (:rule-id rule)]
    (when (get @registry rule-id)
      (throw (IllegalArgumentException.
              (str "Rule already registered: " rule-id))))
    (swap! registry assoc rule-id rule)
    rule))

(defn register-rules!
  "Register multiple validation rules.
  
   Args:
     rules: Collection of rule definition maps
   
   Returns:
     Vector of registered rules
   
   Example:
     (register-rules! [rule1 rule2 rule3])"
  [rules]
  (mapv register-rule! rules))

(defn unregister-rule!
  "Remove a rule from the registry (primarily for testing).
  
   Args:
     rule-id: Rule identifier keyword
   
   Returns:
     Removed rule definition or nil"
  [rule-id]
  (let [rule (get @registry rule-id)]
    (swap! registry dissoc rule-id)
    (swap! execution-tracking dissoc rule-id)
    rule))

(defn clear-registry!
  "Clear all rules from registry (primarily for testing).
  
   Returns:
     Empty map"
  []
  (reset! registry {})
  (reset! execution-tracking {})
  {})

;; =============================================================================
;; Rule Lookup
;; =============================================================================

(defn get-rule
  "Retrieve a rule definition by id.
  
   Args:
     rule-id: Rule identifier keyword
   
   Returns:
     Rule definition map or nil"
  [rule-id]
  (get @registry rule-id))

(defn get-all-rules
  "Get all registered rules.
  
   Returns:
     Vector of rule definitions"
  []
  (vec (vals @registry)))

(defn get-rules-by-module
  "Get all rules for a specific module.
  
   Args:
     module: Module keyword (e.g., :user, :billing)
   
   Returns:
     Vector of rule definitions"
  [module]
  (filterv #(= (:module %) module) (get-all-rules)))

(defn get-rules-by-category
  "Get all rules of a specific category.
  
   Args:
     category: Category keyword (:schema, :business, :cross-field, :context)
   
   Returns:
     Vector of rule definitions"
  [category]
  (filterv #(= (:category %) category) (get-all-rules)))

(defn get-rules-for-field
  "Get all rules that validate a specific field.
  
   Args:
     field: Field keyword
   
   Returns:
     Vector of rule definitions"
  [field]
  (filterv #(some #{field} (:fields %)) (get-all-rules)))

;; =============================================================================
;; Rule Execution Tracking (for coverage)
;; =============================================================================

(defn track-rule-execution!
  "Record that a rule was executed (for coverage reporting).
  
   Args:
     rule-id: Rule identifier keyword
   
   Returns:
     Updated execution count"
  [rule-id]
  (swap! execution-tracking update rule-id (fnil inc 0)))

(defn get-execution-count
  "Get execution count for a rule.
  
   Args:
     rule-id: Rule identifier keyword
   
   Returns:
     Integer execution count (0 if never executed)"
  [rule-id]
  (get @execution-tracking rule-id 0))

(defn get-execution-stats
  "Get execution statistics for all rules.
  
   Returns:
     Map with :total-rules, :executed-rules, :coverage-percent, :by-rule
   
   Example:
     {:total-rules 50
      :executed-rules 45
      :coverage-percent 90.0
      :by-rule {:user.email/required 100
                :user.name/min-length 50
                ...}}"
  []
  (let [total-rules (count @registry)
        executed-rules (count (filter pos? (vals @execution-tracking)))
        coverage (if (zero? total-rules)
                   0.0
                   (* 100.0 (/ executed-rules total-rules)))]
    {:total-rules total-rules
     :executed-rules executed-rules
     :coverage-percent coverage
     :by-rule @execution-tracking}))

(defn reset-execution-tracking!
  "Reset all execution tracking (for testing/benchmarking).
  
   Returns:
     Empty map"
  []
  (reset! execution-tracking {}))

;; =============================================================================
;; Rule Validation
;; =============================================================================

(defn valid-rule?
  "Check if rule definition is valid.
  
   Args:
     rule: Rule definition map
   
   Returns:
     Boolean indicating validity"
  [rule]
  (and (map? rule)
       (keyword? (:rule-id rule))
       (string? (:description rule))
       (keyword? (:category rule))
       (keyword? (:module rule))
       (vector? (:fields rule))
       (keyword? (:error-code rule))
       (fn? (:validator-fn rule))))

(defn validate-rule
  "Validate rule definition and return errors.
  
   Args:
     rule: Rule definition map
   
   Returns:
     Vector of error strings (empty if valid)"
  [rule]
  (cond-> []
    (not (map? rule))
    (conj "Rule must be a map")

    (not (keyword? (:rule-id rule)))
    (conj ":rule-id must be a keyword")

    (not (string? (:description rule)))
    (conj ":description must be a string")

    (not (contains? #{:schema :business :cross-field :context} (:category rule)))
    (conj ":category must be :schema, :business, :cross-field, or :context")

    (not (keyword? (:module rule)))
    (conj ":module must be a keyword")

    (not (and (vector? (:fields rule)) (every? keyword? (:fields rule))))
    (conj ":fields must be a vector of keywords")

    (not (keyword? (:error-code rule)))
    (conj ":error-code must be a keyword")

    (not (fn? (:validator-fn rule)))
    (conj ":validator-fn must be a function")))

;; =============================================================================
;; Conflict Detection
;; =============================================================================

(defn find-duplicate-rule-ids
  "Find duplicate rule IDs in a collection of rules.
  
   Args:
     rules: Collection of rule definitions
   
   Returns:
     Set of duplicate rule-ids"
  [rules]
  (let [id-frequencies (frequencies (map :rule-id rules))
        duplicates (filter #(> (val %) 1) id-frequencies)]
    (set (map key duplicates))))

(defn find-conflicting-rules
  "Find rules with overlapping fields and categories that might conflict.
  
   Args:
     None (uses registry)
   
   Returns:
     Vector of conflict maps {:rule-ids [...] :reason string}"
  []
  (let [rules (get-all-rules)
        field-groups (group-by (fn [r] [(:category r) (set (:fields r))]) rules)]
    (->> field-groups
         (filter #(> (count (val %)) 1))
         (mapv (fn [[k rules]]
                 {:rule-ids (mapv :rule-id rules)
                  :category (first k)
                  :fields (second k)
                  :reason "Multiple rules with same category and fields"})))))

;; =============================================================================
;; Registry Info
;; =============================================================================

(defn registry-stats
  "Get statistics about the rule registry.
  
   Returns:
     Map with counts by module, category, etc."
  []
  (let [rules (get-all-rules)]
    {:total-rules (count rules)
     :by-module (frequencies (map :module rules))
     :by-category (frequencies (map :category rules))
     :fields-count (count (distinct (mapcat :fields rules)))
     :error-codes-count (count (distinct (map :error-code rules)))}))

(defn registry-ready?
  "Check if registry is ready for use (has rules registered).
  
   Returns:
     Boolean indicating readiness"
  []
  (pos? (count @registry)))
