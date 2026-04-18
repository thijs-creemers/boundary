(ns boundary.devtools.shell.auto-fix
  "Execute fix descriptors — side-effecting operations.
   This is a shell namespace: it runs migrations, sets env vars, etc.
   The safety gate (safe? false → always confirm) is never overridden by guidance level."
  (:require [clojure.java.shell :as shell]))

(defmulti run-action!
  "Execute a specific fix action. Dispatches on :action keyword."
  (fn [action params] action))

(defmethod run-action! :migrate-up
  [_ _params]
  (println "Running: bb migrate up")
  (let [{:keys [exit out err]} (shell/sh "bb" "migrate" "up")]
    (when (seq out) (println out))
    (when (and (seq err) (not (zero? exit))) (println err))
    (zero? exit)))

(defmethod run-action! :set-env
  [_ {:keys [var-name value]}]
  (when (and var-name value)
    (System/setProperty var-name value)
    true))

(defmethod run-action! :set-jwt
  [_ _params]
  (let [secret (str "dev-secret-" (System/currentTimeMillis) "-boundary")]
    (System/setProperty "JWT_SECRET" secret)
    true))

(defmethod run-action! :integrate-module
  [_ {:keys [module-name]}]
  (when module-name
    (println (str "Running: bb scaffold integrate " module-name))
    (let [{:keys [exit out err]} (shell/sh "bb" "scaffold" "integrate" (name module-name))]
      (when (seq out) (println out))
      (when (and (seq err) (not (zero? exit))) (println err))
      (zero? exit))))

(defmethod run-action! :add-dependency
  [_ {:keys [lib version]}]
  (println "Suggested addition to deps.edn:")
  (println (str "  " lib " {:mvn/version \"" (or version "LATEST") "\"}"))
  (println "Please add this manually to the appropriate deps.edn file.")
  true)

(defmethod run-action! :show-refactoring
  [_ {:keys [source-ns requires-ns]}]
  (println "FC/IS Refactoring Steps:")
  (println "  1. Create a protocol in ports.clj for the data you need")
  (println "  2. Move the shell dependency behind the protocol")
  (println "  3. Have the shell namespace implement the protocol")
  (when source-ns
    (println (str "  Source: " source-ns))
    (println (str "  Remove require: " requires-ns)))
  (println (str "\n  Or try: (ai/refactor-fcis '" source-ns ")"))
  true)

(defmethod run-action! :default
  [action _params]
  (println (str "Unknown fix action: " action))
  false)

(defn execute-fix!
  "Execute a fix descriptor with safety/confirmation logic.
   opts:
     :guidance-level — :full, :minimal, or :off
     :confirm-fn     — (fn [prompt] => boolean), for risky fixes"
  [{:keys [label safe? action params]} {:keys [guidance-level confirm-fn]}]
  (let [should-confirm? (not safe?)
        should-print?   (not= guidance-level :minimal)]
    (if should-confirm?
      (if (and confirm-fn (confirm-fn (str "Apply fix: " label "?")))
        (do
          (when should-print? (println (str "Applying: " label)))
          (run-action! action params))
        (println "Aborted."))
      (do
        (when should-print? (println (str "Applying: " label)))
        (run-action! action params)))))
