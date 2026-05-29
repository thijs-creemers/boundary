(ns boundary.audience.core.compiler
  "Compile audience segment definitions into execution plans.
   Partitions filters into SQL-evaluable and predicate-evaluable phases."
  (:require [boundary.audience.core.filter :as f]))

(defn compile-segment
  "Compile a segment definition into an execution plan.
   Returns {:sql-clauses [...] :predicates [...]}"
  [definition]
  (reduce
   (fn [plan filter-def]
     (let [sql (f/filter->sql filter-def)]
       (if sql
         (update plan :sql-clauses conj sql)
         (update plan :predicates conj (f/filter->predicate filter-def)))))
   {:sql-clauses [] :predicates []}
   (:filters definition)))
