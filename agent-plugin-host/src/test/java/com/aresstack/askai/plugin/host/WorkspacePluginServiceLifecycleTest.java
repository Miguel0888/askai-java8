package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.UiExecutor;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Hardened refresh/shutdown lifecycle: shutdown is idempotent, stops a live plugin cleanly (no
 * ConcurrentModificationException), and no refresh is accepted or delivered once shutdown has begun.
 */
public class WorkspacePluginServiceLifecycleTest {

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

    private static WorkspacePluginService service(Path root) {
        return new WorkspacePluginService(root, "0.1.0", 1, new InlineUi(), null);
    }

    @Test
    public void shutdownWithActivePluginIsCleanAndIdempotent() throws Exception {
        WorkspacePluginService service = service(pluginsDirWithJar());
        final CountDownLatch ready = new CountDownLatch(1);
        service.addCatalogListener(new WorkspaceCatalogListener() {
            public void onCatalogReady(List<PluginCatalogEntry> catalog, List<PluginLoadFailure> failures) {
                ready.countDown();
            }
        });
        service.refreshAsync();
        assertTrue("discovery did not complete", ready.await(15, TimeUnit.SECONDS));
        assertFalse("expected the research agent in the catalog", service.getCatalog().isEmpty());

        service.shutdown();   // must not throw (individual stop/unload avoids PF4J's CME)
        service.shutdown();   // idempotent
    }

    @Test
    public void refreshIsRejectedAfterShutdown() throws Exception {
        WorkspacePluginService service = service(pluginsDirWithJar());
        final AtomicInteger deliveries = new AtomicInteger();
        service.addCatalogListener(new WorkspaceCatalogListener() {
            public void onCatalogReady(List<PluginCatalogEntry> catalog, List<PluginLoadFailure> failures) {
                deliveries.incrementAndGet();
            }
        });
        service.shutdown();
        service.refreshAsync(); // rejected synchronously after shutdown (early return, executor already stopped)
        assertEquals(0, deliveries.get());
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
}
