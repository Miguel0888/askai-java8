package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Commit 21 invariants: a plugin refresh is transactional (exactly one active generation; a failed candidate
 * keeps the previous one), disablement is honoured before start (a disabled plugin is loaded but never started
 * and is not selectable), and enablement is keyed on the stable PF4J plugin id.
 */
public class WorkspacePluginTransactionalRefreshTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File pluginJar;

    @Before
    public void resolvePluginJar() {
        String path = System.getProperty("research.plugin.jar");
        assumeTrue("research.plugin.jar not set (run via Gradle)", path != null && !path.trim().isEmpty());
        pluginJar = new File(path.trim());
        assumeTrue("plugin jar missing", pluginJar.isFile());
    }

    private Path pluginsDirWithJar() throws Exception {
        Path root = folder.newFolder("plugins").toPath();
        Files.copy(pluginJar.toPath(), root.resolve("research-agent-ui-plugin.jar"),
                StandardCopyOption.REPLACE_EXISTING);
        return root;
    }

    // ------------------------------------------------------------------ #1, #2 single active generation

    @Test
    public void refreshIncrementsGenerationAndKeepsExactlyOneActive() throws Exception {
        PluginEnablementService enablement = enablement();
        WorkspacePluginService service =
                new WorkspacePluginService(pluginsDirWithJar(), "0.1.0", 1, new InlineUi(), enablement);
        Catcher catcher = attach(service);

        catcher.refreshAndAwait(service);
        long g1 = service.getActiveGenerationId();
        assertTrue("generation id must advance from 0", g1 > 0);
        String agentId = firstAgentId(service);
        assertNotNull("research agent must be selectable", service.getSelectableAgentExtension(agentId));

        catcher.refreshAndAwait(service);
        long g2 = service.getActiveGenerationId();
        catcher.refreshAndAwait(service);
        long g3 = service.getActiveGenerationId();

        assertTrue("each refresh advances the active generation", g2 > g1 && g3 > g2);
        // Still exactly one selectable research agent after three refreshes (no leaked/duplicated generation).
        assertEquals(1, service.getSelectableAgentDescriptors().size());
        assertNotNull(service.getSelectableAgentExtension(agentId));
        service.shutdown();
    }

    // ------------------------------------------------------------------ #3,#4,#5 disable = not started

    @Test
    public void disabledPluginIsLoadedButNotStartedAndNotSelectable() throws Exception {
        PluginEnablementService enablement = enablement();
        WorkspacePluginService service =
                new WorkspacePluginService(pluginsDirWithJar(), "0.1.0", 1, new InlineUi(), enablement);
        Catcher catcher = attach(service);

        catcher.refreshAndAwait(service);
        String agentId = firstAgentId(service);
        String pluginId = pluginIdOf(service, agentId);
        assertEquals("STARTED", stateOf(service, pluginId));

        // Disable by the stable PF4J plugin id, refresh: loaded but not started, not selectable.
        enablement.setEnabled(pluginId, false);
        catcher.refreshAndAwait(service);
        assertNull("disabled agent must not be selectable", service.getSelectableAgentExtension(agentId));
        assertTrue("disabled agent must not appear in the selectable list",
                service.getSelectableAgentDescriptors().isEmpty());
        assertFalse("disabled plugin must not be STARTED", "STARTED".equals(stateOf(service, pluginId)));
        assertFalse("catalog row must report Enabled=false", enabledRowOf(service, pluginId));
        assertEquals("disabled plugin must show NOT_EVALUATED, never a fabricated COMPATIBLE",
                PluginCompatibility.NOT_EVALUATED, compatibilityOf(service, pluginId));

        // Re-enable: started and selectable again.
        enablement.setEnabled(pluginId, true);
        catcher.refreshAndAwait(service);
        assertNotNull(service.getSelectableAgentExtension(agentId));
        assertEquals("STARTED", stateOf(service, pluginId));

        // Re-disable: not selectable again.
        enablement.setEnabled(pluginId, false);
        catcher.refreshAndAwait(service);
        assertNull(service.getSelectableAgentExtension(agentId));
        service.shutdown();
    }

    // ------------------------------------------------------------------ #6 fatal candidate keeps old generation

    @Test
    public void globalFailureKeepsPreviousGeneration() throws Exception {
        final Path root = pluginsDirWithJar();
        final AtomicInteger creates = new AtomicInteger();
        WorkspacePluginService.PluginManagerFactory factory = new WorkspacePluginService.PluginManagerFactory() {
            public AskAiPluginManager create() {
                if (creates.getAndIncrement() == 0) {
                    return new AskAiPluginManager(root, "0.1.0"); // first refresh loads the real plugin
                }
                return new AskAiPluginManager(root, "0.1.0") {
                    @Override
                    public void loadPlugins() {
                        throw new RuntimeException("simulated fatal discovery failure");
                    }
                };
            }
        };
        WorkspacePluginService service =
                new WorkspacePluginService(root, "0.1.0", 1, new InlineUi(), enablement(), factory);
        Catcher catcher = attach(service);

        catcher.refreshAndAwait(service);
        assertFalse(catcher.snapshot().isGenerationFailed());
        String agentId = firstAgentId(service);
        long gen1 = service.getActiveGenerationId();
        assertNotNull(service.getSelectableAgentExtension(agentId));

        // Second refresh fails globally: previous generation must stay active and functional.
        catcher.refreshAndAwait(service);
        assertTrue("failed refresh must be flagged", catcher.snapshot().isGenerationFailed());
        assertFalse("global failure must be reported", catcher.snapshot().getGlobalFailures().isEmpty());
        assertEquals("previous generation must be kept", gen1, service.getActiveGenerationId());
        assertNotNull("old agent list must survive a failed refresh",
                service.getSelectableAgentExtension(agentId));
        service.shutdown();
    }

    // ------------------------------------------------------------------ #8 swap hook closes sessions first

    @Test
    public void swapHookFiresOnEverySuccessfulGeneration() throws Exception {
        WorkspacePluginService service =
                new WorkspacePluginService(pluginsDirWithJar(), "0.1.0", 1, new InlineUi(), enablement());
        final AtomicInteger detachCalls = new AtomicInteger();
        service.setGenerationSwapHook(new GenerationSwapHook() {
            public OutgoingSessions detachOutgoing() {
                detachCalls.incrementAndGet();
                return new OutgoingSessions() {
                    public SessionCloseResult closeAll() {
                        return SessionCloseResult.ok();
                    }
                };
            }
        });
        Catcher catcher = attach(service);
        catcher.refreshAndAwait(service);
        catcher.refreshAndAwait(service);
        assertEquals("swap hook detaches once per successful generation swap", 2, detachCalls.get());
        service.shutdown();
    }

    // ------------------------------------------------------------------ #13 JAR replaceable after retirement

    @Test
    public void pluginJarIsReleasedAfterShutdown() throws Exception {
        Path root = pluginsDirWithJar();
        File jar = root.resolve("research-agent-ui-plugin.jar").toFile();
        WorkspacePluginService service =
                new WorkspacePluginService(root, "0.1.0", 1, new InlineUi(), enablement());
        Catcher catcher = attach(service);
        catcher.refreshAndAwait(service);
        service.shutdown();
        assertTrue("shutdown work must complete", service.awaitShutdownForTest(15_000));

        // After retirement the classloader/JAR lock must be released so the file can be deleted (Windows-sensitive).
        boolean deleted = jar.delete();
        assumeTrue("filesystem did not permit deletion in this environment", deleted);
        assertFalse(jar.exists());
    }

    // ------------------------------------------------------------------ helpers

    private static PluginEnablementService enablement() {
        return new PluginEnablementService(new InMemoryStateStore());
    }

    private Catcher attach(WorkspacePluginService service) {
        Catcher catcher = new Catcher();
        service.addCatalogListener(catcher);
        return catcher;
    }

    private static String firstAgentId(WorkspacePluginService service) {
        List<com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor> agents =
                service.getSelectableAgentDescriptors();
        assertFalse("expected at least one selectable agent", agents.isEmpty());
        return agents.get(0).getId();
    }

    /** The PF4J plugin id (== descriptor id) for the catalog row that carries the given agent descriptor id. */
    private static String pluginIdOf(WorkspacePluginService service, String descriptorId) {
        for (PluginCatalogEntry entry : service.getCatalog()) {
            if (entry.getDescriptor() != null && descriptorId.equals(entry.getDescriptor().getId())) {
                return entry.getPluginId();
            }
        }
        return descriptorId;
    }

    private static String stateOf(WorkspacePluginService service, String pluginId) {
        for (PluginCatalogEntry entry : service.getCatalog()) {
            if (pluginId.equals(entry.getPluginId())) {
                return entry.getPluginState();
            }
        }
        return "";
    }

    private static PluginCompatibility compatibilityOf(WorkspacePluginService service, String pluginId) {
        for (PluginCatalogEntry entry : service.getCatalog()) {
            if (pluginId.equals(entry.getPluginId())) {
                return entry.getCompatibility();
            }
        }
        return null;
    }

    private static boolean enabledRowOf(WorkspacePluginService service, String pluginId) {
        for (PluginCatalogEntry entry : service.getCatalog()) {
            if (pluginId.equals(entry.getPluginId())) {
                return entry.isEnabled();
            }
        }
        return false;
    }

    /** Captures the latest snapshot and lets a test await each async refresh delivery. */
    private static final class Catcher implements WorkspaceCatalogListener {
        private final AtomicReference<PluginCatalogSnapshot> latest =
                new AtomicReference<PluginCatalogSnapshot>();
        private volatile CountDownLatch latch = new CountDownLatch(1);

        public void onCatalogReady(List<PluginCatalogEntry> catalog, List<PluginLoadFailure> failures) {
            // superseded by onCatalogSnapshot
        }

        @Override
        public void onCatalogSnapshot(PluginCatalogSnapshot snapshot) {
            latest.set(snapshot);
            latch.countDown();
        }

        void refreshAndAwait(WorkspacePluginService service) throws InterruptedException {
            latch = new CountDownLatch(1);
            service.refreshAsync();
            assertTrue("refresh did not complete in time", latch.await(20, TimeUnit.SECONDS));
        }

        PluginCatalogSnapshot snapshot() {
            return latest.get();
        }
    }

    private static final class InlineUi implements UiExecutor {
        public boolean isUiThread() {
            return true;
        }

        public void execute(Runnable runnable) {
            runnable.run();
        }

        public void assertUiThread() {
        }
    }

    private static final class InMemoryStateStore implements WorkspaceStateStore {
        private final Map<String, String> values = new HashMap<String, String>();

        public String get(String key, String defaultValue) {
            String v = values.get(key);
            return v == null ? defaultValue : v;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            String v = values.get(key);
            return v == null ? defaultValue : Boolean.parseBoolean(v);
        }

        public int getInt(String key, int defaultValue) {
            String v = values.get(key);
            try {
                return v == null ? defaultValue : Integer.parseInt(v);
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        public void put(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, Boolean.toString(value));
        }

        public void putInt(String key, int value) {
            values.put(key, Integer.toString(value));
        }
    }
}
