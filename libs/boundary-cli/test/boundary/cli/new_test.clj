(ns boundary.cli.new-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.cli.new :as new]))

(deftest validate-name-test
  (testing "valid kebab-case names are accepted"
    (is (nil? (new/validate-name "my-app")))
    (is (nil? (new/validate-name "myapp")))
    (is (nil? (new/validate-name "my-app-2"))))

  (testing "invalid names return an error string"
    (is (string? (new/validate-name "My-App")))    ; uppercase
    (is (string? (new/validate-name "123app")))    ; starts with digit
    (is (string? (new/validate-name "my.app")))    ; dot
    (is (string? (new/validate-name "")))           ; empty
    (is (string? (new/validate-name "my_app")))    ; underscore not allowed in project name
    (is (string? (new/validate-name "my-")))       ; trailing hyphen
    (is (string? (new/validate-name "my--app")))))  ; double hyphen

(deftest name->ns-test
  (testing "converts hyphens to underscores"
    (is (= "my_app" (new/name->ns "my-app")))
    (is (= "myapp" (new/name->ns "myapp")))
    (is (= "my_long_name" (new/name->ns "my-long-name")))))

(deftest generate-project-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-test-" (System/currentTimeMillis))]
    (try
      (testing "creates project directory"
        (new/generate! tmp "test-proj" {})
        (is (.exists (io/file tmp))))

      (testing "generates required files"
        (doseq [f ["deps.edn" "bb.edn" ".gitignore" ".env" ".env.example" "tests.edn"
                   "CLAUDE.md" "AGENTS.md"
                   "resources/conf/dev/config.edn"
                   "resources/conf/test/config.edn"
                   "src/boundary/config.clj"
                   "dev/user.clj"
                   "src/test_proj/system.clj"]]
          (is (.exists (io/file tmp f)) (str "Missing: " f))))

      (testing ".env has a generated JWT_SECRET (no unreplaced placeholder)"
        (let [content (slurp (io/file tmp ".env"))]
          (is (str/includes? content "JWT_SECRET="))
          (is (not (str/includes? content "{{jwt-secret}}")))))

      (testing "substitutes project name in CLAUDE.md"
        (let [content (slurp (io/file tmp "CLAUDE.md"))]
          (is (str/includes? content "test-proj"))
          (is (not (str/includes? content "{{project-name}}")))))

      (testing "sentinel comments are present in AGENTS.md"
        (let [content (slurp (io/file tmp "AGENTS.md"))]
          (is (str/includes? content "<!-- boundary:available-modules -->"))
          (is (str/includes? content "<!-- /boundary:available-modules -->"))
          (is (str/includes? content "<!-- boundary:installed-modules -->"))
          (is (str/includes? content "<!-- /boundary:installed-modules -->"))))
      (finally
        ;; cleanup
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))

(deftest directory-exists-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-exists-test")]
    (io/make-parents (io/file tmp "dummy.txt"))
    (spit (io/file tmp "dummy.txt") "x")
    (try
      (testing "non-empty directory without --force exits with error"
        (let [result (new/check-directory tmp false)]
          (is (= :non-empty result))))

      (testing "--force allows non-empty directory"
        (let [result (new/check-directory tmp true)]
          (is (= :ok result))))
      (finally
        (doseq [f (reverse (file-seq (io/file tmp)))]
          (.delete f))))))

(deftest not-a-directory-test
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/boundary-file-test-" (System/currentTimeMillis))]
    (spit (io/file tmp) "x")
    (try
      (testing "existing regular file returns :not-a-dir"
        (is (= :not-a-dir (new/check-directory tmp false))))
      (finally
        (.delete (io/file tmp))))))
