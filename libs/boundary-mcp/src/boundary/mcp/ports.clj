(ns boundary.mcp.ports
  "Transport seam for the MCP server. The stdio transport (shell) implements
   this; future HTTP/SSE transports implement the same protocol, so the core
   dispatch loop never depends on a concrete transport.")

(defprotocol Transport
  "A bidirectional JSON-RPC message channel to an MCP peer."
  (send! [this message]
    "Serialize and write a single JSON-RPC message map to the peer.")
  (receive [this]
    "Read and parse the next JSON-RPC message from the peer. Returns a map, or
     nil at end of stream.")
  (close! [this]
    "Release the transport's underlying resources."))
