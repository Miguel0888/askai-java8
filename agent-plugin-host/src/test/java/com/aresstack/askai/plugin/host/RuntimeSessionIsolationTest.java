package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.agent.AgentSessionFactory;
import com.aresstack.askai.plugin.api.agent.AgentStateSnapshot;
import com.aresstack.askai.plugin.api.agent.ChatSubmissionTarget;
import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * Isolation gate for the per-tab research runtime (the "kleiner Beweis" before the model-backed TeamAgent): two
 * chat tabs of the SAME agent must own <em>independent</em> runtime sessions. The single app-wide
 * {@link AgentSessionCoordinator} is only a multiplexer keyed by {@code agentId + "#" + scope} (the tab's
 * ChatSessionId) — it never collapses two tabs onto one session. This test threads the REAL
 * {@link ScopedPluginPathService} through the coordinator seam so the project directory isolation is concrete,
 * not mocked, and proves:
 * <ul>
 *   <li>distinct session key, distinct host context, distinct project directory per tab;</li>
 *   <li>a message routed while tab A is active reaches only A — never B;</li>
 *   <li>closing tab A ends only A; tab B stays active and answerable.</li>
 * </ul>
 */
public class RuntimeSessionIsolationTest {

    private static final String AGENT = "com.aresstack.askai.research";

    private final String[] scope = {"tab-A"};
    private File dataDir;

    private AgentSessionCoordinator newCoordinator(final RecordingExtension extension) throws Exception {
        dataDir = Files.createTempDirectory("askai-isolation").toFile();
        AgentSessionCoordinator.AgentExtensionResolver resolver =
                new AgentSessionCoordinator.AgentExtensionResolver() {
                    public AgentPluginExtension resolve(String agentId) {
                        return AGENT.equals(agentId) ? extension : null;
                    }
                };
        // Mirrors the productive provider: a DISTINCT host context per (agentId, sessionInstanceId), whose
        // path service scopes the project dir by the exact sessionInstanceId — the tab-scoped session key.
        AgentSessionCoordinator.AgentHostContextProvider provider =
                new AgentSessionCoordinator.AgentHostContextProvider() {
                    public AgentHostContext create(String agentId, String sessionInstanceId) {
                        return new FakeHostContext(new ScopedPluginPathService(dataDir, agentId));
                    }
                };
        AgentSessionCoordinator.SessionScopeProvider scopeProvider =
                new AgentSessionCoordinator.SessionScopeProvider() {
                    public String currentScope() {
                        return scope[0];
                    }
                };
        return new AgentSessionCoordinator(resolver, provider, new InlineUiExecutor(), scopeProvider);
    }

    @Test
    public void twoTabsOfTheSameAgentGetFullyIsolatedRuntimeSessions() throws Exception {
        RecordingExtension extension = new RecordingExtension();
        AgentSessionCoordinator c = newCoordinator(extension);

        scope[0] = "tab-A";
        c.setActiveAgent(AGENT);
        FakeSession a = extension.last;

        scope[0] = "tab-B";
        c.setActiveAgent(AGENT);
        FakeSession b = extension.last;

        // Distinct identity on every axis the isolation contract names.
        assertNotSame("distinct session instances", a, b);
        assertNotEquals("distinct session keys (agentId#chatSessionId)", a.sessionKey, b.sessionKey);
        assertNotSame("distinct host contexts", a.host, b.host);
        assertNotEquals("distinct project directories", a.projectDir, b.projectDir);
        assertTrue("A's key carries its own tab scope", a.sessionKey.endsWith("#tab-A"));
        assertTrue("B's key carries its own tab scope", b.sessionKey.endsWith("#tab-B"));

        // Routing isolation: B is active now — a message reaches only B.
        c.submitText("only-B");
        assertEquals(Collections.singletonList("only-B"), b.target.submitted);
        assertTrue("nothing leaked into tab A", a.target.submitted.isEmpty());

        // Switch back to tab A and route a message — it reaches only A.
        scope[0] = "tab-A";
        c.setActiveAgent(AGENT);
        c.submitText("only-A");
        assertEquals(Collections.singletonList("only-A"), a.target.submitted);
        assertEquals("tab B never saw tab A's message", Collections.singletonList("only-B"),
                b.target.submitted);
    }

    @Test
    public void closingOneTabEndsOnlyThatSessionAndLeavesTheOtherAnswerable() throws Exception {
        RecordingExtension extension = new RecordingExtension();
        AgentSessionCoordinator c = newCoordinator(extension);

        scope[0] = "tab-A";
        c.setActiveAgent(AGENT);
        FakeSession a = extension.last;
        scope[0] = "tab-B";
        c.setActiveAgent(AGENT);
        FakeSession b = extension.last;

        // Close tab A's scope: detach happens here, the real close runs off-EDT.
        c.closeSessionsForScope("tab-A");
        for (int i = 0; i < 200 && a.closeCount == 0; i++) {
            Thread.sleep(10);
        }
        assertEquals("only tab A's session was closed", 1, a.closeCount);
        assertEquals("tab B untouched by tab A's close", 0, b.closeCount);

        // Tab B stays fully answerable: activate it and route a message.
        scope[0] = "tab-B";
        c.setActiveAgent(AGENT);
        assertTrue(c.isActive());
        assertEquals(SubmissionAvailability.AVAILABLE, c.getAvailability());
        c.submitText("still-alive");
        assertEquals(Collections.singletonList("still-alive"), b.target.submitted);

        // A fresh tab A must start a BRAND-NEW session, never resume the closed one.
        scope[0] = "tab-A";
        c.setActiveAgent(AGENT);
        assertNotSame("a fresh tab A never resumes the closed session", a, c.getActiveSession());
        assertNotSame("the replacement is a distinct instance", a, extension.last);
    }

    // ------------------------------------------------------------------ fakes

    private static final class RecordingExtension implements AgentPluginExtension {
        FakeSession last;

        public AgentPluginDescriptor getAgentDescriptor() {
            return AgentPluginDescriptor.builder().id(AGENT).displayName("Research Agent").version("1").build();
        }

        public AgentSessionFactory getSessionFactory() {
            return new AgentSessionFactory() {
                public AgentSession create(AgentSessionCreationRequest request, AgentHostContext hostContext) {
                    // Exactly what the productive research factory does: derive the project dir from THIS
                    // session's id via the host's path service — so distinct session keys ⇒ distinct dirs.
                    File dir = hostContext.getPluginPathService()
                            .getWorkspaceDirectory(request.getSessionId());
                    last = new FakeSession(request.getSessionId(), hostContext, dir);
                    return last;
                }
            };
        }

        public List<ChatCommandContribution> getChatCommands() {
            return Collections.emptyList();
        }

        public List<ArtifactViewContribution> getArtifactViews() {
            return Collections.emptyList();
        }
    }

    private static final class FakeSession implements AgentSession {
        final String sessionKey;
        final AgentHostContext host;
        final File projectDir;
        final FakeTarget target = new FakeTarget();
        volatile int closeCount;

        FakeSession(String sessionKey, AgentHostContext host, File projectDir) {
            this.sessionKey = sessionKey;
            this.host = host;
            this.projectDir = projectDir;
        }

        public ChatSubmissionTarget getChatTarget() {
            return target;
        }

        public List<AgentArtifact> getArtifacts() {
            return Collections.emptyList();
        }

        public AgentArtifactStore getArtifactStore() {
            return null;
        }

        public AgentStateSnapshot getState() {
            return AgentStateSnapshot.builder().build();
        }

        public void activate() {
        }

        public void deactivate() {
        }

        public void close() {
            closeCount++;
        }
    }

    private static final class FakeTarget implements ChatSubmissionTarget {
        final java.util.List<String> submitted = new java.util.ArrayList<String>();

        public SubmissionAvailability getAvailability() {
            return SubmissionAvailability.AVAILABLE;
        }

        public void submitText(String text) {
            submitted.add(text);
        }

        public void stop() {
        }
    }

    /** Minimal host context: only the path service is real (it proves per-session project-dir scoping). */
    private static final class FakeHostContext implements AgentHostContext {
        private final PluginPathService pathService;

        FakeHostContext(PluginPathService pathService) {
            this.pathService = pathService;
        }

        public UiExecutor getUiExecutor() {
            return new InlineUiExecutor();
        }

        public ThemeService getThemeService() {
            return null;
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return null;
        }

        public NotificationService getNotificationService() {
            return null;
        }

        public WorkspaceStateStore getStateStore() {
            return null;
        }

        public PluginPathService getPluginPathService() {
            return pathService;
        }

        public AgentConversationSink getConversationSink() {
            return null;
        }
    }

    private static final class InlineUiExecutor implements UiExecutor {
        public boolean isUiThread() {
            return true;
        }

        public void execute(Runnable runnable) {
            runnable.run();
        }

        public void assertUiThread() {
        }
    }
}
