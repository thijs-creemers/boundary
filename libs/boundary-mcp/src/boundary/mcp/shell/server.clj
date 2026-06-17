(ns ^:boundary/allow-direct boundary.mcp.shell.server
  ;; Composition root: -main wires concrete adapters (audit, system-source, the
  ;; scaffolder service) the way an Integrant config would. Constructing another
  ;; module's adapter here is the canonical hexagonal exception, so this ns is
  ;; exempt from check:ports' cross-module rule (it still calls the scaffolder
  ;; only through boundary.scaffolder.ports thereafter).
  "Entry point. Resolves the security context from the environment, seeds the
   registry with the reflective resources, boots the stdio MCP server, and
   audits startup. Run with: clojure -M:run

   Tool handlers added in BOU-100/101 must call
   `boundary.mcp.core.security/authorize` against the context and record the
   decision via the audit log before performing any mutation."
  (:require [boundary.mcp.core.registry :as registry]
            [boundary.mcp.core.resources :as resources]
            [boundary.mcp.core.security :as security]
            [boundary.mcp.core.tools :as tools]
            [boundary.mcp.ports :as ports]
            [boundary.mcp.shell.audit :as audit]
            [boundary.mcp.shell.context :as context]
            [boundary.mcp.shell.dispatch :as dispatch]
            [boundary.mcp.shell.evaluator :as evaluator]
            [boundary.mcp.shell.migrator :as migrator]
            [boundary.mcp.shell.stdio :as stdio]
            [boundary.mcp.shell.system-source :as system-source]
            [boundary.mcp.shell.test-runner :as test-runner]
            [boundary.scaffolder.shell.service :as scaffolder]
            [clojure.tools.logging :as log]))

(defn- seed-registry
  "Register the reflective resources (BOU-99) and Tier 0 tools (BOU-100)."
  []
  (as-> registry/empty-registry r
    (reduce registry/register-resource r resources/catalog)
    (reduce registry/register-tool r tools/catalog)))

(defn -main
  "Start the blocking stdio server. Returns when stdin reaches EOF."
  [& _args]
  (let [ctx       (context/from-env)
        audit-log (audit/logging-audit-log)
        deps      {:registry      (seed-registry)
                   :security      ctx
                   :audit         audit-log
                   :system-source (system-source/in-process-system-source)
                   ;; Tier 1 generate tools (BOU-101): the scaffolder writes the
                   ;; code; the test-runner runs the project's affected tests in
                   ;; the closed verify loop.
                   :scaffolder    (scaffolder/create-scaffolder-service)
                   :test-runner   test-runner/default-test-runner
                   ;; Tier 2 execute tools (BOU-102): all RCE-class, gated to the
                   ;; :full context. The evaluator/migrator shell into (or run
                   ;; in) the project; query-db needs a read-only datasource not
                   ;; yet wired, so it returns :unavailable until one is.
                   :evaluator     evaluator/default-evaluator
                   :migrator      migrator/default-migrator
                   :db-query      nil
                   ;; sql-preview / gen-tests AI provider is config-driven; nil
                   ;; yields a graceful :unavailable result until one is wired.
                   :ai-provider   nil}]
    (doseq [w (:warnings ctx)]
      (log/warn w))
    (ports/record! audit-log {:event    :server-start
                              :security (security/describe ctx)
                              :warnings (:warnings ctx)})
    (stdio/serve (stdio/transport) (fn [msg] (dispatch/dispatch deps msg)))))
