#!/usr/bin/env bb
;; scripts/agents_gen.clj
;; Deterministic generator for the framework AGENTS.md and the downstream
;; AGENTS.md.tmpl, from resources/agents/knowledge.edn + modules-catalogue.edn.
;; Usage:
;;   bb agents:gen            ; write both targets
;;   bb agents:gen --check    ; verify in sync + module-source valid; non-zero on drift
(ns agents-gen
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn splice-region
  "Replace the content between <!-- gen:SECTION --> and <!-- /gen:SECTION -->
   with body (markers preserved, body placed on its own lines). Throws if a
   marker is missing."
  [content section body]
  (let [open  (str "<!-- gen:" section " -->")
        close (str "<!-- /gen:" section " -->")
        oi    (str/index-of content open)
        ci    (str/index-of content close)]
    (when (or (nil? oi) (nil? ci))
      (throw (ex-info "gen marker not found" {:section section})))
    (str (subs content 0 (+ oi (count open)))
         "\n" body "\n"
         (subs content ci))))

(defn- sub-ns [s ns-token] (str/replace s "{{ns}}" ns-token))

(defn render-fc-is
  "Render the FC/IS layer rules section as markdown. ns-token replaces {{ns}}."
  [{:keys [layers rules ports-required example]} ns-token]
  (let [arrow (fn [{:keys [from to allowed reason]}]
                (format "| %s → %s | %s |"
                        (str/capitalize (name from))
                        (str/capitalize (name to))
                        (if allowed "✅ allowed"
                            (str "❌ NEVER — " reason))))]
    (str "| Direction | Allowed? |\n"
         "|-----------|----------|\n"
         (str/join "\n" (map arrow layers)) "\n\n"
         (when ports-required "Every module MUST define `ports.clj`.\n\n")
         (str/join "\n" (map #(str "- " %) rules)) "\n\n"
         "```clojure\n" (sub-ns example ns-token) "\n```")))

(defn render-pitfalls
  "Render pitfalls whose :surfaces contains `surface`. ns-token replaces {{ns}}.
   Output order follows the input vector (deterministic). An optional :example is
   rendered as a fenced clojure block after the Fix line."
  [pitfalls surface ns-token]
  (->> pitfalls
       (filter #(contains? (:surfaces %) surface))
       (map-indexed
        (fn [i {:keys [title symptom cause fix example]}]
          (sub-ns
           (str (format "### %d. %s\n\n- **Symptom:** %s\n- **Cause:** %s\n- **Fix:** %s"
                        (inc i) title symptom cause fix)
                (when example (str "\n\n```clojure\n" example "\n```")))
           ns-token)))
       (str/join "\n\n")))

(defn render-naming
  "Render the case-convention table as markdown."
  [rows]
  (let [label {:clojure "All Clojure code" :db "Database boundary only" :api "API/JSON boundary only"}
        row (fn [{:keys [context case example]}]
              (format "| %s | %s | `%s` |" (label context) (name case) example))]
    (str "| Location | Convention | Example |\n"
         "|----------|-----------|---------|\n"
         (str/join "\n" (map row rows)))))
