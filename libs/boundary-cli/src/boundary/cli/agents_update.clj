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
            [clojure.string :as str]
            [boundary.cli.templates :as templates]))

(def ^:private synced-blocks
  ["gen:fc-is" "gen:naming" "gen:pitfalls" "boundary:available-modules"])

(defn- open-marker [block] (str "<!-- " block " -->"))
(defn- close-marker [block] (str "<!-- /" block " -->"))

(defn- block-bounds
  "[start-of-body end-of-body] indices of the FIRST marker pair, or nil."
  [content block]
  (let [open  (open-marker block)
        close (close-marker block)
        start (str/index-of content open)
        end   (when start (str/index-of content close start))]
    (when (and start end)
      [(+ start (count open)) end])))

(defn- block-content
  "Content between the first marker pair (exclusive), or nil when absent."
  [content block]
  (when-let [[start end] (block-bounds content block)]
    (subs content start end)))

(defn replace-block
  "Replace the content of the FIRST marker pair in target with new-body via
   index splicing — a user-duplicated marker elsewhere in the document is
   left alone. Returns target unchanged when the markers are missing."
  [target block new-body]
  (if-let [[start end] (block-bounds target block)]
    (str (subs target 0 start) new-body (subs target end))
    target))

(defn- update-block
  "Apply f to the body of the first marker pair; no-op when markers absent."
  [content block f]
  (if-let [body (block-content content block)]
    (replace-block content block (f body))
    content))

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
  "Remove table rows for installed modules from the available-modules block
   ONLY — prose elsewhere mentioning `boundary add <name>` is never touched.
   Keeps the rows removed that `boundary add` removed at install time (it
   uses the same row pattern, though applied file-wide)."
  [content installed]
  (update-block content "boundary:available-modules"
                (fn [body]
                  (reduce (fn [b module-name]
                            (str/replace b (templates/module-row-pattern module-name) ""))
                          body
                          installed))))

(defn project-name-from-agents
  "Project name from the AGENTS.md title line, or nil."
  [content]
  (second (re-find #"(?m)^# (.+?) — Developer Reference" content)))

(defn update-agents-content
  "Pure core of the update: returns {:content new-content :updated [...] :missing [...]}."
  [current template substitutions]
  (let [installed (installed-module-names current)
        ;; Strip installed modules from the template's available table up
        ;; front (mirroring `boundary add`), so the block comparison below is
        ;; against what the project file should actually contain — otherwise
        ;; every run would report the available block as stale.
        rendered  (-> (templates/render template substitutions)
                      (remove-available-rows installed))]
    (reduce (fn [{:keys [content updated missing]} block]
              (let [new-body (block-content rendered block)
                    old-body (block-content content block)]
                (cond
                  ;; Markers absent in the project file — or in the template
                  ;; itself: splicing a nil body would silently empty the
                  ;; project's block, so both count as "cannot sync, skip".
                  (or (nil? old-body) (nil? new-body))
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
            (update-agents-content current (templates/read-template "AGENTS.md.tmpl")
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
