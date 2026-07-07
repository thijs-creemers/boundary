(ns boundary.cli.agents-update
  "Refresh the framework-owned sections of a project's AGENTS.md after a
   Boundary upgrade.

   `boundary new` renders AGENTS.md from a template that evolves with the
   framework (new pitfalls, conventions, modules). This command re-renders the
   marker-delimited blocks from the currently installed CLI's template and
   splices them into the project file, leaving everything the user wrote
   outside the markers untouched.

   Synced blocks:
     <!-- gen:fc-is -->               FC/IS rules
     <!-- gen:naming -->              case conventions
     <!-- gen:pitfalls -->            common pitfalls
     <!-- boundary:available-modules -->  module table

   NOT synced (project state): <!-- boundary:installed-modules -->. Rows for
   already-installed modules are re-removed from the refreshed available
   table, mirroring what `boundary add` did at install time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private synced-blocks
  ["gen:fc-is" "gen:naming" "gen:pitfalls" "boundary:available-modules"])

(defn- open-marker [block] (str "<!-- " block " -->"))
(defn- close-marker [block] (str "<!-- /" block " -->"))

(defn- block-content
  "Content between a block's markers (exclusive), or nil when absent."
  [content block]
  (let [open  (open-marker block)
        close (close-marker block)
        start (str/index-of content open)
        end   (when start (str/index-of content close start))]
    (when (and start end)
      (subs content (+ start (count open)) end))))

(defn replace-block
  "Replace the content between block markers in target with new-body.
   Returns target unchanged when the markers are missing."
  [target block new-body]
  (if (block-content target block)
    (str/replace target
                 (re-pattern (str "(?s)" (java.util.regex.Pattern/quote (open-marker block))
                                  ".*?"
                                  (java.util.regex.Pattern/quote (close-marker block))))
                 (str/re-quote-replacement
                  (str (open-marker block) new-body (close-marker block))))
    target))

(defn installed-module-names
  "Module names listed in the installed-modules block: lines like
   `- payments (`...`) — [docs](...)` or `- payments — [docs](...)`."
  [content]
  (if-let [body (block-content content "boundary:installed-modules")]
    (->> (str/split-lines body)
         (keep #(second (re-find #"^- ([a-z0-9-]+)[ (]" (str % " "))))
         set)
    #{}))

(defn remove-available-rows
  "Remove table rows for installed modules from the available-modules block,
   mirroring what `boundary add` does at install time."
  [content installed]
  (reduce (fn [c module-name]
            (str/replace c
                         (re-pattern (str "(?m)^.*\\b" (java.util.regex.Pattern/quote module-name)
                                          "\\b.*boundary add " (java.util.regex.Pattern/quote module-name)
                                          ".*\\n?"))
                         ""))
          content
          installed))

(defn- render [template substitutions]
  (reduce (fn [s [k v]] (str/replace s (str "{{" (name k) "}}") v))
          template
          substitutions))

(defn- read-template []
  (let [r (io/resource "boundary/cli/templates/AGENTS.md.tmpl")]
    (when-not r
      (throw (ex-info "AGENTS.md.tmpl not found on the CLI classpath" {})))
    (slurp r)))

(defn project-name-from-agents
  "Project name from the AGENTS.md title line, or nil."
  [content]
  (second (re-find #"(?m)^# (.+?) — Developer Reference" content)))

(defn update-agents-content
  "Pure core of the update: returns {:content new-content :updated [...] :missing [...]}."
  [current template subs]
  (let [installed (installed-module-names current)
        ;; Strip installed modules from the template's available table up
        ;; front (mirroring `boundary add`), so the block comparison below is
        ;; against what the project file should actually contain — otherwise
        ;; every run would report the available block as stale.
        rendered  (-> (render template subs)
                      (remove-available-rows installed))]
    (reduce (fn [{:keys [content updated missing]} block]
              (let [new-body (block-content rendered block)
                    old-body (block-content content block)]
                (cond
                  (nil? old-body)
                  {:content content :updated updated :missing (conj missing block)}

                  (= old-body new-body)
                  {:content content :updated updated :missing missing}

                  :else
                  {:content (replace-block content block new-body)
                   :updated (conj updated block)
                   :missing missing})))
            {:content current :updated [] :missing []}
            synced-blocks)))

(defn -main [args]
  (let [check? (some #{"--check"} args)
        f      (io/file "AGENTS.md")]
    (if-not (.exists f)
      (do (println "No AGENTS.md found in the current directory.")
          (println "Run this from a Boundary project root (created with `boundary new`).")
          (System/exit 1))
      (let [current      (slurp f)
            project-name (or (project-name-from-agents current)
                             (.getName (.getCanonicalFile (io/file "."))))
            project-ns   (str/replace project-name "-" "_")
            {:keys [content updated missing]}
            (update-agents-content current (read-template)
                                   {:project-name project-name
                                    :project-ns   project-ns})]
        (doseq [block missing]
          (println (str "  Warning: markers for '" block "' not found — block skipped")))
        (cond
          (= content current)
          (println "AGENTS.md is up to date.")

          check?
          (do (println (str "AGENTS.md is out of date. Stale blocks: " (str/join ", " updated)))
              (println "Run `boundary agents update` (or `bb agents:update`) to refresh.")
              (System/exit 1))

          :else
          (do (spit f content)
              (println (str "AGENTS.md updated. Refreshed blocks: " (str/join ", " updated)))
              (println "Sections outside the framework markers were left untouched.")))))))
