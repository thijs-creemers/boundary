(ns boundary.shell.modules
  "Module registry and composition helpers for the Boundary shell.

  This namespace provides utilities to:
  - Determine which modules are enabled based on configuration.
  - Compose route definitions from multiple modules (new pattern).
  - Legacy HTTP handler composition (deprecated).

  For now, only the `:user` module is supported."
  (:require [clojure.tools.logging :as log]))

(defn enabled-modules
  "Return the vector of enabled modules based on app config.

  Looks at [:active :boundary/settings :modules] in the configuration map.
  If no modules are configured, defaults to [:user] for backwards compatibility."
  [config]
  (let [mods (get-in config [:active :boundary/settings :modules])]
    (if (seq mods)
      (vec mods)
      [:user])))

(defn compose-module-routes
  "Compose route definitions from multiple modules (new pattern).

  Arguments:
    route-maps: collection of route maps from modules.
                Each route map has keys: :api, :web, :static.

  Returns:
    Combined route map with merged routes:
    {:api    [all-api-routes]
     :web    [all-web-routes]
     :static [all-static-routes]}

  Example:
    (compose-module-routes [{:api [['/users' {...}]] :web [] :static []}
                            {:api [['/billing' {...}]] :web [] :static []}])
    ;=> {:api [['/users' {...}] ['/billing' {...}]]
    ;    :web []
    ;    :static []}"
  [route-maps]
  (reduce
    (fn [acc route-map]
      {:api    (vec (concat (:api acc []) (:api route-map [])))
       :web    (vec (concat (:web acc []) (:web route-map [])))
       :static (vec (concat (:static acc []) (:static route-map [])))})
    {:api [] :web [] :static []}
    route-maps))

(defn compose-http-handlers
  "DEPRECATED: Use structured route definitions instead.

  Compose HTTP handlers from module-provided handlers.

  Arguments:
    enabled-modules: vector of module keywords, e.g. [:user :billing]
    handlers: map from module keyword to Ring handler.

  For now, we only support the :user module and simply return its handler.
  In the future, this can be extended to build a combined router or apply
  module-specific routing prefixes.

  NOTE: This function is deprecated as of ADR-007. Use compose-module-routes
  with structured route definitions instead."
  [enabled-modules handlers]
  (log/warn "compose-http-handlers is DEPRECATED - use structured route definitions (ADR-007)"
            {:enabled-modules enabled-modules})
  (let [handler (or (get handlers :user)
                    (first (vals handlers)))]
    (when (nil? handler)
      (log/warn "No HTTP handlers available for enabled modules" {:enabled-modules enabled-modules}))
    handler))

(defn dispatch-cli
  "Dispatch CLI based on enabled modules.

  Arguments:
    enabled-modules: vector of module keywords from config
    module->runner: map of module keyword to a function (fn [args] -> exit-code)
    args: raw CLI arguments vector

  CLI convention:
    boundary <module> <command> [options]

  - <module> is a keyword name like user or billing.
  - If <module> is omitted, and only one enabled module exists, that module
    is used by default.
  - If <module> is omitted and multiple modules are enabled, an error is
    reported and exit code 1 is returned.
  - If the selected module is not enabled or has no runner, exit code 1
    is returned."
  [enabled-modules module->runner args]
  (let [[mod-token & rest-args] args
        mod-kw (when mod-token (keyword mod-token))
        enabled-set (set enabled-modules)
        ;; Determine target module and remaining args
        [target-module cli-args]
        (cond
          ;; Explicit module token
          (and mod-kw (enabled-set mod-kw))
          [mod-kw rest-args]

          ;; Explicit module token but not enabled
          mod-kw
          (do
            (log/error "Requested CLI module is not enabled" {:module mod-kw
                                                               :enabled-modules enabled-modules})
            [nil args])

          ;; No module token, single enabled module: default to it
          (= 1 (count enabled-set))
          [(first enabled-set) args]

          ;; No module token, multiple enabled modules
          :else
          (do
            (log/error "Multiple modules enabled; please specify <module> explicitly"
                       {:enabled-modules enabled-modules})
            [nil args]))]
    (cond
      (and target-module (contains? module->runner target-module))
      ((get module->runner target-module) cli-args)

      target-module
      (do
        (log/error "No CLI runner registered for module" {:module target-module})
        1)

      :else
      1)))
