package com.aresstack.askai.research.runtime;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.acp.solon.SolonAcpAgentConnector;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolClientFactory;
import com.aresstack.askai.mcp.solon.SolonMcpServerRuntime;
import com.aresstack.askai.mcp.solon.SolonMcpToolClientFactory;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.acp.ResearchBackendMode;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchAgentSessionFactory;
import com.aresstack.askai.research.host.ResearchRuntimeSettings;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Commit 40 smoke: the productive research mode selected through the PERSISTED settings and started by the
 * SAME {@code ResearchAgentSessionFactory} AskAI's plugin host calls — proving select → validate → start
 * works end to end from the user-facing entry point (MCP-P007). Environment-gated: skips readably without
 * built jars, a Java-21 toolchain or an installed browser (the factory's own specific error message).
 */
public class ProductiveModeFactorySmokeTest {

    @Test
    public void factoryStartsAProductiveSessionFromPersistedSettings() throws Exception {
        String agentJar = System.getProperty("research.agent.jar", "");
        String sidecarJar = System.getProperty("browser.sidecar.jar", "");
        String sidecarJava = System.getProperty("sidecar.java", "");
        String agentHome = System.getProperty("acp.java.home", System.getProperty("java.home"));
        String agentJava = agentHome + File.separator + "bin" + File.separator
                + (System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        assumeTrue("SKIPPED: agent jar not built", new File(agentJar).isFile());
        assumeTrue("SKIPPED: sidecar jar not built", new File(sidecarJar).isFile());
        assumeTrue("SKIPPED: no Java 21 toolchain", new File(sidecarJava).isFile());

        SolonMcpServerRuntime registry = new SolonMcpServerRuntime();
        final File dataDir = Files.createTempDirectory("askai-mode-smoke").toFile();
        final java.util.List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<String>();
        final CountDownLatch ready = new CountDownLatch(1);
        FakeHost host = new FakeHost(dataDir, registry, new SolonMcpToolClientFactory(),
                new SolonAcpAgentConnector(Duration.ofSeconds(120), null), messages, ready);
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, agentJava, agentJar, sidecarJava,
                sidecarJar, System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome"),
                true, "").save(host.store);

        AgentSession session;
        try {
            session = new ResearchAgentSessionFactory().create(
                    new AgentSessionCreationRequest("smoke1", "p1", new HashMap<String, String>()), host);
        } catch (IllegalStateException notReady) {
            // The remaining external dependency is the installed browser — skip with the specific reason.
            assumeTrue("SKIPPED (environment-gated): " + notReady.getMessage(), false);
            return;
        }
        try {
            session.activate();
            ((ResearchAgentSession) session).submitPrompt("hello", "");
            assertTrue("first prompt must announce RESEARCH_MCP_READY: " + messages,
                    ready.await(120, TimeUnit.SECONDS));
        } finally {
            session.close();
            session.close(); // idempotent, closes agent + endpoints + sidecar via the close hook
            registry.shutdown();
        }
    }

    /** Minimal host: direct-run UI executor, in-memory store, real runtime services, recording sink. */
    private static final class FakeHost implements AgentHostContext {
        final MemoryStore store = new MemoryStore();
        private final File dataDir;
        private final Map<Class<?>, Object> services = new HashMap<Class<?>, Object>();
        private final java.util.List<String> messages;
        private final CountDownLatch ready;

        FakeHost(File dataDir, McpServerRegistry registry, McpToolClientFactory clients,
                 AcpAgentConnector connector, java.util.List<String> messages, CountDownLatch ready) {
            this.dataDir = dataDir;
            this.messages = messages;
            this.ready = ready;
            services.put(McpServerRegistry.class, registry);
            services.put(McpToolClientFactory.class, clients);
            services.put(AcpAgentConnector.class, connector);
        }

        public UiExecutor getUiExecutor() {
            return new UiExecutor() {
                public void execute(Runnable runnable) {
                    runnable.run();
                }

                public void assertUiThread() {
                }

                public boolean isUiThread() {
                    return true;
                }
            };
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
            return store;
        }

        public PluginPathService getPluginPathService() {
            return new PluginPathService() {
                public File getPluginDataDirectory() {
                    return dataDir;
                }

                public File getWorkspaceDirectory(String workspaceInstanceId) {
                    return new File(dataDir, workspaceInstanceId);
                }
            };
        }

        public AgentConversationSink getConversationSink() {
            return new AgentConversationSink() {
                public void appendUserMessage(String messageId, String markdown) {
                }

                public void appendAssistantMessage(String messageId, String markdown) {
                    messages.add(markdown);
                    if (markdown != null && markdown.contains("RESEARCH_MCP_READY")) {
                        ready.countDown();
                    }
                }

                public void startThinking(String activityId, String title) {
                }

                public void updateThinking(String activityId, String text) {
                }

                public void finishThinking(String activityId, String summary) {
                }

                public void startToolActivity(String activityId, String title, String explanation) {
                }

                public void updateToolActivity(String activityId, String title, String explanation) {
                }

                public void completeToolActivity(String activityId, String summary) {
                }

                public void failToolActivity(String activityId, String summary) {
                }

                public void requestApproval(String approvalId, String prompt) {
                }

                public void showProblem(String problemId, String publicMessage) {
                    messages.add("PROBLEM: " + publicMessage);
                }
            };
        }

        @SuppressWarnings("unchecked")
        public <T> T getService(Class<T> type) {
            return (T) services.get(type);
        }
    }

    private static final class MemoryStore implements WorkspaceStateStore {
        final Map<String, String> values = new HashMap<String, String>();

        public String get(String key, String defaultValue) {
            return values.containsKey(key) ? values.get(key) : defaultValue;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : defaultValue;
        }

        public int getInt(String key, int defaultValue) {
            return values.containsKey(key) ? Integer.parseInt(values.get(key)) : defaultValue;
        }

        public void put(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, String.valueOf(value));
        }

        public void putInt(String key, int value) {
            values.put(key, String.valueOf(value));
        }
    }
}
