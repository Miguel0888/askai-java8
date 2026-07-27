# Migration — standalone research workspace → chat agent

This documents the migration performed across Commits 10–19 on `feature/pf4j-research-clickdummy`.

## Product model change

Before: selecting Questing → Research swapped the whole center to a **standalone research workspace** with
its own chat/activity column, composer, outline tree and toolbar.

After: AskAI stays one chat. The Research Agent **extends** the shared chat with slash commands, agent
activity (in the normal transcript), artifacts, specialized artifact views and a state visualization. The
chat component and composer are physically the same across Yapping↔Questing.

## Removed UI (Commit 17)

- `ResearchWorkspaceInstance` (the full shell) and its views: toolbar, outline tree, main tabs, composer.
- `ResearchWorkspaceFactory`, `ResearchWorkspacePluginExtension`.
- `ResearchDemoData` and the `research.domain` package (outline/section/source/finding/problem) — only the
  removed shell used them.
- Legacy tests for the shell and the workspace-focused classloader test.

`extensions.idx` now lists only `ResearchAgentPluginExtension`. Plugin id
(`com.aresstack.askai.research`) and the plugin jar are unchanged.

## Changed extension

Research is discovered **only** as an `AgentPluginExtension`. The generic `WorkspacePluginExtension` path
remains in the host for other/future plugins; Research no longer needs it. The host treats a plugin as
selectable via its agent extension alone (agent-only plugins get a catalog entry from a bridged descriptor).

## State model migration (Commit 14)

The free `(ResearchPhase, ResearchRunState)` pair is replaced by a hierarchical OO state pattern
(`ResearchPhaseState → PhaseState`). The `ResearchStateMachine` port is unchanged; `DefaultResearchStateMachine`
is now a facade. Persisted state is a **memento of ids only** (`phaseId`, `stateId`, `continuationStateId`,
`revision`, `pendingApprovalId`) — see `docs/research-state-machine.md`. Old code that constructed arbitrary
phase/run-state pairs is gone; illegal combinations are now rejected by `ResearchStateFactory`.

## Persistence keys & project paths

- Chat/host state keys are unchanged (`chat.interactionMode`, `chat.questingAgentId`, artifact-area
  `chat.artifactArea.visible/width`).
- New file-backed project store layout (`docs/research-artifacts.md`):
  `project/artifacts/*.md (+ .meta)`, `project/sources/*.properties`, `project/state/research-session.json`.

## Fallbacks / not-yet-migrated

- The live `ResearchAgentSession` still uses in-memory artifact/source stores; the file store is implemented
  and tested but not yet wired into the running session (RA-P001, RA-P002 in `problems.md`).
- No non-migratable runtime state: the fake backend starts a fresh run on activation; restart-restore is
  available at the store level and is the remaining wiring step.
