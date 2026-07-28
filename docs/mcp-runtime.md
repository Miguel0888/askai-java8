# MCP runtime (host side)

## Port and implementations

`:mcp-runtime-api` (Java 8, no Solon/MCP-SDK types) defines the host's MCP surface:

- `McpServerRegistry` — register/unregister endpoints, update tool sets (`tools/list_changed`),
  `endpointUrl(handle)` for the client-facing URL (token embedded in the path).
- `McpEndpointHandle` — endpoint id + opaque random token; unregistering invalidates the token.
- `McpToolContribution` / `McpToolHandler` / `McpToolCall` / `McpToolResult` / `McpToolParameter` —
  flat tool schemas only (STRING/INTEGER/BOOLEAN), results are plain text, never raw HTML/binary.
- `McpToolClient` / `McpToolClientFactory` — the neutral CLIENT port (used by the plugin's browser
  bridge to call the sidecar); tool failure and unreachable endpoint stay distinguishable.
- `InProcessMcpServerRegistry` — transport-free reference implementation for wiring/tests. It is NOT
  an MCP protocol implementation; `endpointUrl` returns an identity-only `inprocess:` URI.

`:mcp-solon-runtime` implements the port over the real Solon streamable-HTTP transport:

- `SolonMcpServerRuntime` — one Solon server on `127.0.0.1` with a free port; every endpoint lives
  under `/mcp/<id>/<token>`; `updateTools` emits `tools/list_changed`; shutdown is idempotent.
- `SolonMcpToolClientFactory` — the `McpToolClient` implementation over `McpClientProvider`.

## Rules

- All endpoints bind to `127.0.0.1` only; every session endpoint carries a random, non-guessable
  token that is invalidated at session end (unregister).
- Tokens never appear in transcripts, tool bubbles, normal logs or `toString()`.
- No own MCP protocol implementation anywhere — Solon (`org.noear:solon-ai-mcp:3.10.1`, Java 8
  compatible) is the only wire implementation.

## Verification

`SolonMcpServerRuntimeTest` (real client round-trip, token gate, tools/list_changed) and every
integration test in `:research-agent-runtime` (round-trip, 36A/36C loop tests, 38 E2E) run against
this runtime with real streamable-HTTP clients.
