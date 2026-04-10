(ns boundary.tools.bou-15-test
  (:require [clojure.test :refer [deftest is testing]]
            [boundary.tools.bou-15 :as bou-15]))

(deftest ^:unit source-files-includes-src-and-test
  (let [paths (map str (bou-15/source-files))]
    (testing "scanner includes production source trees"
      (is (some #(re-find #"/libs/.+/src/" %) paths)))
    (testing "scanner includes test trees"
      (is (some #(re-find #"/libs/.+/test/" %) paths)))))

(deftest ^:unit scan-deprecated-usage-detects-qualified-calls
  (let [tmp-dir (doto (java.io.File/createTempFile "bou-15-usage" "")
                  (.delete)
                  (.mkdirs))
        src-dir (doto (java.io.File. tmp-dir "src") (.mkdirs))
        test-dir (doto (java.io.File. tmp-dir "test") (.mkdirs))
        src-file (java.io.File. src-dir "caller.clj")
        test-file (java.io.File. test-dir "caller_test.clj")]
    (try
      (spit src-file
            (str "(ns example.shell\n"
                 "  (:require [boundary.platform.core.http.problem-details :as pd]\n"
                 "            [boundary.search.core.index :as index]))\n"
                 "(defn f []\n"
                 "  [(pd/request->context {:uri \"/x\"})\n"
                 "   (index/build-document {} nil {} {})])\n"))
      (spit test-file
            (str "(ns example.shell-test\n"
                 "  (:require [boundary.storage.core.validation :as validation]))\n"
                 "(defn f []\n"
                 "  (validation/generate-unique-filename \"x.txt\"))\n"))
      (let [matches (bou-15/scan-deprecated-usage [src-file test-file])]
        (is (= #{"request->context" "build-document" "generate-unique-filename"}
               (set (map :symbol matches))))
        (is (= 2 (count (filter #(= :production (:category %)) matches))))
        (is (= 1 (count (filter #(= :test (:category %)) matches)))))
      (finally
        (.delete src-file)
        (.delete test-file)
        (.delete src-dir)
        (.delete test-dir)
        (.delete tmp-dir)))))
