(ns wagoe.audience-dialect-test
  "The audience schema and store, against every database adapter reachable here.

   Audience had one fixture, H2, whose columns were TEXT. Its PostgreSQL
   migration declared JSONB and ran nowhere, so `save-audience` had never
   executed against the adapter the framework recommends for production: it
   fails with \"column is of type jsonb but expression is of type character
   varying\". SQLite failed earlier still, on DDL its dialect cannot parse.

   One table of cases across the adapters, because that is what a per-adapter
   fixture cannot tell you (BOU-419 review)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [support.embedded-pg :as epg]
            [wagoe.audience.core.compiler :as compiler]
            [wagoe.audience.shell.cache :as audience-cache]
            [wagoe.audience.ports :as ports]
            [wagoe.audience.shell.persistence :as p]))

(defn- backends
  "Each is [label make]; `make` returns [datasource stop!]."
  []
  [["h2"     (fn [] [(jdbc/get-datasource
                      {:dbtype "h2:mem"
                       :dbname (str "aud_" (System/nanoTime) ";DB_CLOSE_DELAY=-1")})
                     (fn [] nil)])]
   ["sqlite" (fn [] (let [f (str (System/getProperty "java.io.tmpdir")
                                 "/aud-" (System/nanoTime) ".db")]
                      [(jdbc/get-datasource {:dbtype "sqlite" :dbname f})
                       (fn [] (.delete (java.io.File. f)))]))]
   ["postgresql" (fn [] (let [pg (epg/start!)]
                          [(epg/datasource pg) (fn [] (epg/stop! pg))]))]])

(deftest ^:integration the-sweep-covers-every-reachable-adapter
  (is (= 3 (count (backends)))
      "an adapter dropped out of the sweep; the cases below would say less than they look"))

(deftest ^:integration a-definition-round-trips-on-every-adapter
  (doseq [[label make] (backends)]
    (testing label
      (let [[ds stop!] (make)]
        (try
          (is (= label (name (p/dialect ds))) "the dialect was not detected")
          (p/initialize-audience-schema! ds)

          (let [store (p/create-audience-store ds)
                users [(random-uuid) (random-uuid)]]
            (testing "a stored definition compiles to the same plan it did in memory"
              ;; The promise is not that the JSON bytes match — it is that the
              ;; audience still means what it meant. JSON has no keywords, and a
              ;; definition reloaded as strings compiled to a constant predicate
              ;; instead of a SQL clause: the right count of filters, the wrong
              ;; users (BOU-419 review).
              (let [definition {:id      :premium
                                :label   "Premium"
                                :filters [{:type :demographics :field :plan
                                           :op :eq :value "premium"}]
                                :tags    ["billing"]}]
                (ports/save-audience store definition)
                (let [found (ports/find-audience store :premium)]
                  (is (= :premium (:id found)))
                  (is (= ["billing"] (:tags found)))
                  (is (= (:sql-clauses (compiler/compile-segment definition))
                         (:sql-clauses (compiler/compile-segment found)))
                      "the reloaded definition compiles to a different plan")
                  (is (= [[:= :plan "premium"]]
                         (:sql-clauses (compiler/compile-segment found)))
                      "and not to the plan the filter describes"))))

            (testing "memberships are written and read against its own id type"
              (p/save-memberships! ds :premium users)
              (is (= (set users) (set (p/get-memberships ds :premium)))))

            (testing "a cached result is read back, TTL and all"
              ;; PostgreSQL returns a JSONB column as a PGobject, and the cache
              ;; had its own decoder that answered nil for one — so the TTL was
              ;; nil, the cache never hit, and every resolve recomputed the
              ;; membership (BOU-419 review).
              (let [c (audience-cache/create-audience-cache ds nil)]
                (ports/put-cached c :premium {:user-ids (set users) :count 2} 60)
                (let [hit (ports/get-cached c :premium)]
                  (is (some? hit) "a result cached one minute ago was not found")
                  (is (true? (:cached? hit)))
                  (is (= (set users) (:user-ids hit))))))

            (testing "and it can be deleted"
              (ports/delete-audience store :premium)
              (is (nil? (ports/find-audience store :premium)))))
          (finally (stop!)))))))
