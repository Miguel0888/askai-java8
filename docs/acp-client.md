# ACP client (host side)

## Modules

- `:acp-client-api` (Java 8, neutral — no ACP-SDK/Reactor/Swing types): connection/session/prompt
  state machines with explicitly rejected invalid transitions, `AcpUpdate` (sessionId + promptId +
  monotone sequenceNumber), `PromptDispatcher` (ordered streaming, exactly one terminal callback,
  late-drop, listener-exception isolation), `AgentLaunchSpec` (command/args/explicit env),
  `AcpEndpointDescriptor` (endpointId/url/transport/token), `AcpException(Phase)`,
  `AcpAgentConnector` port.
- `:acp-solon-client` — the only module touching `org.noear:acp-sdk:3.10.1`/Reactor.
  `SolonAcpAgentConnector` spawns the agent process over stdio, drains STDERR into a bounded ring
  (200 lines) + optional host log consumer, routes `sessionUpdateConsumer` into the dispatcher,
  runs prompts on a daemon executor and classifies process exit. Callbacks never run on the EDT.
- `:acp-demo-agent` — minimal echo agent used by the acp-solon-client round-trip tests.

## Rules

- Endpoint descriptors travel to the agent as STRUCTURED launch environment (`ASKAI_*` variables),
  never as prompt text. Browser variables are fully present or fully absent — no empty token values.
- The child process receives only the explicit env of the launch spec plus the `ASKAI_*` contract —
  it never inherits arbitrary host secrets.
- Tokens never appear in events, logs or `toString()`.
- Cancel cancels the running prompt turn only; the process stays alive. A dead process → the session
  reports FAILED and stays down (MVP restart policy; restart is an explicit user action).

## Verification

10 round-trip tests in `:acp-solon-client` (lifecycle, ordering, exactly-one-terminal, cancel race,
listener-exception isolation, shutdown-timeout kill) plus the research agent round-trip and E2E
tests in `:research-agent-runtime`.
