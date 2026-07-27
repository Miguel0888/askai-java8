package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pf4j.PluginState;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Correction-commit invariants (fix(plugin-host): complete transactional generation retirement): a candidate
 * that fails to build is fully cleaned up; a failed session close aborts the swap and keeps the old generation
 * loaded; session close runs off the EDT; and a start failure is reported honestly as START_FAILED.
 */
public class WorkspacePluginRetirementTest {

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

    // ------------------------------------------------------------------ #1 partial-load candidate cleanup

    @Test
    public void partialLoadFailureRetiresHalfBuiltManagerAndKeepsPreviousGeneration() throws Exception {
        final Path root = pluginsDirWithJar();
        final AtomicInteger creates = new AtomicInteger();
        final AtomicReference<RecordingManager> second = new AtomicReference<RecordingManager>();
        WorkspacePluginService.PluginManagerFactory factory = new WorkspacePluginService.PluginManagerFactory() {
            public AskAiPluginManager create() {
                if (creates.getAndIncrement() == 0) {
                    return new AskAiPluginManager(root, "0.1.0");
                }
                RecordingManager m = new RecordingManager(root); // loads the real plugin, then throws
                second.set(m);
                return m;
            }
        };
        WorkspacePluginService service =
                new WorkspacePluginService(root, "0.1.0", 1, new InlineUi(), enablement(), factory);
        Catcher catcher = attach(service);

        catcher.refreshAndAwait(service);
        long gen1 = service.getActiveGenerationId();
        String agentId = firstAgentId(service);

        catcher.refreshAndAwait(service); // second create loads then throws → global failure
        assertTrue("build failure must be flagged", catcher.snapshot().isGenerationFailed());
        assertEquals("previous generation kept", gen1, service.getActiveGenerationId());
        assertNotNull("old agent list survives", service.getSelectableAgentExtension(agentId));
        assertFalse("half-built manager must have been unloaded", second.get().unloaded.isEmpty());
        assertEquals("half-built manager retired completely", 0, service.getPendingRetirementCount());
        service.shutdown();
    }

    // ------------------------------------------------------------------ #2/#3 session-close is a precondition

    @Test
    public void sessionCloseFailureAbortsSwapAndKeepsOldGenerationLoaded() throws Exception {
        WorkspacePluginService service =
                new WorkspacePluginService(pluginsDirWithJar(), "0.1.0", 1, new InlineUi(), enablement());
        final AtomicInteger detaches = new AtomicInteger();
        service.setGenerationSwapHook(new GenerationSwapHook() {
            public OutgoingSessions detachOutgoing() {
                final boolean fail = detaches.incrementAndGet() >= 2;
                return new OutgoingSessions() {
                    public SessionCloseResult closeAll() {
                        return fail ? SessionCloseResult.of(java.util.Arrays.asList("session close boom"))
                                : SessionCloseResult.ok();
                    }
                };
            }
        });
        Catcher catcher = attach(service);

        catcher.refreshAndAwait(service);
        long gen1 = service.getActiveGenerationId();
        String agentId = firstAgentId(service);

        catcher.refreshAndAwait(service); // close fails → swap aborted
        assertTrue("aborted swap must be flagged failed", catcher.snapshot().isGenerationFailed());
        assertEquals("old generation must stay active (not unloaded)", gen1, service.getActiveGenerationId());
        assertNotNull("old generation's extensions must stay usable", service.getSelectableAgentExtension(agentId));
        assertEquals("the discarded candidate must be retired completely", 0, service.getPendingRetirementCount());
        service.shutdown();
    }

    // ------------------------------------------------------------------ detach failure aborts the swap

    @Test
    public void detachFailureAbortsSwapAndKeepsPreviousGeneration() throws Exception {
        WorkspacePluginService service =
                new WorkspacePluginService(pluginsDirWithJar(), "0.1.0", 1, new InlineUi(), enablement());
        final AtomicInteger detaches = new AtomicInteger();
        service.setGenerationSwapHook(new GenerationSwapHook() {
            public OutgoingSessions detachOutgoing() {
                if (detaches.incrementAndGet() >= 2) {
                    throw new RuntimeException("EDT detach blew up");
                }
                return new OutgoingSessions() {
                    public SessionCloseResult closeAll() {
                        return SessionCloseResult.ok();
                    }
                };
            }
        });
        Catcher catcher = attach(service);
        catcher.refreshAndAwait(service);
        long gen1 = service.getActiveGenerationId();
        String agentId = firstAgentId(service);

        catcher.refreshAndAwait(service); // detach throws → swap must abort
        assertTrue("aborted swap must be flagged failed", catcher.snapshot().isGenerationFailed());
        assertEquals("previous generation kept when detach fails", gen1, service.getActiveGenerationId());
        assertNotNull(service.getSelectableAgentExtension(agentId));
        assertEquals(0, service.getPendingRetirementCount());
        service.shutdown();
    }

    // ------------------------------------------------------------------ #7 close runs off the EDT

    @Test
    public void sessionCloseRunsOffTheEdt() throws Exception {
        ThreadedUi ui = new ThreadedUi();
        WorkspacePluginService service =
                new WorkspacePluginService(pluginsDirWithJar(), "0.1.0", 1, ui, enablement());
        final AtomicReference<Thread> detachThread = new AtomicReference<Thread>();
        final AtomicReference<Thread> closeThread = new AtomicReference<Thread>();
        service.setGenerationSwapHook(new GenerationSwapHook() {
            public OutgoingSessions detachOutgoing() {
                detachThread.set(Thread.currentThread());
                return new OutgoingSessions() {
                    public SessionCloseResult closeAll() {
                        closeThread.set(Thread.currentThread());
                        return SessionCloseResult.ok();
                    }
                };
            }
        });
        Catcher catcher = attach(service);
        catcher.refreshAndAwait(service);

        assertNotNull(detachThread.get());
        assertNotNull(closeThread.get());
        assertSame("detach must run on the EDT", ui.edtThread(), detachThread.get());
        assertFalse("close must NOT run on the EDT", ui.edtThread() == closeThread.get());
        service.shutdown();
        ui.shutdown();
    }

    // ------------------------------------------------------------------ #10 honest start-failure state

    @Test
    public void startFailureIsReportedAsStartFailedNotMissingExtension() throws Exception {
        final Path root = pluginsDirWithJar();
        WorkspacePluginService.PluginManagerFactory factory = new WorkspacePluginService.PluginManagerFactory() {
            public AskAiPluginManager create() {
                return new AskAiPluginManager(root, "0.1.0") {
                    @Override
                    public PluginState startPlugin(String pluginId) {
                        throw new RuntimeException("simulated start failure");
                    }
                };
            }
        };
        WorkspacePluginService service =
                new WorkspacePluginService(root, "0.1.0", 1, new InlineUi(), enablement(), factory);
        Catcher catcher = attach(service);
        catcher.refreshAndAwait(service);

        assertFalse("a start-failed plugin still produces a catalog row", service.getCatalog().isEmpty());
        PluginCatalogEntry entry = service.getCatalog().get(0);
        assertEquals(PluginCompatibility.START_FAILED, entry.getCompatibility());
        assertNotNull(entry.getLastError());
        assertEquals(PluginFailurePhase.PLUGIN_START, entry.getLastError().getPhase());
        assertTrue("a start-failed plugin is not selectable", service.getSelectableAgentDescriptors().isEmpty());
        service.shutdown();
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

    /** A manager that loads the real plugins and then throws, to exercise the partial-load cleanup path. */
    private static final class RecordingManager extends AskAiPluginManager {
        private final List<String> unloaded = new ArrayList<String>();

        RecordingManager(Path root) {
            super(root, "0.1.0");
        }

        @Override
        public void loadPlugins() {
            super.loadPlugins();
            throw new RuntimeException("simulated failure after partial load");
        }

        @Override
        public boolean unloadPlugin(String pluginId) {
            unloaded.add(pluginId);
            return super.unloadPlugin(pluginId);
        }
    }

    private static final class Catcher implements WorkspaceCatalogListener {
        private final AtomicReference<PluginCatalogSnapshot> latest =
                new AtomicReference<PluginCatalogSnapshot>();
        private volatile CountDownLatch latch = new CountDownLatch(1);

        public void onCatalogReady(List<PluginCatalogEntry> catalog, List<PluginLoadFailure> failures) {
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

    /** A UiExecutor backed by a single dedicated "EDT" thread, so off-EDT work can be verified by identity. */
    private static final class ThreadedUi implements UiExecutor {
        private volatile Thread edt;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "test-edt");
                t.setDaemon(true);
                edt = t;
                return t;
            }
        });

        Thread edtThread() {
            return edt;
        }

        public boolean isUiThread() {
            return Thread.currentThread() == edt;
        }

        public void execute(Runnable runnable) {
            executor.execute(runnable);
        }

        public void assertUiThread() {
        }

        void shutdown() {
            executor.shutdownNow();
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
