(ns boundary.mcp.core.protocol
  "Pure JSON-RPC 2.0 + MCP message construction. No I/O — the shell codec
   (boundary.mcp.shell.codec) owns JSON serialization.

   Maps here use the exact JSON-RPC / MCP wire keys, including camelCase where
   the spec dictates (e.g. :protocolVersion, :serverInfo). This namespace IS
   the protocol boundary, so the wire schema is the representation; the usual
   kebab-case-internal rule does not apply to these protocol field names.")

(def jsonrpc-version "2.0")

;; Pinned MCP protocol revision. The supported-version negotiation policy is
;; tracked separately in BOU-97; the skeleton pins a single revision.
(def mcp-protocol-version "2025-06-18")

;; Standard JSON-RPC 2.0 error codes (https://www.jsonrpc.org/specification).
(def error-codes
  {:parse-error      -32700
   :invalid-request  -32600
   :method-not-found -32601
   :invalid-params   -32602
   :internal-error   -32603})

(defn request?
  "A JSON-RPC request expects a response (carries an :id); a notification does
   not."
  [msg]
  (contains? msg :id))

(defn notification?
  [msg]
  (not (request? msg)))

(defn success
  "Build a JSON-RPC success response for request `id` with `result` payload."
  [id result]
  {:jsonrpc jsonrpc-version
   :id      id
   :result  result})

(defn error
  "Build a JSON-RPC error response. `code-key` is a key in `error-codes` (or a
   raw integer code). `data` is optional extra context."
  ([id code-key message] (error id code-key message nil))
  ([id code-key message data]
   (let [code (get error-codes code-key code-key)]
     {:jsonrpc jsonrpc-version
      :id      id
      :error   (cond-> {:code code :message message}
                 (some? data) (assoc :data data))})))
