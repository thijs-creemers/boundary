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
│   │                  #   supported spec versions + negotiation
│   ├── registry.clj   # tool/resource registry as data (+ list/register fns)
│   ├── security.clj   # pure capability/context gating (tiers, modes, authorize)
│   ├── guardrail.clj  # pure guardrail error payloads (BOU-98)
│   ├── resources.clj  # pure reflective-resource catalog + producers (BOU-99)
│   └── handlers.clj   # pure dispatch: initialize, ping, tools/list, resources/list
├── ports.clj          # Transport + AuditLog + SystemSource protocols
└── shell/
    ├── codec.clj      # cheshire JSON <-> data (kept out of core)
    ├── context.clj    # read env -> security context (I/O)
    ├── audit.clj      # AuditLog sinks: logging (stderr JSON) + in-memory
    ├── guardrail.clj  # guardrail payloads from the devtools BND catalog (I/O)
    ├── system_source.clj # SystemSource adapters: in-process (now), nREPL (later)
    ├── dispatch.clj   # shell dispatch: resources/read (gate+snapshot+encode)
    ├── stdio.clj      # newline-delimited stdin/stdout loop; logs to stderr
    └── server.clj     # -main: resolve context, audit start, boot serve loop
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

## Security — capability/context gating (ADR-031)

Threat model: an agent may be pointed at a live REPL with a production DB.
Every tool declares a **capability tier** — `:read` (Tier 0), `:generate`
(Tier 1, reversible writes), `:execute` (Tier 2, RCE surface). The active
**context** grants a ceiling derived from the environment:

| Mode | Max tier | Set by |
|------|----------|--------|
| `:full`       | `:execute`  | local dev (`BND_ENV=dev`/`test`) |
| `:no-execute` | `:generate` | `BND_ENV=prod` |
| `:read-only`  | `:read`     | CI (`CI` truthy), or no env signal (fail-closed) |
| `:disabled`   | none        | `MCP_CAPABILITY_MODE=disabled` |

Resolution precedence: **`MCP_CAPABILITY_MODE` override > `CI` > `BND_ENV` >
fail-closed `:read-only`**. The decision is pure (`core/security/resolve-context`,
`authorize`); the shell reads env (`shell/context`) and audits (`shell/audit`,
stderr JSON — never stdout).

**Tool authors (BOU-99/100/101/102):** before any work, call
`security/authorize` with the context + `{:name :capability}`; on deny, build
the guardrail error and do nothing (see below); record the decision via the
audit log.

## Guardrail error payload (ADR-032)

"Guardrail, not straitjacket." Every enforcing tool returns one structured
payload — the **rule** that fired (a BND code), the **principle** behind it, a
suggested **fix**, and (when overridable) the audited bypass:

```clojure
{:code "BND-803" :rule "Capability Tier Exceeded"
 :principle "...exceeds the ceiling..." :fix "Run in local dev, ..."
 :overridable? false :details {:tool "eval" :capability :execute :mode :no-execute}}
```

- BND text comes from the shared `devtools` catalog (`BND-8xx` = MCP guardrails),
  the single source of truth. `core/guardrail` is pure; `shell/guardrail` does
  the catalog lookup (I/O) and `boundary/devtools` is a **shell-only** dep.
- Map a `security/authorize` denial → payload: `shell/guardrail/payload-for-denial`
  (or `error-for-denial` for the JSON-RPC error, app code `:forbidden` `-32001`).
- **Hard vs soft:** security/capability denials (BND-801..805) are *not*
  per-call overridable — the audited override is changing the env/context
  (`MCP_CAPABILITY_MODE`). Codegen guardrails (BND-806/807, Tier 1) *are*: the
  caller passes `{:allow true}` (`guardrail/override-requested?`) and the tool
  records a `:guardrail-override` audit event (`guardrail/override-event`)
  before proceeding.

## Reflective resources (ADR-033)

Resources reflect the **running project**, never hardcoded — the answer to
version skew. Producers (`core/resources`) are pure functions of a project
**snapshot**; a `SystemSource` port supplies it (hybrid: in-process file
reflection now; nREPL bridge later for live-system views).

| URI | Source | Status |
|-----|--------|--------|
| `boundary://conventions`     | `resources/agents/knowledge.edn` (FC/IS + naming) | concrete |
| `boundary://module-graph`    | `libs/*/deps.edn` + `ports.clj` presence | concrete |
| `boundary://kondo-rules`     | `.clj-kondo/config.edn` | concrete |
| `boundary://schema-registry` | live Malli registry | `:unavailable` until nREPL bridge |
| `boundary://routes`          | live reitit router | `:unavailable` until nREPL bridge |
| `boundary://workflows`       | workflow registry | `:unavailable` until nREPL bridge |
| `boundary://lib/{name}`      | installed lib API surface | `:unavailable` until nREPL bridge |

- `resources/read` is gated (`security/authorize` `:read`) + audited in
  `shell/dispatch`; denial returns the guardrail payload. Unknown uri →
  `-32602`. Content is JSON. Live views the snapshot can't fill return
  `{:status :unavailable :note ...}` (honest, not silent-empty).
- The in-process adapter reflects the **current working directory** — run from a
  Boundary project root.

## Adding tools / resources (BOU-99 / BOU-100)

Register definitions into the registry data and add a dispatch case (or a
`tools/call` / `resources/read` handler) in `core/handlers.clj`. Gate it per
the Security section above. The stdio transport stays unchanged.
