(ns boundary.cli.templates
  "Shared template helpers for the boundary CLI: template loading, {{var}}
   rendering, and the module-row pattern used to keep AGENTS.md's available-
   modules table in sync (`boundary add` removes a row at install time;
   `boundary agents update` keeps it removed on refresh)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn render
  "Replace {{key}} placeholders in template with substitution values."
  [template substitutions]
  (reduce (fn [s [k v]] (str/replace s (str "{{" (name k) "}}") v))
          template
          substitutions))

(defn read-template
  "Slurp a template from the CLI's resources. Throws when missing."
  [tmpl-name]
  (let [r (io/resource (str "boundary/cli/templates/" tmpl-name))]
    (when-not r
      (throw (ex-info (str "Template not found: " tmpl-name)
                      {:type :internal-error
                       :template tmpl-name})))
    (slurp r)))

(defn module-row-pattern
  "Regex matching a module's row in the available-modules table:
   a line naming the module that ends in its `boundary add <name>` command.
   Kebab-aware lookarounds on both sides of the name (plain \\b treats `-`
   as a boundary) so `search` never matches a `search-advanced` row."
  [module-name]
  (let [q     (java.util.regex.Pattern/quote module-name)
        left  "(?<![a-z0-9-])"
        right "(?![a-z0-9-])"]
    (re-pattern (str "(?m)^.*" left q right ".*boundary add " q right ".*\\n?"))))
