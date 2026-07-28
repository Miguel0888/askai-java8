# External research agent runtime

`:research-agent-runtime` (Java 8, `application` plugin) is the EXTERNAL agent process: it speaks
ACP (stdio) to AskAI and MCP (streamable HTTP) to the research-control and browser endpoints. It is
never part of the AskAI fat jar; the runnable jar comes from the `researchAgentJar` task.

## Process contract

- Launch: `java -jar research-agent-runtime-<version>-all.jar`, configured ONLY via `ASKAI_*`
  environment: `ASKAI_SESSION_ID`, `ASKAI_PROJECT_ID`, `ASKAI_RESEARCH_MCP_URL/_TRANSPORT/_TOKEN`,
  optional `ASKAI_BROWSER_MCP_URL/_TRANSPORT/_TOKEN` (fully present or fully absent).
- `session/new` performs REAL readiness: connect the research endpoint, `tools/list` must contain
  `research_status`, then call it — a failure fails session creation atomically.
- First prompt turn announces `RESEARCH_MCP_READY` (+ `BROWSER_NOT_AVAILABLE` when no browser
  endpoint exists — visible, non-fatal).
- The agent mirrors host state via `research_status`; it owns NO research state machine and has no
  way to switch phases.
- Logs go to STDERR only; `toString()`/logs never contain tokens.

## The autonomous loop (36A)

Prompts starting with `research:` run the deterministic loop (`loop.ResearchLoop`):
`web_search` seed → content-driven frontier (query terms against page text/title and link text) →
`web_open` (parse `capture_id=`) → `source_accept` (Commit-37 contract, duplicates are not errors,
ALREADY_ACCEPTED never re-claims) → `finding_add` for new claims → `web_links` → follow only
content-matching links. One central budget gate (`ResearchRunBudget`: tool calls, pages, sources,
consecutive errors, wall time; defaults 30/20/8/3/10min, sufficiency = ≥3 sources on ≥2 hosts) runs
before EVERY tool call; the explicit `ResearchStopReason` (11 values) is streamed over ACP as
`RESEARCH_RUN_STOPPED: <reason> pages=… sources=… hosts=…`. `PHASE_READY: SUFFICIENT_EVIDENCE` is an
EVENT line only — the host decides what follows. Server-side write rejections ("Not allowed in the
current state") end the run as APPROVAL_REQUIRED/STATE_CHANGED, never as a crash. Cancel (ACP) stops
the loop at the next gate.

`SolonToolInvoker` is the production `ToolInvoker` (real Solon MCP client); the identical class runs
in unit fixtures, against in-process endpoints (36A), against the live Playwright sidecar (36C) and
inside the productive E2E (38) — there is no backend-specific code path in the loop.

## Tests

- `ResearchLoopTest` — 7 content-fixture tests (budgets, cancel, error reset, late-write rejection,
  page-count semantics) with an injected clock, no sleeps.
- `ResearchLoopMcpIntegrationTest` — the loop over real Solon MCP clients/endpoints.
- `ResearchLoopPlaywrightSidecarIntegrationTest` — the unchanged loop against the live sidecar
  process + real Chrome (environment-gated).
- `ResearchAgentRoundTripTest` — real process round-trip incl. atomic wrong-token failure.
- `ProductiveResearchMvpEndToEndTest` — the full productive host wiring (see research-mvp.md).
