package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspaceCreationRequest;
import com.aresstack.askai.plugin.api.WorkspaceFactory;
import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;
import com.aresstack.askai.plugin.api.lifecycle.WorkspaceInstance;
import com.aresstack.askai.plugin.api.service.ConversationSurface;
import com.aresstack.askai.plugin.api.service.ConversationSurfaceFactory;
import com.aresstack.askai.plugin.api.service.ConversationSurfaceOptions;
import com.aresstack.askai.plugin.api.service.InteractionModeControls;
import com.aresstack.askai.plugin.api.service.InteractionModeControlsFactory;
import com.aresstack.askai.plugin.api.service.InteractionModeControlsOptions;
import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceHostContext;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.plugin.pf4j.api.WorkspacePluginExtension;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pf4j.PluginWrapper;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The real PF4J boundary: the research plugin is loaded from a plugin directory (never the test classpath),
 * discovered through PF4J, and its extension must implement the HOST's {@link WorkspacePluginExtension} while
 * coming from a different (plugin) classloader. This is what proves single class identity for the shared API.
 *
 * <p>The plugin JAR path is injected by Gradle via {@code -Dresearch.plugin.jar} (see build.gradle). The
 * plugin is deliberately not a dependency of this module.</p>
 */
public class ResearchPluginLoadIntegrationTest {

    private static final String PLUGIN_ID = "com.aresstack.askai.research";
    private static final String PLUGIN_CLASS = "com.aresstack.askai.research.plugin.ResearchPlugin";
    private static final String EXTENSION_CLASS =
            "com.aresstack.askai.research.plugin.ResearchWorkspacePluginExtension";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File pluginJar;

    @Before
    public void resolvePluginJar() {
        String path = System.getProperty("research.plugin.jar");
        assumeTrue("research.plugin.jar system property not set (run via Gradle)",
                path != null && path.trim().length() > 0);
        pluginJar = new File(path.trim());
        assumeTrue("built plugin jar not found: " + pluginJar, pluginJar.isFile());
    }

    // ------------------------------------------------------------------ JAR contents

    @Test
    public void pluginJarHasThePf4jManifestAndExtensionsIndex() throws Exception {
        JarFile jar = new JarFile(pluginJar);
        try {
            Manifest manifest = jar.getManifest();
            assertNotNull("plugin jar has no manifest", manifest);
            Attributes attrs = manifest.getMainAttributes();
            assertEquals(PLUGIN_ID, attrs.getValue("Plugin-Id"));
            assertEquals(PLUGIN_CLASS, attrs.getValue("Plugin-Class"));
            assertEquals("0.1.0", attrs.getValue("Plugin-Version"));
            assertEquals("AresStack", attrs.getValue("Plugin-Provider"));

            assertNotNull("missing META-INF/extensions.idx", jar.getEntry("META-INF/extensions.idx"));
            assertNotNull("missing plugin class",
                    jar.getEntry("com/aresstack/askai/research/plugin/ResearchPlugin.class"));
            assertNotNull("missing extension class",
                    jar.getEntry("com/aresstack/askai/research/plugin/"
                            + "ResearchWorkspacePluginExtension.class"));
        } finally {
            jar.close();
        }
    }

    @Test
    public void pluginJarDoesNotBundleSharedApiPf4jOrApp() throws Exception {
        JarFile jar = new JarFile(pluginJar);
        try {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                assertFalse("bundled PF4J class: " + name, name.startsWith("org/pf4j/"));
                assertFalse("bundled workspace API: " + name,
                        name.startsWith("com/aresstack/askai/plugin/api/"));
                assertFalse("bundled pf4j-bridge API: " + name,
                        name.startsWith("com/aresstack/askai/plugin/pf4j/api/"));
                assertFalse("bundled askai-app class: " + name,
                        name.startsWith("com/aresstack/askai/java8/"));
            }
        } finally {
            jar.close();
        }
    }

    // ------------------------------------------------------------------ real PF4J classloader boundary

    @Test
    public void loadsThroughPf4jWithSeparateClassloaderAndSharedApiIdentity() throws Exception {
        Path pluginsRoot = installIntoTempPluginsDir();
        AskAiPluginManager manager = new AskAiPluginManager(pluginsRoot, "0.1.0");
        try {
            manager.loadPlugins();
            manager.startPlugins();

            List<PluginWrapper> started = manager.getStartedPlugins();
            assertEquals("exactly one plugin should be started", 1, started.size());
            PluginWrapper wrapper = started.get(0);
            assertEquals(PLUGIN_ID, wrapper.getPluginId());

            List<WorkspacePluginExtension> extensions =
                    manager.getExtensions(WorkspacePluginExtension.class, PLUGIN_ID);
            assertEquals("exactly one workspace extension", 1, extensions.size());
            WorkspacePluginExtension extension = extensions.get(0);

            // Identity: the plugin object satisfies the HOST-loaded interface...
            assertTrue("extension must implement the host's WorkspacePluginExtension",
                    extension instanceof WorkspacePluginExtension);
            assertEquals(EXTENSION_CLASS, extension.getClass().getName());

            // ...yet comes from a DIFFERENT classloader than the shared API (plugin CL vs host/parent CL).
            ClassLoader pluginCl = extension.getClass().getClassLoader();
            ClassLoader apiCl = WorkspacePluginExtension.class.getClassLoader();
            assertTrue("extension must come from its own plugin classloader", pluginCl != apiCl);

            // Descriptor + factory reachable across the boundary through the shared API only.
            WorkspacePluginDescriptor descriptor = extension.getDescriptor();
            assertEquals(PLUGIN_ID, descriptor.getId());
            assertEquals("Research Agent", descriptor.getDisplayName());
            assertEquals("0.1.0", descriptor.getVersion());
            assertEquals(1, descriptor.getPluginApiVersion());
            assertNotNull(extension.getWorkspaceFactory());
        } finally {
            quietShutdown(manager);
        }
    }

    // ------------------------------------------------------------------ catalog via WorkspacePluginService

    @Test
    public void discoveryPublishesACatalogEntryForTheResearchAgent() throws Exception {
        Path pluginsRoot = installIntoTempPluginsDir();
        InlineUiExecutor ui = new InlineUiExecutor();
        WorkspacePluginService service =
                new WorkspacePluginService(pluginsRoot, "0.1.0", 1, ui, null);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<List<PluginCatalogEntry>> received =
                new AtomicReference<List<PluginCatalogEntry>>();
        service.addCatalogListener(new WorkspaceCatalogListener() {
            public void onCatalogReady(List<PluginCatalogEntry> catalog, List<PluginLoadFailure> failures) {
                received.set(catalog);
                latch.countDown();
            }
        });
        try {
            service.refreshAsync();
            assertTrue("discovery did not complete", latch.await(15, TimeUnit.SECONDS));

            PluginCatalogEntry entry = findEntry(received.get(), PLUGIN_ID);
            assertNotNull("no catalog entry for the research plugin", entry);
            assertEquals("Research Agent", entry.getDescriptor().getDisplayName());
            assertTrue("plugin should be enabled", entry.isEnabled());
            assertEquals(PluginCompatibility.COMPATIBLE, entry.getCompatibility());
            assertEquals("STARTED", entry.getPluginState());
            assertTrue("expected a real sha256", entry.getSha256().length() == 64);
            assertTrue("expected the real jar location",
                    entry.getLocation().endsWith(".jar"));

            // The selectable extension is exposed for the Questing agent list.
            assertNotNull(service.getSelectableExtension(PLUGIN_ID));
        } finally {
            service.shutdown();
        }
    }

    // ------------------------------------------------------------------ workspace lifecycle across the boundary

    @Test
    public void createsActivatesAndDisposesAWorkspaceWithoutLeaks() throws Exception {
        Path pluginsRoot = installIntoTempPluginsDir();
        final AskAiPluginManager manager = new AskAiPluginManager(pluginsRoot, "0.1.0");
        manager.loadPlugins();
        manager.startPlugins();
        try {
            WorkspacePluginExtension extension =
                    manager.getExtensions(WorkspacePluginExtension.class, PLUGIN_ID).get(0);
            final WorkspaceFactory factory = extension.getWorkspaceFactory();
            final FakeHost host = new FakeHost(folder.newFolder("ws-data"));
            final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    try {
                        WorkspaceInstance ws = factory.createWorkspace(
                                new WorkspaceCreationRequest("ws-int-1", "", new HashMap<String, String>()),
                                host);
                        // All host services must come from the passed context.
                        assertTrue(host.markdownCreated >= 1);
                        assertTrue(host.conversationCreated >= 1);
                        assertTrue(host.controlsCreated >= 1);
                        assertNotNull(ws.getLayout().getMainContent());
                        ws.activate();   // starts the fake session (real scheduler)
                        ws.deactivate(); // keeps state
                        ws.dispose();    // closes session + scheduler; no listener call afterwards
                        ws.dispose();    // idempotent
                    } catch (Throwable t) {
                        error.set(t);
                    }
                }
            });
            if (error.get() != null) {
                throw new AssertionError("workspace lifecycle failed", error.get());
            }
        } finally {
            quietShutdown(manager);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Best-effort teardown mirroring {@link WorkspacePluginService#shutdown()}: PF4J 3.15.0's
     * {@code stopPlugins()} can throw a {@link java.util.ConcurrentModificationException} while iterating its
     * started-plugins list; production swallows it, so the test does too.
     */
    private static void quietShutdown(AskAiPluginManager manager) {
        try {
            manager.stopPlugins();
        } catch (RuntimeException ignored) {
            // best-effort
        }
        try {
            manager.unloadPlugins();
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    private Path installIntoTempPluginsDir() throws Exception {
        Path root = folder.newFolder("plugins").toPath();
        Files.copy(pluginJar.toPath(), root.resolve("research-agent-ui-plugin.jar"),
                StandardCopyOption.REPLACE_EXISTING);
        return root;
    }

    private static PluginCatalogEntry findEntry(List<PluginCatalogEntry> catalog, String id) {
        if (catalog == null) {
            return null;
        }
        for (PluginCatalogEntry e : catalog) {
            if (id.equals(e.getPluginId()) || (e.getDescriptor() != null && id.equals(e.getDescriptor().getId()))) {
                return e;
            }
        }
        return null;
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

    // ------------------------------------------------------------------ fake host services

    private static final class FakeHost implements WorkspaceHostContext {
        private final File dataDir;
        private final FakeTheme theme = new FakeTheme();
        private final MapStore state = new MapStore();
        int markdownCreated;
        int conversationCreated;
        int controlsCreated;

        FakeHost(File dataDir) {
            this.dataDir = dataDir;
        }

        public UiExecutor getUiExecutor() {
            return new InlineUiExecutor();
        }

        public ThemeService getThemeService() {
            return theme;
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return new MarkdownViewFactory() {
                public MarkdownView create(MarkdownViewOptions options) {
                    markdownCreated++;
                    return new FakeMarkdown();
                }
            };
        }

        public ConversationSurfaceFactory getConversationSurfaceFactory() {
            return new ConversationSurfaceFactory() {
                public ConversationSurface create(ConversationSurfaceOptions options) {
                    conversationCreated++;
                    return new FakeConversation();
                }
            };
        }

        public InteractionModeControlsFactory getInteractionModeControlsFactory() {
            return new InteractionModeControlsFactory() {
                public InteractionModeControls create(InteractionModeControlsOptions options) {
                    controlsCreated++;
                    return new FakeControls();
                }
            };
        }

        public WorkspaceStateStore getWorkspaceStateStore() {
            return state;
        }

        public PluginPathService getPluginPathService() {
            return new PluginPathService() {
                public File getPluginDataDirectory() {
                    return dataDir;
                }

                public File getWorkspaceDirectory(String workspaceInstanceId) {
                    return dataDir;
                }
            };
        }

        public NotificationService getNotificationService() {
            return new NotificationService() {
                public void notify(Severity severity, String message) {
                }
            };
        }
    }

    private static final class FakeTheme implements ThemeService {
        public Color color(String key, Color fallback) {
            return fallback;
        }

        public boolean isDark() {
            return false;
        }

        public void addThemeChangeListener(Runnable listener) {
        }

        public void removeThemeChangeListener(Runnable listener) {
        }
    }

    private static final class FakeMarkdown implements MarkdownView {
        private final JPanel component = new JPanel();

        public JComponent getComponent() {
            return component;
        }

        public void setMarkdown(String markdown) {
        }

        public void startStreaming() {
        }

        public void appendMarkdownDelta(String delta) {
        }

        public void finishStreaming() {
        }

        public void dispose() {
        }
    }

    private static final class FakeControls implements InteractionModeControls {
        private final JPanel component = new JPanel();

        public JComponent getComponent() {
            return component;
        }

        public void dispose() {
        }
    }

    private static final class FakeConversation implements ConversationSurface {
        private final JPanel component = new JPanel();

        public JComponent getComponent() {
            return component;
        }

        public void addUserMessage(String messageId, String markdown) {
        }

        public void addAssistantMessage(String messageId, String markdown) {
        }

        public void startAssistantStreaming(String messageId) {
        }

        public void appendAssistantDelta(String messageId, String delta) {
        }

        public void finishAssistantStreaming(String messageId) {
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

        public void markApprovalRequired(String activityId, String explanation) {
        }

        public void completeToolActivity(String activityId, String summary) {
        }

        public void failToolActivity(String activityId, String summary) {
        }

        public void cancelActivity(String activityId, String summary) {
        }

        public void clear() {
        }

        public void dispose() {
        }
    }

    private static final class MapStore implements WorkspaceStateStore {
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
