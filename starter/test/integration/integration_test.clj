(ns integration-test
  "Integration tests for project generation.
   Tests that all templates can be generated and produce valid files."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;; Load file generators
(load-file "scripts/file_generators.clj")
(load-file "scripts/helpers.clj")
(require '[file-generators :as file-generators]
         '[helpers :as helpers])

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-projects-dir "/tmp/boundary-integration-tests")

(defn cleanup-test-dir
  "Clean up test directory before and after tests."
  [f]
  (when (.exists (io/file test-projects-dir))
    (shell/sh "rm" "-rf" test-projects-dir))
  (.mkdirs (io/file test-projects-dir))
  (f)
  ;; Uncomment to clean up after tests
  ;; (shell/sh "rm" "-rf" test-projects-dir)
  )

(use-fixtures :once cleanup-test-dir)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn file-exists? [dir filename]
  (.exists (io/file dir filename)))

(defn file-contains? [dir filename pattern]
  (when-let [content (try (slurp (io/file dir filename))
                          (catch Exception _ nil))]
    (str/includes? content pattern)))

(defn directory-exists? [dir dirname]
  (let [path (io/file dir dirname)]
    (and (.exists path) (.isDirectory path))))

;; =============================================================================
;; Minimal Template Tests
;; =============================================================================

(deftest generate-minimal-project-test
  (testing "Generate minimal template project"
    (let [output-dir (str test-projects-dir "/minimal-test")
          template (helpers/resolve-extends (helpers/load-template "minimal"))
          result (file-generators/generate-project! template output-dir "minimal-test" {:db-choice :sqlite})]

      ;; Check generation succeeded
      (is (true? (:success result)))
      (is (= "minimal-test" (:project-name result)))
      (is (= "Minimal Boundary Project" (:template-name result)))

      ;; Check directory structure
      (is (directory-exists? output-dir "src/boundary"))
      (is (directory-exists? output-dir "test/boundary"))
      (is (directory-exists? output-dir "resources/conf/dev"))

      ;; Check core files exist
      (is (file-exists? output-dir "deps.edn"))
      (is (file-exists? output-dir "build.clj"))
      (is (file-exists? output-dir "README.md"))
      (is (file-exists? output-dir ".gitignore"))
      (is (file-exists? output-dir ".env.example"))
      (is (file-exists? output-dir "resources/conf/dev/config.edn"))
      (is (file-exists? output-dir "resources/conf/dev/system.clj"))
      (is (file-exists? output-dir "src/boundary/app.clj"))
      (is (file-exists? output-dir "test/boundary/app_test.clj"))

      ;; Check deps.edn content
      (is (file-contains? output-dir "deps.edn" "org.clojure/clojure"))
      (is (file-contains? output-dir "deps.edn" "boundary/core"))
      (is (file-contains? output-dir "deps.edn" "boundary/platform"))
      (is (file-contains? output-dir "deps.edn" "org.xerial/sqlite-jdbc"))

      ;; Check config.edn content
      (is (file-contains? output-dir "resources/conf/dev/config.edn" ":http"))
      (is (file-contains? output-dir "resources/conf/dev/config.edn" ":db"))

      ;; Check README content
      (is (file-contains? output-dir "README.md" "Minimal Boundary Project"))
      (is (file-contains? output-dir "README.md" "Quick Start"))

      ;; Check .gitignore
      (is (file-contains? output-dir ".gitignore" ".cpcache"))
      (is (file-contains? output-dir ".gitignore" "target/"))
      (is (file-contains? output-dir ".gitignore" ".env")))))

;; =============================================================================
;; Web-App Template Tests
;; =============================================================================

(deftest generate-web-app-project-test
  (testing "Generate web-app template project"
    (let [output-dir (str test-projects-dir "/web-app-test")
          template (helpers/resolve-extends (helpers/load-template "web-app"))
          result (file-generators/generate-project! template output-dir "web-app-test" {:db-choice :postgres})]

      ;; Check generation succeeded
      (is (true? (:success result)))
      (is (= "Web Application Template" (:template-name result)))

      ;; Check files exist
      (is (file-exists? output-dir "deps.edn"))
      (is (file-exists? output-dir ".env.example"))

      ;; Check deps.edn has web-app libraries
      (is (file-contains? output-dir "deps.edn" "boundary/user"))
      (is (file-contains? output-dir "deps.edn" "boundary/admin"))
      (is (file-contains? output-dir "deps.edn" "hiccup/hiccup"))
      (is (file-contains? output-dir "deps.edn" "org.postgresql/postgresql"))
      (is (not (file-contains? output-dir "deps.edn" "org.xerial/sqlite-jdbc"))) ;; postgres only

      ;; Check config has auth section
      (is (file-contains? output-dir "resources/conf/dev/config.edn" ":auth"))
      (is (file-contains? output-dir "resources/conf/dev/config.edn" "#env JWT_SECRET"))

      ;; Check .env.example has JWT_SECRET
      (is (file-contains? output-dir ".env.example" "JWT_SECRET="))

      ;; Check README mentions auth
      (is (file-contains? output-dir "README.md" "authentication")))))

;; =============================================================================
;; SaaS Template Tests
;; =============================================================================

(deftest generate-saas-project-test
  (testing "Generate saas template project"
    (let [output-dir (str test-projects-dir "/saas-test")
          template (helpers/resolve-extends (helpers/load-template "saas"))
          result (file-generators/generate-project! template output-dir "saas-test" {:db-choice :both})]

      ;; Check generation succeeded
      (is (true? (:success result)))
      (is (= "SaaS Application Template" (:template-name result)))

      ;; Check files exist
      (is (file-exists? output-dir "deps.edn"))

      ;; Check deps.edn has saas libraries
      (is (file-contains? output-dir "deps.edn" "boundary/storage"))
      (is (file-contains? output-dir "deps.edn" "boundary/cache"))
      (is (file-contains? output-dir "deps.edn" "boundary/jobs"))
      (is (file-contains? output-dir "deps.edn" "boundary/email"))
      (is (file-contains? output-dir "deps.edn" "boundary/tenant"))

      ;; Check both database drivers included
      (is (file-contains? output-dir "deps.edn" "org.xerial/sqlite-jdbc"))
      (is (file-contains? output-dir "deps.edn" "org.postgresql/postgresql"))

      ;; Check config has saas sections
      (is (file-contains? output-dir "resources/conf/dev/config.edn" ":storage"))
      (is (file-contains? output-dir "resources/conf/dev/config.edn" ":email"))
      (is (file-contains? output-dir "resources/conf/dev/config.edn" ":cache"))
      (is (file-contains? output-dir "resources/conf/dev/config.edn" "#env SMTP_HOST"))

      ;; Check .env.example has saas vars
      (is (file-contains? output-dir ".env.example" "SMTP_HOST="))
      (is (file-contains? output-dir ".env.example" "SMTP_PORT="))
      (is (file-contains? output-dir ".env.example" "EMAIL_FROM="))

      ;; Check README mentions saas features
      (is (file-contains? output-dir "README.md" "Multi-tenancy"))
      (is (file-contains? output-dir "README.md" "File storage")))))

;; =============================================================================
;; File Structure Tests
;; =============================================================================

(deftest all-templates-have-consistent-structure-test
  (testing "All templates generate consistent file structure"
    (let [templates ["minimal" "web-app" "saas"]
          required-files ["deps.edn" "build.clj" "README.md" ".gitignore"
                          ".env.example" "src/boundary/app.clj"
                          "test/boundary/app_test.clj"
                          "resources/conf/dev/config.edn"
                          "resources/conf/dev/system.clj"]]
      (doseq [template-name templates]
        (let [output-dir (str test-projects-dir "/" template-name "-structure-test")
              template (helpers/resolve-extends (helpers/load-template template-name))]
          (file-generators/generate-project! template output-dir (str template-name "-test") {})

          ;; Check all required files exist
          (doseq [file required-files]
            (is (file-exists? output-dir file)
                (str template-name " should have " file))))))))

;; =============================================================================
;; Database Driver Selection Tests
;; =============================================================================

(deftest database-driver-selection-test
  (testing "Database driver selection works correctly"
    (let [template (helpers/resolve-extends (helpers/load-template "minimal"))]

      ;; Test SQLite only
      (let [output-dir (str test-projects-dir "/db-sqlite-test")]
        (file-generators/generate-project! template output-dir "db-test" {:db-choice :sqlite})
        (is (file-contains? output-dir "deps.edn" "org.xerial/sqlite-jdbc"))
        (is (not (file-contains? output-dir "deps.edn" "org.postgresql/postgresql"))))

      ;; Test PostgreSQL only
      (let [output-dir (str test-projects-dir "/db-postgres-test")]
        (file-generators/generate-project! template output-dir "db-test" {:db-choice :postgres})
        (is (file-contains? output-dir "deps.edn" "org.postgresql/postgresql"))
        (is (not (file-contains? output-dir "deps.edn" "org.xerial/sqlite-jdbc"))))

      ;; Test both drivers
      (let [output-dir (str test-projects-dir "/db-both-test")]
        (file-generators/generate-project! template output-dir "db-test" {:db-choice :both})
        (is (file-contains? output-dir "deps.edn" "org.xerial/sqlite-jdbc"))
        (is (file-contains? output-dir "deps.edn" "org.postgresql/postgresql"))))))

;; =============================================================================
;; Aero Tag Preservation Tests
;; =============================================================================

(deftest aero-tag-preservation-test
  (testing "Aero #env tags are preserved in config.edn"
    (let [output-dir (str test-projects-dir "/aero-test")
          template (helpers/resolve-extends (helpers/load-template "web-app"))]
      (file-generators/generate-project! template output-dir "aero-test" {})

      ;; Check #env tags are in output (not :env/ keywords)
      (is (file-contains? output-dir "resources/conf/dev/config.edn" "#env JWT_SECRET"))
      (is (not (file-contains? output-dir "resources/conf/dev/config.edn" ":env/JWT_SECRET"))))))
