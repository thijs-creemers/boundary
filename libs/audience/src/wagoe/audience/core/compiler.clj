(ns wagoe.audience.core.compiler
  "Compile audience segment definitions into execution plans.
   Partitions filters into SQL-evaluable and predicate-evaluable phases."
  (:require [wagoe.audience.core.filter :as f]))

(defn compile-segment
  "Compile a segment definition into an execution plan.
   Returns {:sql-clauses [...] :predicates [...]}
   Accepts optional :now (java.time.LocalDate) for predicate date comparisons.
   If not provided, predicates that need dates will receive :now from the filter map."
  ([definition]
   (compile-segment definition {}))
  ([definition {:keys [now]}]
   (reduce
    (fn [plan filter-def]
      ;; `filter-with-now` to both: the date filters compute their cutoff from
      ;; `:now` on the SQL side too, since a bound parameter replaced the
      ;; PostgreSQL-only interval expression (BOU-425). Passing the bare
      ;; `filter-def` here is what left `filter->sql` unable to see it.
      (let [filter-with-now (if now (assoc filter-def :now now) filter-def)
            sql (f/filter->sql filter-with-now)]
        (if sql
          (update plan :sql-clauses conj sql)
          (update plan :predicates conj (f/filter->predicate filter-with-now)))))
    {:sql-clauses [] :predicates []}
    (:filters definition))))
