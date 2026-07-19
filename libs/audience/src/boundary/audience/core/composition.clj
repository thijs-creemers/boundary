(ns ^:boundary/allow-throw boundary.audience.core.composition
  "AND/OR/NOT composition of audience segment result sets.
   FC/IS rule: pure functions only — no I/O, no side effects.

   Exempt from the core throw ban (^:boundary/allow-throw): the throws here are
   composition-tree invariant guards (unknown operator, NOT-inside-OR, circular
   or unknown segment refs) raised deep inside recursive set-algebra. Converting
   them to error-return values requires a separate shell-side validation pass
   over the tree before evaluation; tracked in BOU-185."
  (:require [clojure.set :as set]))

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare compose-results)
(declare resolve-and-compose*)

;; =============================================================================
;; Internal helpers — compose-results
;; =============================================================================

(defn- resolve-node
  "Return the {:user-ids #{...}} for a single child node.
   Handles plain result maps, :not wrappers, and nested composition trees."
  [node]
  (cond
    ;; Negation wrapper — resolve inner node, return :not key so AND can subtract
    (:not node)
    {:not (:user-ids (resolve-node (:not node)))}

    ;; Leaf result already carries :user-ids
    (:user-ids node)
    node

    ;; Nested composition (contains :and / :or)
    :else
    {:user-ids (compose-results node)}))

;; =============================================================================
;; Public API — compose-results
;; =============================================================================

(defn compose-results
  "Evaluate a composition tree whose leaves are {:user-ids #{...}} maps.

   Supported operators (as map keys):
     :and  — intersection of all positive children; subtract :not children
     :or   — union of all children

   Returns: a set of user IDs."
  [tree]
  (cond
    (:and tree)
    (let [children  (map resolve-node (:and tree))
          positives (filter :user-ids children)
          negations (filter :not children)
          base      (if (seq positives)
                      (reduce set/intersection (map :user-ids positives))
                      #{})
          excluded  (apply set/union (map :not negations))]
      (set/difference base excluded))

    (:or tree)
    (let [children (map resolve-node (:or tree))]
      (when (some :not children)
        (throw (ex-info "NOT is not supported directly inside OR — wrap in AND"
                        {:tree tree})))
      (apply set/union (map :user-ids children)))

    :else
    (throw (ex-info "Unknown composition operator" {:tree tree}))))

;; =============================================================================
;; Internal helpers — ref resolution
;; =============================================================================

(defn- resolve-node-with-lookup
  "Resolve a single child node, substituting {:ref id} leaves via lookup.
   visited  - set of segment ids already on the resolution stack (cycle guard)
   lookup   - (fn [id] -> segment-map-or-nil)"
  [node lookup visited]
  (cond
    (:ref node)
    (let [id (:ref node)]
      (when (contains? visited id)
        (throw (ex-info "Circular segment reference"
                        {:type :circular-reference :id id})))
      (let [segment (lookup id)]
        (cond
          (nil? segment)
          (throw (ex-info "Unknown segment ref" {:id id}))

          (:user-ids segment)
          segment

          (:compose segment)
          {:user-ids (resolve-and-compose* (:compose segment) lookup (conj visited id))}

          :else
          (throw (ex-info "Segment has neither :user-ids nor :compose" {:id id})))))

    (:not node)
    {:not (:user-ids (resolve-node-with-lookup (:not node) lookup visited))}

    (:user-ids node)
    node

    ;; Nested composition
    :else
    {:user-ids (resolve-and-compose* node lookup visited)}))

;; =============================================================================
;; Public API — resolve-and-compose
;; =============================================================================

(defn resolve-and-compose*
  "Internal recursive implementation.
   tree     - composition map with possible {:ref ...} leaves
   lookup   - (fn [id] -> segment-map-or-nil)
   visited  - set of already-resolving ids"
  [tree lookup visited]
  (cond
    (:and tree)
    (let [children  (map #(resolve-node-with-lookup % lookup visited) (:and tree))
          positives (filter :user-ids children)
          negations (filter :not children)
          base      (if (seq positives)
                      (reduce set/intersection (map :user-ids positives))
                      #{})
          excluded  (apply set/union (map :not negations))]
      (set/difference base excluded))

    (:or tree)
    (let [children (map #(resolve-node-with-lookup % lookup visited) (:or tree))]
      (when (some :not children)
        (throw (ex-info "NOT is not supported directly inside OR — wrap in AND"
                        {:tree tree})))
      (apply set/union (map :user-ids children)))

    :else
    (throw (ex-info "Unknown composition operator" {:tree tree}))))

(defn resolve-and-compose
  "Evaluate a composition tree that may contain {:ref :keyword} leaves.

   Refs are resolved via lookup:
     (fn [id] -> {:user-ids #{...}} | {:compose {...}} | nil)

   Circular references throw ex-info with :type :circular-reference.

   Returns: a set of user IDs."
  [tree lookup]
  (resolve-and-compose* tree lookup #{}))
