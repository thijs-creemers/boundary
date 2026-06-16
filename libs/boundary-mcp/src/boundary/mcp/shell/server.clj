(ns boundary.mcp.shell.server
  "Entry point. Boots the stdio MCP server with the (empty, in the skeleton)
   tool/resource registry. Run with: clojure -M:run"
  (:require [boundary.mcp.core.registry :as registry]
            [boundary.mcp.shell.stdio :as stdio]))

(defn -main
  "Start the blocking stdio server. Returns when stdin reaches EOF."
  [& _args]
  (stdio/serve (stdio/transport) registry/empty-registry))
