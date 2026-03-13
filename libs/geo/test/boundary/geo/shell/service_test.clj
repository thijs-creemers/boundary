(ns boundary.geo.shell.service-test
  "Integration tests for GeoService — mock provider + atom-backed cache."
  (:require [boundary.geo.shell.service :as sut]
            [boundary.geo.ports :as ports]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; Mock provider
;; =============================================================================

(defrecord MockProvider [results-atom call-count-atom]
  ports/GeoProviderProtocol

  (geocode [_this query]
    (swap! call-count-atom inc)
    (get @results-atom (:city query)))

  (reverse-geocode [_this point]
    (swap! call-count-atom inc)
    (when (= 52.3676 (:lat point))
      {:lat               52.3676
       :lng               4.9041
       :formatted-address "Amsterdam, Netherlands"
       :provider          :mock
       :cached?           false})))

(defn- make-mock-provider
  "Return a mock provider with pre-seeded results keyed by :city."
  [results]
  (->MockProvider (atom results) (atom 0)))

;; =============================================================================
;; Mock cache (atom-backed)
;; =============================================================================

(defrecord AtomCache [store-atom]
  ports/GeoCacheProtocol

  (cache-lookup [_this hash]
    (get @store-atom hash))

  (cache-store! [_this hash result]
    (swap! store-atom assoc hash (assoc result :cached? true))
    nil))

(defn- make-atom-cache []
  (->AtomCache (atom {})))

;; =============================================================================
;; Test fixtures
;; =============================================================================

(def amsterdam-result
  {:lat               52.3676
   :lng               4.9041
   :formatted-address "Amsterdam, Netherlands"
   :city              "Amsterdam"
   :country           "Netherlands"
   :provider          :mock
   :cached?           false})

;; =============================================================================
;; geocode! — no cache
;; =============================================================================

(deftest geocode-no-cache-test
  ^:integration
  (let [provider (make-mock-provider {"Amsterdam" amsterdam-result})
        service  {:providers [provider] :cache nil}]
    (testing "returns result from provider"
      (let [result (sut/geocode! service {:city "Amsterdam"})]
        (is (some? result))
        (is (= 52.3676 (:lat result)))
        (is (= :mock (:provider result)))))
    (testing "returns nil when provider has no match"
      (is (nil? (sut/geocode! service {:city "Unknown"}))))))

;; =============================================================================
;; geocode! — with cache
;; =============================================================================

(deftest geocode-cache-miss-then-hit-test
  ^:integration
  (let [provider  (make-mock-provider {"Amsterdam" amsterdam-result})
        geo-cache (make-atom-cache)
        service   {:providers [provider] :cache geo-cache}]
    (testing "first call is a cache miss — provider is called"
      (let [result (sut/geocode! service {:city "Amsterdam"})]
        (is (some? result))
        (is (= 1 @(:call-count-atom provider)))))

    (testing "second call is a cache hit — provider not called again"
      (let [result (sut/geocode! service {:city "Amsterdam"})]
        (is (some? result))
        (is (true? (:cached? result)))
        ;; call-count should still be 1
        (is (= 1 @(:call-count-atom provider)))))))

;; =============================================================================
;; geocode! — fallback chain
;; =============================================================================

(deftest geocode-fallback-chain-test
  ^:integration
  (let [provider-a (make-mock-provider {})                     ; always nil
        provider-b (make-mock-provider {"Amsterdam" amsterdam-result})
        service    {:providers [provider-a provider-b] :cache nil}]
    (testing "falls back to second provider when first returns nil"
      (let [result (sut/geocode! service {:city "Amsterdam"})]
        (is (some? result))
        (is (= 1 @(:call-count-atom provider-a)))
        (is (= 1 @(:call-count-atom provider-b)))))))

;; =============================================================================
;; reverse-geocode!
;; =============================================================================

(deftest reverse-geocode-test
  ^:integration
  (let [provider (make-mock-provider {})
        service  {:providers [provider] :cache nil}]
    (testing "returns result for known coordinates"
      (let [result (sut/reverse-geocode! service {:lat 52.3676 :lng 4.9041})]
        (is (some? result))
        (is (= :mock (:provider result)))))

    (testing "returns nil for unknown coordinates"
      (is (nil? (sut/reverse-geocode! service {:lat 0.0 :lng 0.0}))))))

;; =============================================================================
;; distance — delegates to core/math
;; =============================================================================

(deftest distance-test
  ^:integration
  (testing "distance between Amsterdam and Rotterdam is approximately 57 km"
    (let [d (sut/distance {:lat 52.3676 :lng 4.9041}
                          {:lat 51.9225 :lng 4.4792})]
      (is (> d 55.0))
      (is (< d 60.0))))

  (testing "distance is zero for same point"
    (let [amsterdam {:lat 52.3676 :lng 4.9041}]
      (is (< (sut/distance amsterdam amsterdam) 0.001)))))
