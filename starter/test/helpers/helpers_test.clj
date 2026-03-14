(ns helpers-test
  "Unit tests for template loading and merging logic"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]))

;; Load helpers from scripts (Babashka compatible)
(load-file "scripts/helpers.clj")
(require '[helpers :as helpers])

;; =============================================================================
;; Deep Merge Tests
;; =============================================================================

(deftest deep-merge-test
  (testing "Merge simple maps"
    (is (= (helpers/deep-merge {:a 1} {:b 2})
           {:a 1 :b 2})))

  (testing "Override primitive values (right wins)"
    (is (= (helpers/deep-merge {:port 3000} {:port 8080})
           {:port 8080})))

  (testing "Deep merge nested maps"
    (is (= (helpers/deep-merge
            {:a 1 :b {:c 2}}
            {:b {:d 3} :e 4})
           {:a 1 :b {:c 2 :d 3} :e 4})))

  (testing "Concatenate and deduplicate vectors"
    (is (= (helpers/deep-merge
            {:libs [:a :b]}
            {:libs [:c :d]})
           {:libs [:a :b :c :d]})))

  (testing "Deduplicate when vectors have common elements"
    (is (= (helpers/deep-merge
            {:libs [:a :b :c]}
            {:libs [:b :c :d]})
           {:libs [:a :b :c :d]})))

  (testing "Merge three maps"
    (is (= (helpers/deep-merge
            {:a 1}
            {:b 2}
            {:c 3})
           {:a 1 :b 2 :c 3})))

  (testing "Nested vector concatenation"
    (is (= (helpers/deep-merge
            {:config {:features [:auth]}}
            {:config {:features [:admin]}})
           {:config {:features [:auth :admin]}}))))

;; =============================================================================
;; Template Loading Tests
;; =============================================================================

(deftest load-template-test
  (testing "Load base template successfully"
    (let [base (helpers/load-template "_base")]
      (is (map? base))
      (is (contains? base :meta))
      (is (contains? base :dependencies))
      (is (contains? base :boundary-libs))
      (is (contains? base :config))
      (is (vector? (:boundary-libs base)))
      (is (map? (:dependencies base)))))

  (testing "Base template has expected structure"
    (let [base (helpers/load-template "_base")]
      (is (= (get-in base [:meta :name]) "Boundary Base Template"))
      (is (some #(= % :core) (:boundary-libs base)))
      (is (some #(= % :platform) (:boundary-libs base)))
      (is (some #(= % :observability) (:boundary-libs base)))
      (is (contains? (:config base) :http))
      (is (contains? (:config base) :db))))

  (testing "Load non-existent template throws"
    (is (thrown? Exception (helpers/load-template "nonexistent-template")))))

;; =============================================================================
;; Template Extension Resolution Tests
;; =============================================================================

(deftest resolve-extends-test
  (testing "Resolve template without extends returns as-is"
    (let [base (helpers/load-template "_base")
          resolved (helpers/resolve-extends base)]
      (is (= (:meta resolved) (:meta base)))
      (is (= (:boundary-libs resolved) (:boundary-libs base)))))

  (testing "Resolve extends is idempotent (no extends)"
    (let [base (helpers/load-template "_base")
          once (helpers/resolve-extends base)
          twice (helpers/resolve-extends once)]
      (is (= once twice)))))

;; =============================================================================
;; Deps.edn Generation Tests
;; =============================================================================

(deftest boundary-lib->dep-test
  (testing "Convert boundary library keyword to dep"
    (let [[artifact dep] (helpers/boundary-lib->dep :user)]
      (is (= artifact 'boundary/user))
      (is (= (:git/url dep) "https://github.com/thijs-creemers/boundary"))
      (is (string? (:git/sha dep)))
      (is (= (:deps/root dep) "libs/user"))))

  (testing "Convert core library"
    (let [[artifact dep] (helpers/boundary-lib->dep :core)]
      (is (= artifact 'boundary/core))
      (is (= (:git/url dep) "https://github.com/thijs-creemers/boundary"))
      (is (string? (:git/sha dep)))
      (is (= (:deps/root dep) "libs/core")))))

(deftest dependency->dep-test
  (testing "Convert maven dependency"
    (is (= (helpers/dependency->dep
            [:clojure {:group "org.clojure/clojure" :version "1.12.4"}])
           ['org.clojure/clojure {:mvn/version "1.12.4"}])))

  (testing "Convert git dependency"
    (is (= (helpers/dependency->dep
            [:tools-build {:group "io.github.clojure/tools.build"
                           :git/tag "v0.10.11"
                           :git/sha "c6c670a4"}])
           ['io.github.clojure/tools.build {:git/tag "v0.10.11"
                                            :git/sha "c6c670a4"}]))))

(deftest template->deps-edn-test
  (testing "Generate deps.edn from base template"
    (let [base (helpers/load-template "_base")
          deps (helpers/template->deps-edn base)]
      (is (map? deps))
      (is (vector? (:paths deps)))
      (is (map? (:deps deps)))
      (is (map? (:aliases deps)))

      ;; Check paths
      (is (some #(= % "src") (:paths deps)))
      (is (some #(= % "resources") (:paths deps)))

      ;; Check core deps
      (is (contains? (:deps deps) 'org.clojure/clojure))
      (is (contains? (:deps deps) 'integrant/integrant))

      ;; Check boundary libs
      (is (contains? (:deps deps) 'boundary/core))
      (is (contains? (:deps deps) 'boundary/platform))
      (is (contains? (:deps deps) 'boundary/observability))

      ;; Check aliases
      (is (contains? (:aliases deps) :repl-clj))
      (is (contains? (:aliases deps) :test))
      (is (contains? (:aliases deps) :build))))

  (testing "Database driver selection - SQLite"
    (let [base (helpers/load-template "_base")
          deps (helpers/template->deps-edn base {:db-choice :sqlite})]
      (is (contains? (:deps deps) 'org.xerial/sqlite-jdbc))))

  (testing "Database driver selection - PostgreSQL"
    (let [base (helpers/load-template "_base")
          deps (helpers/template->deps-edn base {:db-choice :postgres})]
      (is (contains? (:deps deps) 'org.postgresql/postgresql))))

  (testing "Database driver selection - Both"
    (let [base (helpers/load-template "_base")
          deps (helpers/template->deps-edn base {:db-choice :both})]
      (is (contains? (:deps deps) 'org.xerial/sqlite-jdbc))
      (is (contains? (:deps deps) 'org.postgresql/postgresql)))))

;; =============================================================================
;; Config.edn Generation Tests
;; =============================================================================

(deftest template->config-edn-test
  (testing "Extract config from base template"
    (let [base (helpers/load-template "_base")
          config (helpers/template->config-edn base)]
      (is (map? config))
      (is (contains? config :http))
      (is (contains? config :db))
      (is (contains? config :logging))

      (is (= (get-in config [:http :port]) 3000))
      (is (= (get-in config [:db :type]) :sqlite))
      (is (= (get-in config [:logging :level]) :info)))))

;; =============================================================================
;; Environment Variables Generation Tests
;; =============================================================================

(deftest template->env-vars-test
  (testing "Generate .env content from base template"
    (let [base (helpers/load-template "_base")
          env-content (helpers/template->env-vars base)]
      (is (string? env-content))
      (is (clojure.string/includes? env-content "# Boundary Framework"))
      (is (clojure.string/includes? env-content "# Optional Environment Variables")))))

;; =============================================================================
;; README Sections Generation Tests
;; =============================================================================

(deftest template->readme-sections-test
  (testing "Extract README sections from base template"
    (let [base (helpers/load-template "_base")
          sections (helpers/template->readme-sections base)]
      (is (map? sections))
      (is (contains? sections :features))
      (is (contains? sections :next-steps))
      (is (string? (:features sections)))
      (is (string? (:next-steps sections)))
      (is (clojure.string/includes? (:features sections) "✅"))
      (is (clojure.string/includes? (:next-steps sections) "1.")))))

;; =============================================================================
;; Validation Tests
;; =============================================================================

(deftest validate-template-test
  (testing "Valid template passes validation"
    (let [base (helpers/load-template "_base")]
      (is (= (helpers/validate-template base) base))))

  (testing "Invalid template throws"
    (is (thrown? Exception
                 (helpers/validate-template {:meta {:name "Incomplete"}})))))

;; =============================================================================
;; Template Discovery Tests
;; =============================================================================

(deftest list-available-templates-test
  (testing "List available templates"
    (let [templates (helpers/list-available-templates)]
      (is (vector? templates))
      ;; _base should be excluded
      (is (not (some #(= % "_base") templates))))))

;; =============================================================================
;; Pretty Printing Tests
;; =============================================================================

(deftest pprint-edn-test
  (testing "Pretty print EDN to string"
    (let [data {:foo 1 :bar {:baz 2}}
          output (helpers/pprint-edn data)]
      (is (string? output))
      (is (str/includes? output ":foo"))
      (is (str/includes? output ":bar")))))

;; =============================================================================
;; Template Extension Chain Tests
;; =============================================================================

(deftest template-extension-minimal-test
  (testing "Minimal template extends _base correctly"
    (let [minimal (helpers/load-template "minimal")
          resolved (helpers/resolve-extends minimal)]
      ;; Check metadata
      (is (= "Minimal Boundary Project" (get-in minimal [:meta :name])))
      (is (= :_base (get-in minimal [:meta :extends])))

      ;; Resolved template should have base dependencies
      (is (contains? (:dependencies resolved) :clojure))
      (is (contains? (:dependencies resolved) :integrant))

      ;; Should inherit boundary-libs from _base
      (is (= [:core :observability :platform] (:boundary-libs resolved)))

      ;; Should have base config
      (is (= 3000 (get-in resolved [:config :http :port])))
      (is (= :sqlite (get-in resolved [:config :db :type]))))))

(deftest template-extension-web-app-test
  (testing "Web-app template extends minimal (and transitively _base)"
    (let [web-app (helpers/load-template "web-app")
          resolved (helpers/resolve-extends web-app)]
      ;; Check metadata
      (is (= "Web Application Template" (get-in web-app [:meta :name])))
      (is (= :minimal (get-in web-app [:meta :extends])))

      ;; Should have both base and web-app boundary libs
      (is (some #(= :core %) (:boundary-libs resolved)))
      (is (some #(= :user %) (:boundary-libs resolved)))
      (is (some #(= :admin %) (:boundary-libs resolved)))

      ;; Should have web-app specific dependencies
      (is (contains? (:dependencies resolved) :hiccup))
      (is (contains? (:dependencies resolved) :ring-defaults))

      ;; Should have auth config with env placeholder
      (is (= :env/JWT_SECRET (get-in resolved [:config :auth :jwt-secret])))
      (is (= true (get-in resolved [:config :auth :enabled])))

      ;; Should have required JWT_SECRET env var
      (is (some #(= "JWT_SECRET" %) (get-in resolved [:env-vars :required])))

      ;; Should have migrations
      (is (= 2 (count (:migrations resolved)))))))

(deftest template-extension-saas-test
  (testing "SaaS template extends web-app (triple inheritance chain)"
    (let [saas (helpers/load-template "saas")
          resolved (helpers/resolve-extends saas)]
      ;; Check metadata
      (is (= "SaaS Application Template" (get-in saas [:meta :name])))
      (is (= :web-app (get-in saas [:meta :extends])))

      ;; Should have all boundary libs (base + web-app + saas)
      (is (some #(= :core %) (:boundary-libs resolved)))
      (is (some #(= :user %) (:boundary-libs resolved)))
      (is (some #(= :storage %) (:boundary-libs resolved)))
      (is (some #(= :cache %) (:boundary-libs resolved)))
      (is (some #(= :jobs %) (:boundary-libs resolved)))
      (is (some #(= :email %) (:boundary-libs resolved)))
      (is (some #(= :tenant %) (:boundary-libs resolved)))

      ;; Should have SaaS-specific dependencies
      (is (contains? (:dependencies resolved) :redis))
      (is (contains? (:dependencies resolved) :aws-s3))

      ;; Should have all config sections (base + web-app + saas)
      (is (contains? (:config resolved) :http))      ; from _base
      (is (contains? (:config resolved) :auth))      ; from web-app
      (is (contains? (:config resolved) :storage))   ; from saas
      (is (contains? (:config resolved) :email))     ; from saas

      ;; Should have storage config with env placeholders
      (is (= :env/S3_BUCKET (get-in resolved [:config :storage :s3-bucket])))

      ;; Should have all migrations (6 total)
      (is (= 6 (count (:migrations resolved))))
      (is (some #(str/includes? % "create_users") (:migrations resolved)))
      (is (some #(str/includes? % "create_tenants") (:migrations resolved))))))

;; =============================================================================
;; Aero Tag Conversion Tests
;; =============================================================================

(deftest config-aero-tag-conversion-test
  (testing "Convert :env/VAR_NAME to #env VAR_NAME in config output"
    (let [web-app (helpers/resolve-extends (helpers/load-template "web-app"))
          config (helpers/template->config-edn web-app)
          config-str (helpers/config->aero-string config)]
      ;; Should convert :env/JWT_SECRET to #env JWT_SECRET
      (is (str/includes? config-str "#env JWT_SECRET"))
      (is (not (str/includes? config-str ":env/JWT_SECRET")))))

  (testing "Convert multiple env vars in saas config"
    (let [saas (helpers/resolve-extends (helpers/load-template "saas"))
          config (helpers/template->config-edn saas)
          config-str (helpers/config->aero-string config)]
      ;; Should have multiple #env tags
      (is (str/includes? config-str "#env SMTP_HOST"))
      (is (str/includes? config-str "#env SMTP_PORT"))
      (is (str/includes? config-str "#env S3_BUCKET"))
      (is (str/includes? config-str "#env REDIS_URL"))

      ;; Should not have any :env/ keywords left
      (is (not (str/includes? config-str ":env/"))))))

;; =============================================================================
;; Deps.edn Generation with Database Choice Tests
;; =============================================================================

(deftest deps-generation-db-choice-test
  (testing "Generate deps with SQLite driver"
    (let [minimal (helpers/resolve-extends (helpers/load-template "minimal"))
          deps (helpers/template->deps-edn minimal {:db-choice :sqlite})]
      (is (contains? (:deps deps) 'org.xerial/sqlite-jdbc))
      (is (not (contains? (:deps deps) 'org.postgresql/postgresql)))))

  (testing "Generate deps with PostgreSQL driver"
    (let [web-app (helpers/resolve-extends (helpers/load-template "web-app"))
          deps (helpers/template->deps-edn web-app {:db-choice :postgres})]
      (is (contains? (:deps deps) 'org.postgresql/postgresql))
      (is (not (contains? (:deps deps) 'org.xerial/sqlite-jdbc)))))

  (testing "Generate deps with both drivers"
    (let [saas (helpers/resolve-extends (helpers/load-template "saas"))
          deps (helpers/template->deps-edn saas {:db-choice :both})]
      (is (contains? (:deps deps) 'org.xerial/sqlite-jdbc))
      (is (contains? (:deps deps) 'org.postgresql/postgresql)))))

;; =============================================================================
;; README Sections Generation Tests
;; =============================================================================

(deftest readme-sections-generation-test
  (testing "Minimal template README sections"
    (let [minimal (helpers/resolve-extends (helpers/load-template "minimal"))
          sections (helpers/template->readme-sections minimal)]
      ;; Should have features from both _base and minimal
      (is (str/includes? (:features sections) "Functional Core"))
      (is (str/includes? (:features sections) "SQLite database"))

      ;; Should have numbered next steps
      (is (str/includes? (:next-steps sections) "1."))
      (is (str/includes? (:next-steps sections) "bb scaffold"))))

  (testing "Web-app template README sections"
    (let [web-app (helpers/resolve-extends (helpers/load-template "web-app"))
          sections (helpers/template->readme-sections web-app)]
      ;; Should have web-app specific features
      (is (str/includes? (:features sections) "JWT-based authentication"))
      (is (str/includes? (:features sections) "admin CRUD UI"))

      ;; Should have emojis
      (is (str/includes? (:features sections) "🔐"))
      (is (str/includes? (:features sections) "👤"))))

  (testing "SaaS template README sections"
    (let [saas (helpers/resolve-extends (helpers/load-template "saas"))
          sections (helpers/template->readme-sections saas)]
      ;; Should have SaaS-specific features
      (is (str/includes? (:features sections) "Multi-tenancy"))
      (is (str/includes? (:features sections) "File storage"))
      (is (str/includes? (:features sections) "Background job"))
      (is (str/includes? (:features sections) "email")))))
