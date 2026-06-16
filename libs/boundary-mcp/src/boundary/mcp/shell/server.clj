(ns boundary.mcp.shell.server
  "Entry point. Resolves the security context from the environment, seeds the
   registry with the reflective resources, boots the stdio MCP server, and
   audits startup. Run with: clojure -M:run

   Tool handlers added in BOU-100/101 must call
   `boundary.mcp.core.security/authorize` against the context and record the
   decision via the audit log before performing any mutation."
  (:require [boundary.mcp.core.registry :as registry]
            [boundary.mcp.core.resources :as resources]
            [boundary.mcp.core.security :as security]
            [boundary.mcp.ports :as ports]
            [boundary.mcp.shell.audit :as audit]
            [boundary.mcp.shell.context :as context]
            [boundary.mcp.shell.dispatch :as dispatch]
            [boundary.mcp.shell.stdio :as stdio]
            [boundary.mcp.shell.system-source :as system-source]
            [clojure.tools.logging :as log]))

(defn- seed-registry
  "Register the reflective resources (BOU-99) into the registry."
  []
  (reduce registry/register-resource registry/empty-registry resources/catalog))

(defn -main
  "Start the blocking stdio server. Returns when stdin reaches EOF."
  [& _args]
  (let [ctx       (context/from-env)
        audit-log (audit/logging-audit-log)
        deps      {:registry      (seed-registry)
                   :security      ctx
                   :audit         audit-log
                   :system-source (system-source/in-process-system-source)}]
    (doseq [w (:warnings ctx)]
      (log/warn w))
    (ports/record! audit-log {:event    :server-start
                              :security (security/describe ctx)
                              :warnings (:warnings ctx)})
    (stdio/serve (stdio/transport) (fn [msg] (dispatch/dispatch deps msg)))))
