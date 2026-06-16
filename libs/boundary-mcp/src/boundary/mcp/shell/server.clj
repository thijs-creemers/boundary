(ns boundary.mcp.shell.server
  "Entry point. Resolves the security context from the environment, boots the
   stdio MCP server with the (empty, in the skeleton) tool/resource registry,
   and audits startup. Run with: clojure -M:run

   Tool/resource handlers added in BOU-99/100/101 must call
   `boundary.mcp.core.security/authorize` against this context and record the
   decision via the audit log before performing any mutation."
  (:require [boundary.mcp.core.registry :as registry]
            [boundary.mcp.core.security :as security]
            [boundary.mcp.ports :as ports]
            [boundary.mcp.shell.audit :as audit]
            [boundary.mcp.shell.context :as context]
            [boundary.mcp.shell.stdio :as stdio]
            [clojure.tools.logging :as log]))

(defn -main
  "Start the blocking stdio server. Returns when stdin reaches EOF."
  [& _args]
  (let [ctx       (context/from-env)
        audit-log (audit/logging-audit-log)]
    (doseq [w (:warnings ctx)]
      (log/warn w))
    (ports/record! audit-log {:event    :server-start
                              :security (security/describe ctx)
                              :warnings (:warnings ctx)})
    (stdio/serve (stdio/transport) registry/empty-registry)))
