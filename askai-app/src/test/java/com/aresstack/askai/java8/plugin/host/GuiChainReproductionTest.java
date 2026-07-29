package com.aresstack.askai.java8.plugin.host;

import com.aresstack.askai.mcp.api.McpServerRegistry;
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
import com.aresstack.askai.research.agent.ResearchAgentSessionFactory;
import com.aresstack.askai.research.host.ResearchRuntimeSettings;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The EXACT GUI chain, headless, on the APP classpath: the frame's {@link AgentRuntimeServices} as host
 * services, the plugin's {@link ResearchAgentSessionFactory} (auto defaults, sidecar spawn, both endpoints,
 * ACP agent) and {@code activate()} — the place the GUI reported
 * "session/new failed ... [code=-32603]". A failure here reproduces the GUI bug headlessly; a pass means
 * the remaining difference is Swing/EDT or the PF4J classloader.
 */
public class GuiChainReproductionTest {

    @Test
    public void activateStartsTheAgentExactlyLikeTheGui() throws Exception {
        String agentJar = System.getProperty("research.agent.jar", "");
        String sidecarJar = System.getProperty("browser.sidecar.jar", "");
        String sidecarJava = System.getProperty("sidecar.java", "");
        assumeTrue("SKIPPED: agent jar not built", new File(agentJar).isFile());
        assumeTrue("SKIPPED: sidecar jar not built", new File(sidecarJar).isFile());
        assumeTrue("SKIPPED: no Java 21", !sidecarJava.isEmpty() && new File(sidecarJava).isFile());
        // The productive GUI path now requires the MANDATORY local reranker: its runtime jar must be
        // staged and a cross-encoder model installed, else skip readably.
        String localModelJar = System.getProperty("localmodel.sidecar.jar", "");
        assumeTrue("SKIPPED: local reranker runtime jar not staged",
                !localModelJar.isEmpty() && new File(localModelJar).isFile());
        assumeTrue("SKIPPED: no installed local reranker model",
                new com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager().hasInstalledModels());

        // Distribution layout like runWithDevPlugins assembles it.
        File dist = Files.createTempDirectory("askai-guichain").toFile();
        Files.copy(new File(agentJar).toPath(), new File(dist, "research-agent-runtime.jar").toPath());
        Files.copy(new File(sidecarJar).toPath(), new File(dist, "browser-mcp-sidecar.jar").toPath());
        File libTarget = new File(dist, "lib");
        assertTrue(libTarget.mkdirs());
        for (File jar : new File(new File(sidecarJar).getParentFile(), "lib").listFiles()) {
            Files.copy(jar.toPath(), new File(libTarget, jar.getName()).toPath());
        }
        String oldDist = System.setProperty("askai.research.runtime.dir", dist.getAbsolutePath());
        String oldJava21 = System.setProperty("askai.research.java21", sidecarJava);
        // Stage the local reranker runtime jar where the real LocalModelRuntimeManager looks for it,
        // and point it at the Java-21 launcher — exactly as the assembled distribution provides them.
        File localRuntimeDir = new File(dist, "local-runtime");
        assertTrue(localRuntimeDir.mkdirs());
        Files.copy(new File(localModelJar).toPath(),
                new File(localRuntimeDir, "local-model-runtime-sidecar.jar").toPath());
        // The thin jar has a Class-Path pointing at a sibling lib/ — stage it too.
        File localModelLibsDir = new File(System.getProperty("localmodel.sidecar.libs", ""));
        File libDir = new File(localRuntimeDir, "lib");
        assertTrue(libDir.mkdirs());
        if (localModelLibsDir.isDirectory()) {
            for (File jar : localModelLibsDir.listFiles()) {
                Files.copy(jar.toPath(), new File(libDir, jar.getName()).toPath());
            }
        }
        String oldLocalDir = System.setProperty("askai.local.runtime.dir",
                localRuntimeDir.getAbsolutePath());
        String oldLocalJava = System.setProperty("askai.local.runtime.java", sidecarJava);

        // EXACTLY like the GUI: the host services carry the local model runtime manager, so the
        // mandatory reranker snapshot provider is published to the plugin.
        AgentRuntimeServices services = new AgentRuntimeServices(
                new com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager());
        final List<String> problems = new CopyOnWriteArrayList<String>();
        final List<String> messages = new CopyOnWriteArrayList<String>();
        final CountDownLatch greeted = new CountDownLatch(1);
        FakeHost host = new FakeHost(dist, services.asServiceMap(), problems, messages, greeted);
        // The GUI settings: productive requirements met, Bing search, strict policy (like the screenshot).
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, "", "", "", "", "chrome", true,
                "https://www.bing.com/search?q={query}", false).save(host.store);

        // REGRESSION: the REAL GUI session id contains '#' (pluginId + "#session") and an empty
        // project id. The raw '#' in the MCP endpoint URL truncated the client URL at the fragment
        // and broke the initialize with -32603 — exactly the reported GUI failure.
        AgentSession session = new ResearchAgentSessionFactory().create(
                new AgentSessionCreationRequest("com.aresstack.askai.research#session", "",
                        new HashMap<String, String>()), host);
        try {
            session.activate(); // ← the GUI failure point
            assertTrue("agent start failed like the GUI: " + problems, problems.isEmpty());
            assertTrue("the greeting must appear", greeted.await(10, TimeUnit.SECONDS));
        } finally {
            session.close();
            restore("askai.research.runtime.dir", oldDist);
            restore("askai.research.java21", oldJava21);
            restore("askai.local.runtime.dir", oldLocalDir);
            restore("askai.local.runtime.java", oldLocalJava);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class FakeHost implements AgentHostContext {
        final MemoryStore store = new MemoryStore();
        private final File dataDir;
        private final Map<Class<?>, Object> services;
        private final List<String> problems;
        private final List<String> messages;
        private final CountDownLatch greeted;

        FakeHost(File dataDir, Map<Class<?>, Object> services, List<String> problems,
                 List<String> messages, CountDownLatch greeted) {
            this.dataDir = dataDir;
            this.services = services;
            this.problems = problems;
            this.messages = messages;
            this.greeted = greeted;
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
                    greeted.countDown();
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
                    problems.add(publicMessage);
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
