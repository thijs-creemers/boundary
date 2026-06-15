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
