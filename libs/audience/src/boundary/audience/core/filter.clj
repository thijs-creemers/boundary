(ns boundary.audience.core.filter
  "Filter multimethods for audience segment evaluation.
   FC/IS rule: pure functions only — no I/O, no side effects, no logging."
  (:require [clojure.string :as str]))

;; =============================================================================
;; SQL operator mapping
;; =============================================================================

(def ^:private sql-ops
  "Maps filter operator keywords to HoneySQL operator keywords."
  {:eq       :=
   :neq      :<>
   :gt       :>
   :gte      :>=
   :lt       :<
   :lte      :<=
   :in       :in
   :contains :like})

(defn- sql-op
  "Return the HoneySQL operator for a filter :op keyword.
   For :contains, the value is wrapped in % wildcards."
  [op value]
  (case op
    :in       [:in value]
    :contains [:like (str "%" value "%")]
    [(get sql-ops op :=) value]))

(defn- field-clause
  "Build a HoneySQL clause [op field value] for a field-based filter."
  [{:keys [field op value]}]
  (let [[honey-op honey-val] (sql-op op value)]
    [honey-op field honey-val]))

;; =============================================================================
;; filter->sql multimethod
;; =============================================================================

(defmulti filter->sql
  "Convert a filter map to a HoneySQL where-clause fragment.
   Dispatches on the :type key.

   Returns a HoneySQL clause vector, or nil if the filter cannot be
   expressed as SQL (e.g. :behavior uses an in-process predicate instead).

   Built-in types: :demographics, :location, :role, :account-tenure,
                   :last-active, :behavior, :feature-usage

   Applications can extend the multimethod:
     (defmethod filter->sql :my-type [filt] ...)"
  :type)

;; =============================================================================
;; filter->predicate multimethod
;; =============================================================================

(defmulti filter->predicate
  "Convert a filter map to a predicate function (fn [user-map] -> boolean).
   Dispatches on the :type key.

   Built-in types without a SQL representation (:behavior, :feature-usage)
   use this path. DB-evaluable filters may also provide a predicate for
   in-process post-filtering.

   Applications can extend the multimethod:
     (defmethod filter->predicate :my-type [filt] ...)"
  :type)

;; =============================================================================
;; :demographics — simple field equality / comparison
;; =============================================================================

(defmethod filter->sql :demographics [filt]
  (field-clause filt))

(defmethod filter->predicate :demographics [{:keys [field op value]}]
  (let [[honey-op honey-val] (sql-op op value)
        compare-fn (case honey-op
                     :=    #(= % honey-val)
                     :<>   #(not= % honey-val)
                     :>    #(pos? (compare % honey-val))
                     :>=   #(>= (compare % honey-val) 0)
                     :<    #(neg? (compare % honey-val))
                     :<=   #(<= (compare % honey-val) 0)
                     :in   #(contains? (set honey-val) %)
                     :like #(boolean (re-find (re-pattern (str/replace (str honey-val) "%" ".*")) (str %)))
                     (constantly false))]
    (fn [user] (compare-fn (get user field)))))

;; =============================================================================
;; :location — geographic field filter (same logic as demographics)
;; =============================================================================

(defmethod filter->sql :location [filt]
  (field-clause filt))

(defmethod filter->predicate :location [filt]
  (filter->predicate (assoc filt :type :demographics)))

;; =============================================================================
;; :role — role equality filter
;; =============================================================================

(defmethod filter->sql :role [filt]
  (field-clause filt))

(defmethod filter->predicate :role [filt]
  (filter->predicate (assoc filt :type :demographics)))

;; =============================================================================
;; :account-tenure — days since account creation
;;   SQL: compare (current_date - created_at) in days against :value
;; =============================================================================

(defmethod filter->sql :account-tenure [{:keys [op value]}]
  ;; tenure >= N days means created_at <= now - N days (inverted comparison)
  (let [sql-operator (get {:gte :<=, :gt :<, :lte :>=, :lt :>, :eq :=} op :<=)]
    [sql-operator :created_at [:raw (str "CURRENT_DATE - INTERVAL '" value " days'")]]))

(defmethod filter->predicate :account-tenure [{:keys [op value]}]
  (let [compare-fn (case op
                     :gte #(>= % value)
                     :gt  #(> % value)
                     :lte #(<= % value)
                     :lt  #(< % value)
                     :eq  #(= % value)
                     :neq #(not= % value)
                     (constantly false))]
    (fn [user]
      (when-let [created (:created-at user)]
        (let [days (.between java.time.temporal.ChronoUnit/DAYS
                             (.toLocalDate (.atZone (.toInstant created)
                                                    java.time.ZoneOffset/UTC))
                             (java.time.LocalDate/now))]
          (compare-fn days))))))

;; =============================================================================
;; :last-active — activity within a rolling date window
;;   :within-days op: last_active_at >= (now - N days)
;; =============================================================================

(defmethod filter->sql :last-active [{:keys [op value]}]
  (when (= op :within-days)
    [:>= :last_active_at [:raw (str "CURRENT_DATE - INTERVAL '" value " days'")]]))

(defmethod filter->predicate :last-active [{:keys [op value]}]
  (case op
    :within-days
    (fn [user]
      (when-let [last-active (:last-active-at user)]
        (let [cutoff (.minusDays (java.time.LocalDate/now) value)
              active-date (.toLocalDate (.atZone (.toInstant last-active)
                                                 java.time.ZoneOffset/UTC))]
          (not (.isBefore active-date cutoff)))))
    (constantly false)))

;; =============================================================================
;; :behavior — arbitrary in-process predicate; not SQL-evaluable
;; =============================================================================

(defmethod filter->sql :behavior [_filt]
  nil)

(defmethod filter->predicate :behavior [{:keys [value]}]
  value)

;; =============================================================================
;; :feature-usage — declarative feature-usage check; not SQL-evaluable
;;   :used-within N days checks user's feature usage log
;; =============================================================================

(defmethod filter->sql :feature-usage [_filt]
  nil)

(defmethod filter->predicate :feature-usage [{:keys [field op value]}]
  (case op
    :used-within
    (fn [user]
      (let [usage (get-in user [:feature-usage field])]
        (when usage
          (let [cutoff (.minusDays (java.time.LocalDate/now) value)
                last-used (.toLocalDate (.atZone (.toInstant usage)
                                                 java.time.ZoneOffset/UTC))]
            (not (.isBefore last-used cutoff))))))
    (constantly false)))
