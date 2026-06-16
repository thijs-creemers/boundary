# boundary-mcp — MCP Server for Boundary

Model Context Protocol server exposing Boundary's framework knowledge to editor
agents (Claude Code, Cursor). Standalone library — **deliberately not wired into
the root `deps.edn` paths** so applications never pull an MCP server
transitively. Depends on `boundary/ai` for the core context/parsing helpers that
later tools reuse.

> Status: **skeleton (BOU-96)** — stdio transport, JSON-RPC handshake, empty
> tool/resource registry. Tier 0 tools land in BOU-100, reflective resources in
> BOU-99.

## Run

```bash
cd libs/boundary-mcp
clojure -M:run        # stdio MCP server (reads JSON-RPC from stdin)
clojure -M:test       # kaocha unit tests
```

Smoke test the handshake:

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"resources/list"}' \
  | clojure -M:run 2>/dev/null
```

## Layout (FC/IS)

```
src/boundary/mcp/
├── core/
│   ├── protocol.clj   # pure JSON-RPC 2.0 + MCP message builders, error codes,
│   │                  #   pinned spec version (2025-06-18)
│   ├── registry.clj   # tool/resource registry as data (+ list/register fns)
│   └── handlers.clj   # pure dispatch: initialize, ping, tools/list, resources/list
├── ports.clj          # Transport protocol (stdio now; HTTP/SSE later)
└── shell/
    ├── codec.clj      # cheshire JSON <-> data (kept out of core)
    ├── stdio.clj      # newline-delimited stdin/stdout loop; logs to stderr
    └── server.clj     # -main: boots serve loop with empty registry
```

**Core is pure** — no JSON, no I/O. The shell owns cheshire and stdin/stdout.
stdout carries protocol messages only; all logging goes to **stderr** so it
never corrupts the message stream.

## Protocol notes

- JSON-RPC field names are the wire contract: `protocol.clj` builds maps with the
  exact MCP keys (camelCase where the spec requires, e.g. `:protocolVersion`).
  The usual kebab-case-internal rule does not apply to these protocol fields.
- Notifications (no `:id`) get no reply; unknown **requests** return `-32601`
  (method not found); malformed JSON returns `-32700` (parse error) and the loop
  continues.
- Adding a transport: implement `boundary.mcp.ports/Transport`; the `serve` loop
  is transport-agnostic.

## Adding tools / resources (BOU-99 / BOU-100)

Register definitions into the registry data and add a dispatch case (or a
`tools/call` / `resources/read` handler) in `core/handlers.clj`. The stdio
transport stays unchanged.
