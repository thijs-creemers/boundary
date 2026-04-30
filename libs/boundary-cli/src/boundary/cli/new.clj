(ns boundary.cli.new
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [boundary.cli.catalogue :as cat]))

(defn validate-name [n]
  (cond
    (str/blank? n)                            "Project name cannot be empty"
    (not (re-matches #"[a-z][a-z0-9]*(-[a-z0-9]+)*" n))  "Project name must be kebab-case (lowercase letters, digits, hyphens; must start with a letter)"
    :else nil))

(defn name->ns [n]
  (str/replace n "-" "_"))

(defn- render [template substitutions]
  (reduce (fn [s [k v]] (str/replace s (str "{{" (name k) "}}") v))
          template
          substitutions))

(defn- read-template [tmpl-name]
  (let [r (io/resource (str "boundary/cli/templates/" tmpl-name))]
    (when-not r
      (throw (ex-info (str "Template not found: " tmpl-name) {:name tmpl-name})))
    (slurp r)))

(defn- write-file! [dir relative-path content]
  (let [f (io/file dir relative-path)]
    (io/make-parents f)
    (spit f content)))

(defn check-directory
  "Returns :ok, :empty-exists, or :non-empty. If force? is true, always :ok."
  [dir force?]
  (let [f (io/file dir)]
    (cond
      force?              :ok
      (not (.exists f))   :ok
      (empty? (.list f))  :empty-exists
      :else               :non-empty)))

(defn generate!
  "Generate project files into dir."
  [dir project-name _opts]
  (let [project-ns (name->ns project-name)
        version    (:catalogue-version (cat/load-catalogue))
        subs       {:project-name     project-name
                    :project-ns       project-ns
                    :boundary-version version}
        files      {"deps.edn"                           "deps.edn.tmpl"
                    "bb.edn"                             "bb.edn.tmpl"
                    ".gitignore"                         "gitignore.tmpl"
                    ".env.example"                       "env.example.tmpl"
                    "CLAUDE.md"                          "CLAUDE.md.tmpl"
                    "AGENTS.md"                          "AGENTS.md.tmpl"
                    "resources/conf/dev/config.edn"      "dev-config.edn.tmpl"
                    "resources/conf/test/config.edn"     "test-config.edn.tmpl"
                    (str "src/" project-ns "/system.clj") "system.clj.tmpl"}]
    (doseq [[target tmpl] files]
      (write-file! dir target (render (read-template tmpl) subs)))))

(defn -main [args]
  (let [[project-name & flags] args
        force? (boolean (some #{"--force"} flags))]
    (when-not project-name
      (println "Usage: boundary new <project-name> [--force]")
      (System/exit 1))
    (let [err (validate-name project-name)]
      (when err
        (println (str "Error: " err))
        (System/exit 1)))
    (let [dir    (str (System/getProperty "user.dir") "/" project-name)
          status (check-directory dir force?)]
      (case status
        :non-empty
        (do (println (str "Error: Directory " project-name "/ already exists and is not empty."))
            (println "Use a different name, remove the directory, or pass --force.")
            (System/exit 1))
        :empty-exists nil
        :ok nil)
      (println (str "Creating " project-name "/..."))
      (generate! dir project-name {})
      (println (str "\n✓ Project created: " project-name "/"))
      (println "\nCore modules installed: core, observability, platform, user")
      (println "\nOptional modules available — add any with:\n")
      (doseq [{:keys [name description add-command]} (take 6 (cat/optional-modules))]
        (println (format "  %-25s %s" add-command description)))
      (println "  ... (boundary list modules for full list)")
      (println (str "\nNext:\n  cd " project-name "\n  boundary add <module>    (optional)\n  clojure -M:repl-clj")))))
