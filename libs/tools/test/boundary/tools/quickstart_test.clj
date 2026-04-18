(ns boundary.tools.quickstart-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.tools.quickstart :as quickstart]
            [clojure.java.io :as io]))

;; =============================================================================
;; Preset resolution
;; =============================================================================

(deftest presets-contain-all-database-options
  (testing "all supported databases are represented"
    (let [db-values (set (map :database (vals quickstart/presets)))]
      (is (contains? db-values "h2"))
      (is (contains? db-values "postgresql"))
      (is (contains? db-values "sqlite"))
      (is (contains? db-values "mysql")))))

(deftest presets-have-required-fields
  (testing "every preset has :database and :description"
    (doseq [[name preset] quickstart/presets]
      (is (string? (:database preset)) (str "preset " name " missing :database"))
      (is (string? (:description preset)) (str "preset " name " missing :description")))))

(deftest resolve-preset-returns-preset-map
  (testing "known presets resolve to maps"
    (is (= {:database "h2" :description "H2 in-memory, no extras"}
           (#'quickstart/resolve-preset "minimal")))
    (is (= "postgresql" (:database (#'quickstart/resolve-preset "standard"))))
    (is (= "sqlite" (:database (#'quickstart/resolve-preset "sqlite"))))
    (is (= "mysql" (:database (#'quickstart/resolve-preset "mysql")))))

  (testing "unknown preset returns nil"
    (is (nil? (#'quickstart/resolve-preset "banana")))
    (is (nil? (#'quickstart/resolve-preset "")))))

;; =============================================================================
;; Config injection
;; =============================================================================

(deftest inject-module-config-test
  (testing "injects :boundary/tasks before :inactive section"
    (let [tmp (java.io.File/createTempFile "config" ".edn")
          path (.getAbsolutePath tmp)]
      (try
        (spit path (str "{:active\n"
                        " {:boundary/settings {:name \"test\"}}\n"
                        "\n"
                        " :inactive\n"
                        " {:boundary/cache {:provider :redis}}}\n"))
        (is (true? (#'quickstart/inject-module-config path)))
        (let [result (slurp path)]
          (is (re-find #":boundary/tasks" result)
              "config should contain :boundary/tasks after injection")
          (is (re-find #":enabled\? true" result)
              "config should contain :enabled? true"))
        (finally
          (.delete tmp)))))

  (testing "skips injection when :boundary/tasks already present"
    (let [tmp (java.io.File/createTempFile "config" ".edn")
          path (.getAbsolutePath tmp)]
      (try
        (spit path ":boundary/tasks {:enabled? true}\n:inactive {}")
        (is (true? (#'quickstart/inject-module-config path)))
        ;; Should not duplicate
        (is (= 1 (count (re-seq #":boundary/tasks" (slurp path)))))
        (finally
          (.delete tmp)))))

  (testing "returns false for non-existent file"
    (is (nil? (#'quickstart/inject-module-config "/nonexistent/config.edn")))))
