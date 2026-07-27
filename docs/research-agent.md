# Research Agent

The Research Agent is a chat agent plugin (`com.aresstack.askai.research`) that extends the shared AskAI
chat. It is selected under **Questing → Research Agent**. There is no standalone research workspace anymore
(removed in Commit 17); Research is discovered only via `AgentPluginExtension`.

## What it adds

- **Slash commands** to steer the session (see below).
- **Agent activity** in the normal chat (thinking, tool activity, approval requests, problems).
- **Artifacts** in the shared, collapsible artifact area:
  - Markdown: `outline.md`, `concept.md`, `research-notes.md`, `findings.md`, `draft.md`, `final.md`.
  - Structured: **Sources** (a writing manager) and **State** (state-machine visualization).
- A deterministic **fake backend** (`FakeResearchSessionBackend`) drives a simulated run; the same
  `ResearchSessionBackend` port will later be implemented by an ACP backend without UI changes.

## Slash commands

| Command | Effect |
|---------|--------|
| `/status` | Show the current phase and run state in the chat. |
| `/approve` | Approve the pending approval gate (only when one is pending). |
| `/request-changes` | Reject the pending proposal and return to the working step. |
| `/pause` / `/resume` | Pause/resume the active run. |
| `/cancel` | Cancel the session. |
| `/open <artifact>` | Reveal an artifact tab (completes artifact ids). |

Typing `/` opens a completion popup on the shared composer (Up/Down navigate, Tab accepts, Enter executes,
Escape closes). A slash line is executed as a command and is never sent to the model.

## Flow

```
Questing + Research Agent selected
  → AgentSessionCoordinator resolves the agent extension, creates/reactivates the session
  → composer routes prompts to the session; slash commands to the command registry
  → backend events → AgentConversationSink → the SAME chat transcript
  → artifacts open in the shared artifact area
```

Switching back to Yapping deactivates (keeps) the session; disabling the plugin closes it and falls back to
Yapping. See `docs/plugin-lifecycle.md`.
