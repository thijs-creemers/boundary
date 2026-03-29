#!/usr/bin/env bb
;; boundary-tools/src/boundary/tools/doctor.clj
;;
;; Config Doctor — rule-based validation of Boundary config files.
;;
;; Usage (via bb.edn task):
;;   bb doctor                      # Check dev environment
;;   bb doctor --env prod           # Check specific environment
;;   bb doctor --env all            # Check all environments
;;   bb doctor --ci                 # Exit non-zero on any error (CI mode)

(ns boundary.tools.doctor
  (:require [boundary.tools.ansi :refer [bold green red yellow dim]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

;; =============================================================================
;; Known valid providers
;; =============================================================================

(def known-providers
  {:boundary/logging          #{:no-op :stdout :slf4j :file}
   :boundary/metrics          #{:no-op :prometheus :datadog-statsd}
   :boundary/error-reporting  #{:no-op :sentry}
   :boundary/payment-provider #{:mock :mollie :stripe}
   :boundary/ai-service       #{:ollama :anthropic :openai :no-op}
   :boundary/cache            #{:redis :in-memory}})

;; =============================================================================
;; Pure check functions
;; =============================================================================

(defn extract-env-refs
  "Extract all #env VAR references from raw config text.
   Returns a set of env var name strings."
  [config-text]
  (->> (re-seq #"#env\s+([A-Z_][A-Z0-9_]*)" config-text)
       (map second)
       set))

(defn extract-or-defaults
  "Extract env var names that are wrapped in #or (have a default).
   Matches patterns like: #or [#env VAR default]"
  [config-text]
  (->> (re-seq #"#or\s+\[#env\s+([A-Z_][A-Z0-9_]*)\s+[^\]]" config-text)
       (map second)
       set))

(defn extract-fallback-env-refs
  "Extract env var names that appear inside :fallback blocks in config text.
   These are optional — the fallback provider only runs when the primary fails."
  [config-text]
  (let [;; Find all :fallback { ... } blocks using brace-balanced extraction
        matches (re-seq #":fallback\s*\{" config-text)]
    (if-not (seq matches)
      #{}
      ;; For each :fallback occurrence, extract the balanced block and find #env refs inside
      (loop [text config-text
             refs #{}]
        (let [idx (str/index-of text ":fallback")]
          (if-not idx
            refs
            (let [open-idx (str/index-of text "{" idx)]
              (if-not open-idx
                refs
                (let [block (loop [i (inc open-idx) depth 1 acc ""]
                              (cond
                                (>= i (count text)) acc
                                (zero? depth) acc
                                :else (let [c (nth text i)]
                                        (recur (inc i)
                                               (case c \{ (inc depth) \} (dec depth) depth)
                                               (str acc c)))))]
                  (recur (subs text (inc open-idx))
                         (into refs (map second (re-seq #"#env\s+([A-Z_][A-Z0-9_]*)" block)))))))))))))

(defn check-env-refs
  "Check that #env references without #or defaults are set in the environment.
   Env vars inside :fallback blocks are treated as warnings (optional), not errors.
   Returns a seq of check result maps."
  [config-text env-map]
  (let [all-refs      (extract-env-refs config-text)
        with-default  (extract-or-defaults config-text)
        fallback-refs (extract-fallback-env-refs config-text)
        unprotected   (set/difference all-refs with-default)
        missing       (remove #(get env-map %) unprotected)
        ;; Split into required (error) and optional/fallback (warn)
        required      (remove fallback-refs missing)
        optional      (filter fallback-refs missing)]
    (concat
     (when (seq required)
       [{:id    :env-refs
         :level :error
         :msg   (str "#env references without defaults are unset: " (str/join ", " (sort required)))
         :fix   (str "Export the missing variables:\n"
                     (str/join "\n" (map #(str "  export " % "=\"...\"") (sort required))))}])
     (when (seq optional)
       [{:id    :env-refs
         :level :warn
         :msg   (str "Optional fallback env vars not set: " (str/join ", " (sort optional)))
         :fix   (str "These are in :fallback blocks and only needed if the primary provider fails.\n"
                     "  To enable: " (str/join ", " (map #(str "export " % "=\"...\"") (sort optional))))}])
     (when (and (empty? required) (empty? optional))
       [{:id    :env-refs
         :level :pass
         :msg   "All #env references resolved or have defaults"}]))))

(defn check-providers
  "Check that :provider values in the config are known/valid.
   Accepts parsed EDN config (the :active map)."
  [active-config]
  (let [errors (for [[config-key valid-set] known-providers
                     :let [provider-val (get-in active-config [config-key :provider])]
                     :when (and provider-val (not (contains? valid-set provider-val)))]
                 {:config-key config-key
                  :value      provider-val
                  :valid      valid-set})]
    (if (seq errors)
      (mapv (fn [{:keys [config-key value valid]}]
              {:id    :providers
               :level :error
               :msg   (str config-key " has unknown provider " value)
               :fix   (str "Valid providers: " (str/join ", " (sort (map name valid))))})
            errors)
      [{:id    :providers
        :level :pass
        :msg   "All provider values are known"}])))

(defn check-jwt-secret
  "Check that JWT_SECRET is set when user module is active."
  [active-config env-map]
  (let [user-active? (some (fn [k]
                             (and (keyword? k)
                                  (str/starts-with? (name k) "user")
                                  (= (namespace k) "boundary")))
                           (keys active-config))]
    (cond
      (not user-active?)
      [{:id :jwt-secret :level :pass :msg "User module not active, JWT_SECRET not required"}]

      (get env-map "JWT_SECRET")
      [{:id :jwt-secret :level :pass :msg "JWT_SECRET is set"}]

      :else
      [{:id    :jwt-secret
        :level :error
        :msg   "JWT_SECRET not set (required by user module)"
        :fix   "export JWT_SECRET=\"your-32-char-secret\""}])))

(defn check-admin-parity
  "Check that admin entity config files exist in both dev and test."
  [dev-files test-files]
  (let [dev-names  (set (map #(.getName %) dev-files))
        test-names (set (map #(.getName %) test-files))
        dev-only   (set/difference dev-names test-names)
        test-only  (set/difference test-names dev-names)]
    (cond
      (and (empty? dev-only) (empty? test-only))
      [{:id :admin-parity :level :pass :msg "Admin entity files match between dev and test"}]

      :else
      (concat
       (when (seq dev-only)
         [{:id    :admin-parity
           :level :warn
           :msg   (str "Admin files in dev but not test: " (str/join ", " (sort dev-only)))
           :fix   "Copy missing admin entity EDN files to resources/conf/test/admin/"}])
       (when (seq test-only)
         [{:id    :admin-parity
           :level :warn
           :msg   (str "Admin files in test but not dev: " (str/join ", " (sort test-only)))
           :fix   "Copy missing admin entity EDN files to resources/conf/dev/admin/"}])))))

(defn check-prod-placeholders
  "Check for placeholder values in config text (only for prod/acc environments)."
  [config-text env-name]
  (if-not (contains? #{"prod" "acc"} env-name)
    [{:id :prod-placeholders :level :pass :msg "Placeholder check skipped (non-production env)"}]
    (let [patterns [#"company\.com" #"example\.com" #"TODO" #"CHANGEME" #"xxx" #"your-.*-here"]
          matches  (for [pat patterns
                         :let [found (re-find pat config-text)]
                         :when found]
                     (str found))]
      (if (seq matches)
        [{:id    :prod-placeholders
          :level :error
          :msg   (str "Placeholder values found in " env-name " config: " (str/join ", " matches))
          :fix   "Replace all placeholder values with real configuration"}]
        [{:id :prod-placeholders :level :pass :msg "No placeholder values found"}]))))

(defn check-wiring-requires
  "Check that wiring.clj has require entries for all active Integrant module keys."
  [wiring-text active-config]
  (let [;; Module keys that should have module-wiring requires
        module-keys (->> (keys active-config)
                         (filter keyword?)
                         (map (fn [k]
                                (let [ns-part (or (namespace k) "")
                                      nm      (name k)]
                                  (cond
                                    ;; boundary.external/* keys
                                    (str/starts-with? (str k) ":boundary.external/")
                                    "external"
                                    ;; :boundary/foo keys -> foo
                                    (= ns-part "boundary")
                                    nm
                                    :else nil))))
                         (remove nil?)
                         ;; Exclude infrastructure keys that don't have module-wiring
                         (filter #(not (contains? #{"settings" "postgresql" "sqlite" "mysql" "h2"
                                                    "http" "router" "api-versioning" "pagination"
                                                    "logging" "metrics" "error-reporting"
                                                    "http-server" "db-context"
                                                    "logging-with-stdout" "logging-with-file"
                                                    "logging-no-op" "metrics-with-prometheus"
                                                    "metrics-with-datadog"
                                                    "error-reporting-with-sentry"
                                                    ;; Config key names that don't match module names
                                                    "ai-service"        ; module is "ai", not "ai-service"
                                                    "payment-provider"  ; module is "payments", not "payment-provider"
                                                    }
                                                  %)))
                         set)
        ;; Extract module names from wiring.clj require entries
        wired-modules (->> (re-seq #"boundary\.([a-z0-9-]+)\.shell\.module-wiring" wiring-text)
                           (map second)
                           set)
        missing (set/difference module-keys wired-modules)]
    (if (seq missing)
      [{:id    :wiring-requires
        :level :warn
        :msg   (str "Active modules not wired in wiring.clj: " (str/join ", " (sort missing)))
        :fix   (str "Add requires to wiring.clj:\n"
                    (str/join "\n" (map #(str "  [boundary." % ".shell.module-wiring]") (sort missing))))}]
      [{:id :wiring-requires :level :pass :msg "All active modules wired"}])))

;; =============================================================================
;; Shell: file loading
;; =============================================================================

(defn- root-dir [] (System/getProperty "user.dir"))

(defn- config-path [env]
  (str (root-dir) "/resources/conf/" env "/config.edn"))

(defn- load-raw-config
  "Slurp the raw config text for an environment."
  [env]
  (let [path (config-path env)
        f    (io/file path)]
    (when (.exists f)
      (slurp f))))

(defn- parse-config-minimal
  "Parse config.edn with a minimal reader that replaces Aero tags with placeholders.
   Returns the parsed EDN map."
  [config-text]
  (let [;; Custom readers that handle Aero tags by returning placeholder values
        readers {'env      (fn [v] (str "ENV:" v))
                 'or       (fn [v] (if (vector? v) (last v) v))
                 'long     (fn [v] (if (number? v) v 0))
                 'merge    (fn [v] (if (vector? v) (apply merge v) v))
                 'include  (fn [_v] {})
                 'str      (fn [v] (str v))
                 'join     (fn [v] (if (vector? v) (str/join v) (str v)))
                 'keyword  (fn [v] (keyword v))
                 'ref      (fn [v] v)
                 'ig/ref   (fn [v] v)
                 'profile  (fn [v] v)}]
    (try
      (edn/read-string {:readers readers} config-text)
      (catch Exception e
        (println (yellow (str "  Warning: could not parse config.edn: " (.getMessage e))))
        (println (dim "  Some checks (providers, jwt-secret, wiring) will be skipped."))
        nil))))

(defn- list-admin-files [env]
  (let [dir (io/file (root-dir) "resources" "conf" env "admin")]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(str/ends-with? (.getName %) ".edn"))
           vec))))

(defn- load-wiring-text []
  (let [f (io/file (root-dir) "libs/platform/src/boundary/platform/shell/system/wiring.clj")]
    (when (.exists f)
      (slurp f))))

;; =============================================================================
;; Check orchestration
;; =============================================================================

(defn- extract-active-section
  "Extract the raw text of the :active block from config.edn using balanced brace matching.
   This avoids false positives from #env vars in :inactive blocks.
   Skips occurrences of :active inside ;; comments."
  [config-text]
  (let [lines (str/split-lines config-text)
        ;; Find the line with :active that is not inside a comment
        active-line-idx (first (keep-indexed
                                (fn [i line]
                                  (let [trimmed (str/trim line)]
                                    (when (and (not (str/starts-with? trimmed ";"))
                                               (str/includes? line ":active"))
                                      i)))
                                lines))]
    (if-not active-line-idx
      config-text
      ;; Walk forward from :active, counting braces to find the balanced {} block
      (let [text-from-active (str/join "\n" (subvec (vec lines) active-line-idx))
            open-idx         (str/index-of text-from-active "{")]
        (if-not open-idx
          config-text
          (loop [i     (inc open-idx)
                 depth 1]
            (cond
              (>= i (count text-from-active)) config-text ; unbalanced, fallback
              (zero? depth) (subs text-from-active 0 i)
              :else (let [c (nth text-from-active i)]
                      (recur (inc i)
                             (case c \{ (inc depth) \} (dec depth) depth))))))))))

(defn run-checks
  "Run all doctor checks for a given environment.
   Returns a seq of check result maps."
  [env _opts]
  (let [config-text (load-raw-config env)
        env-map     (into {} (System/getenv))]
    (when-not config-text
      (println (red (str "Config file not found: " (config-path env))))
      (System/exit 1))
    (let [parsed       (parse-config-minimal config-text)
          active       (or (:active parsed) {})
          active-text  (extract-active-section config-text)
          wiring-text  (or (load-wiring-text) "")
          dev-admin    (or (list-admin-files "dev") [])
          test-admin   (or (list-admin-files "test") [])]
      (concat
       (check-env-refs active-text env-map)
       (check-providers active)
       (check-jwt-secret active env-map)
       (check-admin-parity dev-admin test-admin)
       (check-prod-placeholders config-text env)
       (check-wiring-requires wiring-text active)))))

;; =============================================================================
;; Output formatting
;; =============================================================================

(defn- format-result [{:keys [id level msg fix]}]
  (let [icon (case level
               :pass (green "✓")
               :warn (yellow "⚠")
               :error (red "✗"))
        id-str (format "%-20s" (name id))]
    (str "  " icon " " id-str " " msg
         (when fix
           (str "\n" (dim (str "                       Fix: " fix)))))))

(defn- print-results [env results]
  (println)
  (println (bold (str "Boundary Config Doctor — " env)))
  (println)
  (doseq [r results]
    (println (format-result r)))
  (let [passed  (count (filter #(= :pass (:level %)) results))
        warns   (count (filter #(= :warn (:level %)) results))
        errors  (count (filter #(= :error (:level %)) results))]
    (println)
    (println (str "Summary: " (green (str passed " passed"))
                  ", " (yellow (str warns " warning" (when (not= warns 1) "s")))
                  ", " (red (str errors " error" (when (not= errors 1) "s")))))
    {:passed passed :warnings warns :errors errors}))

;; =============================================================================
;; Argument parsing
;; =============================================================================

(defn- parse-args [args]
  (loop [[flag & more :as remaining] args
         opts {:env "dev" :ci false}]
    (cond
      (empty? remaining) opts
      (or (= flag "--help") (= flag "-h")) (assoc opts :help true)
      (= flag "--ci") (recur more (assoc opts :ci true))
      (= flag "--env") (recur (rest more) (assoc opts :env (first more)))
      :else (recur more opts))))

(defn- print-help []
  (println (bold "bb doctor") " — Validate Boundary config for common mistakes")
  (println)
  (println "Usage:")
  (println "  bb doctor                  Check dev environment")
  (println "  bb doctor --env prod       Check specific environment")
  (println "  bb doctor --env all        Check all environments")
  (println "  bb doctor --ci             Exit non-zero on any error (CI mode)")
  (println)
  (println "Checks:")
  (println "  env-refs            #env vars without #or defaults are set")
  (println "  providers           Provider values are recognized")
  (println "  jwt-secret          JWT_SECRET set when user module active")
  (println "  admin-parity        Admin entity files exist in both dev and test")
  (println "  prod-placeholders   No placeholder values in prod config")
  (println "  wiring-requires     wiring.clj has requires for all active modules"))

;; =============================================================================
;; Main entry point
;; =============================================================================

(defn -main [& args]
  (let [opts (parse-args args)]
    (when (:help opts)
      (print-help)
      (System/exit 0))
    (let [envs    (if (= (:env opts) "all")
                    (let [conf-dir (io/file (root-dir) "resources" "conf")]
                      (->> (.listFiles conf-dir)
                           (filter #(.isDirectory %))
                           (map #(.getName %))
                           sort))
                    [(:env opts)])
          all-summaries (mapv (fn [env]
                                (let [results (run-checks env opts)]
                                  (print-results env results)))
                              envs)
          total-errors (reduce + (map :errors all-summaries))]
      (when (and (:ci opts) (pos? total-errors))
        (System/exit 1)))))

;; Run when executed directly (not via bb.edn task)
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
