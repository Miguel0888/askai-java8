package com.aresstack.askai.research.host;

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

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The mode switch is strict and visible: settings round-trip through the ONE typed mapper, FAKE builds only
 * the fake backend (no host-service lookups at all), an unusable productive configuration is rejected before
 * anything starts, a missing host service is named individually — and there is never a fallback to FAKE.
 * The Swing panel reads/writes the SAME mapper (no second mapping).
 */
public class ResearchRuntimeModeTest {

    /** In-memory store (the app persists via the same interface). */
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

    private static final class FakeHostContext implements AgentHostContext {
        final MemoryStore store = new MemoryStore();
        final Map<Class<?>, Object> services = new HashMap<Class<?>, Object>();
        int serviceLookups;
        File workspaceDir;

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
                    return workspaceDir;
                }

                public File getWorkspaceDirectory(String workspaceInstanceId) {
                    return new File(workspaceDir, workspaceInstanceId);
                }
            };
        }

        public AgentConversationSink getConversationSink() {
            return null;
        }

        @SuppressWarnings("unchecked")
        public <T> T getService(Class<T> type) {
            serviceLookups++;
            return (T) services.get(type);
        }
    }

    @Test
    public void settingsRoundTripThroughTheStore() {
        MemoryStore store = new MemoryStore();
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, "C:/j8/java.exe", "C:/agent.jar",
                "C:/j21/java.exe", "C:/sidecar.jar", "msedge", false, "http://x/find?q={query}")
                .save(store);
        ResearchRuntimeSettings loaded = ResearchRuntimeSettings.load(store);
        assertEquals(ResearchBackendMode.ACP, loaded.getMode());
        assertEquals("C:/j8/java.exe", loaded.getAgentJavaExecutable());
        assertEquals("C:/agent.jar", loaded.getAgentJar());
        assertEquals("C:/j21/java.exe", loaded.getSidecarJavaExecutable());
        assertEquals("C:/sidecar.jar", loaded.getSidecarJar());
        assertEquals("msedge", loaded.getBrowserChannel());
        assertEquals(false, loaded.isHeadless());
        assertEquals("http://x/find?q={query}", loaded.getSearchUrlTemplate());
    }

    @Test
    public void unknownPersistedModeLoadsAsFake() {
        MemoryStore store = new MemoryStore();
        store.put(ResearchRuntimeSettings.KEY_MODE, "SOMETHING_ELSE");
        assertEquals(ResearchBackendMode.FAKE, ResearchRuntimeSettings.load(store).getMode());
    }

    @Test
    public void fakeModeCreatesTheFakeBackendWithoutAnyServiceLookup() {
        FakeHostContext host = new FakeHostContext();
        AgentSession session = new ResearchAgentSessionFactory().create(
                new AgentSessionCreationRequest("s1", "p1", new HashMap<String, String>()), host);
        assertNotNull(session);
        assertEquals("the fake path never asks for host runtime services", 0, host.serviceLookups);
        session.close();
    }

    @Test
    public void invalidProductiveConfigurationIsRejectedWithoutFallback() {
        FakeHostContext host = new FakeHostContext();
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, "", "", "", "", "chrome", true, "")
                .save(host.store);
        try {
            new ResearchAgentSessionFactory().create(
                    new AgentSessionCreationRequest("s1", "p1", new HashMap<String, String>()), host);
            fail("an unusable productive configuration must fail visibly, not fall back to FAKE");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("no fallback"));
            assertTrue(expected.getMessage().contains("not configured"));
        }
        assertEquals("validation happens before any service lookup", 0, host.serviceLookups);
    }

    @Test
    public void missingHostServiceIsNamedIndividually() throws Exception {
        FakeHostContext host = new FakeHostContext();
        // Paths pass validation (real temp files), but the host offers no runtime services.
        File dir = Files.createTempDirectory("askai-mode").toFile();
        File java8 = touch(dir, "java.exe");
        File agentJar = touch(dir, "agent.jar");
        File java21 = touch(dir, "java21.exe");
        File sidecarJar = touch(dir, "sidecar.jar");
        assertTrue(new File(dir, "lib").mkdirs()); // the thin sidecar jar needs its sibling lib/
        host.workspaceDir = dir;
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, java8.getAbsolutePath(),
                agentJar.getAbsolutePath(), java21.getAbsolutePath(), sidecarJar.getAbsolutePath(),
                "chrome", true, "http://search.example/find?q={query}").save(host.store);
        try {
            new ResearchAgentSessionFactory().create(
                    new AgentSessionCreationRequest("s1", "p1", new HashMap<String, String>()), host);
            fail("a missing host service must fail visibly");
        } catch (IllegalStateException expected) {
            assertTrue("the FIRST missing service is named: " + expected.getMessage(),
                    expected.getMessage().contains("McpServerRegistry"));
            assertTrue(expected.getMessage().contains("no fallback"));
        }
    }

    @Test
    public void completedSettingsNeverComplainAboutTheAgentJava() throws Exception {
        // Regression (user-reported): Save validated the RAW panel fields and demanded the removed
        // agent-Java field. Validation must always run on the COMPLETED settings — the agent runs on
        // AskAI's own JVM.
        File dir = Files.createTempDirectory("askai-defaults").toFile();
        File agentJar = touch(dir, "research-agent-runtime.jar");
        File sidecarJar = touch(dir, "browser-mcp-sidecar.jar");
        File java21 = touch(dir, "java21.exe");
        assertTrue(new File(dir, "lib").mkdirs());
        String oldJava21 = System.setProperty(ResearchRuntimeDefaults.JAVA21_PROPERTY,
                java21.getAbsolutePath());
        try {
            ResearchRuntimeSettings raw = new ResearchRuntimeSettings(ResearchBackendMode.ACP,
                    "", agentJar.getAbsolutePath(), "", sidecarJar.getAbsolutePath(),
                    "chrome", true, "https://www.bing.com/search?q={query}");
            java.util.List<String> problems =
                    ResearchRuntimeDefaults.complete(raw).validateProductive();
            assertTrue("completed settings must be fully usable: " + problems, problems.isEmpty());
        } finally {
            if (oldJava21 == null) {
                System.clearProperty(ResearchRuntimeDefaults.JAVA21_PROPERTY);
            } else {
                System.setProperty(ResearchRuntimeDefaults.JAVA21_PROPERTY, oldJava21);
            }
        }
    }

    @Test
    public void panelReadsAndWritesTheSameTypedMapper() {
        MemoryStore store = new MemoryStore();
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, "a", "b", "c", "d", "msedge", false,
                "http://s/{query}").save(store);
        ResearchRuntimeSettingsPanel panel = new ResearchRuntimeSettingsPanel(store);
        ResearchRuntimeSettings fromPanel = panel.currentSettings();
        assertEquals(ResearchBackendMode.ACP, fromPanel.getMode());
        assertEquals("a", fromPanel.getAgentJavaExecutable());
        assertEquals("d", fromPanel.getSidecarJar());
        assertEquals("msedge", fromPanel.getBrowserChannel());
        assertEquals(false, fromPanel.isHeadless());
        assertEquals("http://s/{query}", fromPanel.getSearchUrlTemplate());
    }

    private static File touch(File dir, String name) throws Exception {
        File file = new File(dir, name);
        assertTrue(file.createNewFile() || file.isFile());
        return file;
    }
}
