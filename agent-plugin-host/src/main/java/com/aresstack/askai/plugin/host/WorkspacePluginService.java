package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.pf4j.api.WorkspacePluginExtension;

import org.pf4j.PluginWrapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discovers workspace plugins from a single controlled root, off the EDT, and publishes a validated catalog
 * back on the EDT. It owns the PF4J {@link AskAiPluginManager}, maps each started plugin's single workspace
 * extension, runs the {@link PluginCompatibilityChecker}, and exposes only the selectable (compatible,
 * enabled) extensions for opening workspaces. A single broken plugin is captured as a
 * {@link PluginLoadFailure}, never propagated, so the host and normal chat still start.
 */
public final class WorkspacePluginService {

    private final Path pluginsRoot;
    private final String systemVersion;
    private final UiExecutor uiExecutor;
    private final PluginCompatibilityChecker compatibilityChecker;
    private final int supportedApiVersion;
    private final ExecutorService discoveryExecutor;

    private AskAiPluginManager pluginManager;
    private final Map<String, WorkspacePluginExtension> selectableById =
            new LinkedHashMap<String, WorkspacePluginExtension>();
    private volatile List<PluginCatalogEntry> catalog = Collections.emptyList();

    public WorkspacePluginService(Path pluginsRoot, String systemVersion, int supportedApiVersion,
                                  UiExecutor uiExecutor) {
        this.pluginsRoot = pluginsRoot;
        this.systemVersion = systemVersion;
        this.supportedApiVersion = supportedApiVersion;
        this.uiExecutor = uiExecutor;
        this.compatibilityChecker = new PluginCompatibilityChecker(supportedApiVersion);
        this.discoveryExecutor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
    }

    /** Discovers and validates plugins off-EDT; delivers the catalog to the listener on the EDT. */
    public void discoverAsync(final WorkspaceCatalogListener listener) {
        discoveryExecutor.execute(new Runnable() {
            public void run() {
                final List<PluginLoadFailure> failures = new ArrayList<PluginLoadFailure>();
                final List<PluginCatalogEntry> built = discover(failures);
                catalog = built;
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        listener.onCatalogReady(built, failures);
                    }
                });
            }
        });
    }

    /** @return the compatible, enabled extension for a descriptor id, or {@code null}. Call after discovery. */
    public synchronized WorkspacePluginExtension getSelectableExtension(String descriptorId) {
        return descriptorId == null ? null : selectableById.get(descriptorId);
    }

    public List<PluginCatalogEntry> getCatalog() {
        return catalog;
    }

    public synchronized void shutdown() {
        selectableById.clear();
        if (pluginManager != null) {
            try {
                pluginManager.stopPlugins();
            } catch (RuntimeException ignored) {
                // best-effort shutdown
            }
            try {
                pluginManager.unloadPlugins();
            } catch (RuntimeException ignored) {
                // best-effort shutdown
            }
        }
        discoveryExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ discovery (off-EDT)

    private synchronized List<PluginCatalogEntry> discover(List<PluginLoadFailure> failures) {
        selectableById.clear();
        List<PluginCatalogEntry> entries = new ArrayList<PluginCatalogEntry>();
        try {
            pluginManager = new AskAiPluginManager(pluginsRoot, systemVersion);
            pluginManager.loadPlugins();
            pluginManager.startPlugins();
        } catch (RuntimeException | Error ex) {
            failures.add(new PluginLoadFailure(String.valueOf(pluginsRoot), "",
                    PluginFailurePhase.EXTENSION_DISCOVERY, "Plugin discovery failed.", ex));
            return entries;
        }

        Set<String> seenIds = new HashSet<String>();
        for (PluginWrapper wrapper : pluginManager.getStartedPlugins()) {
            entries.add(inspectPlugin(wrapper, seenIds, failures));
        }
        return entries;
    }

    private PluginCatalogEntry inspectPlugin(PluginWrapper wrapper, Set<String> seenIds,
                                             List<PluginLoadFailure> failures) {
        String pluginId = wrapper.getPluginId();
        String location = String.valueOf(wrapper.getPluginPath());
        List<WorkspacePluginExtension> extensions;
        try {
            extensions = pluginManager.getExtensions(WorkspacePluginExtension.class, pluginId);
        } catch (RuntimeException | Error ex) {
            PluginLoadFailure failure = new PluginLoadFailure(location, pluginId,
                    PluginFailurePhase.EXTENSION_DISCOVERY, "Could not load the plugin's extension.", ex);
            failures.add(failure);
            return PluginCatalogEntry.builder().pluginId(pluginId).location(location)
                    .pluginState(String.valueOf(wrapper.getPluginState()))
                    .compatibility(PluginCompatibility.MISSING_EXTENSION).lastError(failure).build();
        }

        int extensionCount = extensions == null ? 0 : extensions.size();
        WorkspacePluginDescriptor descriptor = null;
        if (extensionCount >= 1) {
            try {
                descriptor = extensions.get(0).getDescriptor();
            } catch (RuntimeException | Error ex) {
                PluginLoadFailure failure = new PluginLoadFailure(location, pluginId,
                        PluginFailurePhase.DESCRIPTOR_VALIDATION, "The plugin descriptor is invalid.", ex);
                failures.add(failure);
                return PluginCatalogEntry.builder().pluginId(pluginId).location(location)
                        .pluginState(String.valueOf(wrapper.getPluginState()))
                        .compatibility(PluginCompatibility.DESCRIPTOR_INVALID).lastError(failure).build();
            }
        }

        PluginCompatibility compatibility = descriptor == null
                ? PluginCompatibility.MISSING_EXTENSION
                : compatibilityChecker.check(descriptor,
                        wrapper.getDescriptor().getPluginId(), wrapper.getDescriptor().getVersion(),
                        extensionCount, seenIds);

        if (compatibility == PluginCompatibility.COMPATIBLE && descriptor != null) {
            seenIds.add(descriptor.getId());
            selectableById.put(descriptor.getId(), extensions.get(0));
        }

        return PluginCatalogEntry.builder()
                .pluginId(pluginId)
                .descriptor(descriptor)
                .compatibility(compatibility)
                .pluginState(String.valueOf(wrapper.getPluginState()))
                .location(location)
                .enabled(true)
                .build();
    }

    private static final class DaemonThreadFactory implements java.util.concurrent.ThreadFactory {
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-plugin-discovery");
            thread.setDaemon(true);
            return thread;
        }
    }
}
