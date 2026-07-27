# Developing a chat agent plugin

AskAI is always the same chat application. A **chat agent** does not replace the chat — it *extends* it
with slash commands, agent activity, artifacts, specialized artifact views and a state visualization. This
document describes the generic agent API and how the host wires it.

## Product model

```
Chat is the shell.
  Yapping   → the normal Ollama chat path.
  Questing  → the active AgentSession (the shared chat + composer are reused, not rebuilt).
```

The host owns the single chat transcript and the single composer. A plugin **must not** create a second
conversation surface or a second composer.

## Module map

| Module | Contains |
|--------|----------|
| `agent-workspace-api` | Generic, framework-free contracts (Swing allowed, **no PF4J, no research types**). Package `com.aresstack.askai.plugin.api.agent` and sub-packages `command`, `artifact`. |
| `agent-plugin-pf4j-api` | The only PF4J bridge: `AgentPluginExtension extends org.pf4j.ExtensionPoint`. |
| `agent-plugin-host` | Host: discovery, catalog, `AgentSessionCoordinator` (routing + registry), artifact area, lifecycle. |
| a plugin (e.g. `research-agent-ui-plugin`) | Implements `AgentPluginExtension`; depends on the shared APIs + PF4J as `compileOnly` (host-provided). |

## The extension point

```java
public interface AgentPluginExtension extends org.pf4j.ExtensionPoint {
    AgentPluginDescriptor getAgentDescriptor();
    AgentSessionFactory   getSessionFactory();
    List<ChatCommandContribution>   getChatCommands();
    List<ArtifactViewContribution>  getArtifactViews();
}
```

Annotate the implementation with `@org.pf4j.Extension`. The annotation processor writes
`META-INF/extensions.idx`.

## Session lifecycle

```
AgentSessionFactory.create(request, AgentHostContext) → AgentSession
  activate()   – bring to foreground; may start/resume the run (idempotent)
  deactivate() – background; keep state (do NOT close)
  close()      – release backend + owned resources; no sink call afterwards (idempotent)
```

- Exactly **one session per agent id** — created lazily, reused across Yapping↔Questing switches.
- A session is closed only when its plugin is disabled/removed or on shutdown.
- Agent activity is pushed into the shared chat via `AgentConversationSink` (user/assistant/thinking/
  tool/approval/problem). There is no second surface.

## Composer routing

`ChatSubmissionTarget` (from `AgentSession.getChatTarget()`) receives *plain* prompts; slash commands are
intercepted by the `ActiveAgentCommandRegistry` before they reach the target. `SubmissionAvailability`
(AVAILABLE/BUSY/UNAVAILABLE) drives Send/Stop. Yapping keeps its own Ollama path — the router only handles
the agent side.

## Slash commands

`ChatCommandContribution` (name, description, usage, args; `complete(...)`, `execute(...)`). They are user
controls, distinct from agent tools. Completions returned by the registry are **full replacement lines**.
Execution returns `CommandExecutionResult` (HANDLED/REJECTED/UNKNOWN) — never a thrown exception in the chat.

## Artifacts

`AgentArtifact` (id, displayName, opaque `artifactTypeId`, relativePath, revision). Type `"markdown"` uses
the host's default editor (`HostMarkdownArtifactView`) over `AgentArtifactStore` (read/replace by revision,
optimistic locking). Structured types (e.g. `research.sources`, `research.state`) get a plugin
`ArtifactViewContribution`. Content lives in the store, never in long-lived UI state.

## EDT & classloader

- All `AgentConversationSink` / view updates run on the EDT via the host `UiExecutor`.
- The shared APIs come from the **host** classloader; the plugin bundles only its own classes +
  `extensions.idx` (APIs/PF4J are `compileOnly`). This keeps a single extension-interface identity across
  classloaders (verified by `AgentPluginLoadIntegrationTest`).

## Build & dev run

```bash
./gradlew :research-agent-ui-plugin:pluginJar        # build the external plugin jar
./gradlew :research-agent-ui-plugin:installDevPlugin # copy it into <root>/build/dev-plugins (atomic)
./gradlew :askai-app:runWithDevPlugins               # run AskAI with -Daskai.pluginsDir=<root>/build/dev-plugins
```

The AskAI fat jar never contains research/plugin classes (`:askai-app:verifyFatJarExclusions`).
