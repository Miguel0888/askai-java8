# Plugin & agent-session lifecycle

## Discovery

`WorkspacePluginService` loads plugins from a controlled root (dev: `<root>/build/dev-plugins`, overridable
via `-Daskai.pluginsDir`), off the EDT, and publishes a validated catalog on the EDT. A plugin is selectable
via an `AgentPluginExtension` (new model) and/or a `WorkspacePluginExtension` (legacy generic path). Agent
plugins get a catalog entry from a bridged descriptor, so an agent-only plugin (like Research) appears under
Questing without a workspace extension. Each entry carries id, descriptor, compatibility, PF4J state,
location, SHA-256 and enable/disable state.

## Session lifecycle (agent model)

`AgentSessionCoordinator` (the `ChatSubmissionRouter` + `ActiveAgentCommandRegistry`) owns sessions:

- **one session per agent id**, created lazily, reused across mode switches;
- Yapping → `deactivateActive()` (session kept);
- switch agent → deactivate old (kept), activate new (atomic route swap);
- plugin disabled/removed on refresh → `retainOnly(...)` closes that session and falls back;
- shutdown → `shutdown()` closes all sessions and their schedulers.

After a session is closed no `AgentConversationSink` call happens; late/foreign/duplicate backend events are
dropped (session id + sequence guards).

## Enable / disable

`PluginEnablementService` persists disabled ids. A disabled plugin is not selectable; disabling the active
agent closes its session and the composer falls back to Yapping. Re-enabling makes it selectable again.

## Shutdown (hardened, Commit 18)

`ChatWorkspaceHostPanel.shutdown()`: coordinator.shutdown (close sessions + schedulers) → dispose
workspaces → `WorkspacePluginService.shutdown()`.

`WorkspacePluginService.shutdown()` is idempotent (compareAndSet) and ordered: mark shutting-down (rejects
new refreshes) → stop the discovery executor → clear extension maps + listeners → **stop and unload each
plugin individually over a stable id copy** → drop the manager. Stopping plugins one-by-one avoids PF4J
`stopPlugins()` mutating its own list mid-iteration (the previously seen `ConcurrentModificationException`);
an error in one plugin is isolated. Refresh carries a generation so a slow discovery cannot overwrite a newer
catalog or fire after shutdown.

## Classloader boundary

Shared APIs come from the host classloader; the plugin jar bundles only its own classes + `extensions.idx`.
`AgentPluginLoadIntegrationTest` loads the real plugin jar from a plugin dir and asserts the extension
implements the host-loaded interface yet comes from a different (plugin) classloader, and that the jar bundles
no PF4J / shared-API / askai-app classes. `:askai-app:verifyFatJarExclusions` keeps research classes out of
the AskAI fat jar.
