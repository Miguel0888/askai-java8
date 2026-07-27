package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;
import com.aresstack.askai.plugin.pf4j.api.WorkspacePluginExtension;

import org.pf4j.PluginWrapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discovers workspace plugins from a single controlled root, off the EDT, and publishes a validated catalog
 * to all registered listeners on the EDT. It owns the PF4J {@link AskAiPluginManager}, maps each started
 * plugin's single workspace extension, validates compatibility, applies the persisted enable/disable state,
 * computes a content hash, and exposes only the selectable (enabled + compatible) extensions. A broken plugin
 * is captured as a {@link PluginLoadFailure}, never propagated, so the host and normal chat still start.
 */
public final class WorkspacePluginService {

    private final Path pluginsRoot;
    private final String systemVersion;
    private final UiExecutor uiExecutor;
    private final PluginCompatibilityChecker compatibilityChecker;
    private final PluginEnablementService enablement;
    private final ExecutorService discoveryExecutor;
    private final CopyOnWriteArrayList<WorkspaceCatalogListener> listeners =
            new CopyOnWriteArrayList<WorkspaceCatalogListener>();

    private AskAiPluginManager pluginManager;
    private final Map<String, WorkspacePluginExtension> selectableById =
            new LinkedHashMap<String, WorkspacePluginExtension>();
    private final Map<String, AgentPluginExtension> selectableAgentById =
            new LinkedHashMap<String, AgentPluginExtension>();
    private final Map<String, com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor> agentDescriptors =
            new LinkedHashMap<String, com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor>();
    private volatile List<PluginCatalogEntry> catalog = Collections.emptyList();

    public WorkspacePluginService(Path pluginsRoot, String systemVersion, int supportedApiVersion,
                                  UiExecutor uiExecutor, PluginEnablementService enablement) {
        this.pluginsRoot = pluginsRoot;
        this.systemVersion = systemVersion;
        this.uiExecutor = uiExecutor;
        this.enablement = enablement;
        this.compatibilityChecker = new PluginCompatibilityChecker(supportedApiVersion);
        this.discoveryExecutor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
    }

    public void addCatalogListener(WorkspaceCatalogListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeCatalogListener(WorkspaceCatalogListener listener) {
        listeners.remove(listener);
    }

    /** Re-discovers and validates plugins off-EDT; delivers the catalog to every listener on the EDT. */
    public void refreshAsync() {
        discoveryExecutor.execute(new Runnable() {
            public void run() {
                final List<PluginLoadFailure> failures = new ArrayList<PluginLoadFailure>();
                final List<PluginCatalogEntry> built = discover(failures);
                catalog = built;
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        for (WorkspaceCatalogListener listener : listeners) {
                            listener.onCatalogReady(built, failures);
                        }
                    }
                });
            }
        });
    }

    /** @return the compatible, enabled extension for a descriptor id, or {@code null}. Call after discovery. */
    public synchronized WorkspacePluginExtension getSelectableExtension(String descriptorId) {
        return descriptorId == null ? null : selectableById.get(descriptorId);
    }

    /**
     * @return the compatible, enabled <em>agent</em> extension for an agent id, or {@code null}. This is the
     *         new-model entry point (agent extends the shared chat); the workspace extension above is the
     *         legacy standalone-workspace path. Call after discovery.
     */
    public synchronized AgentPluginExtension getSelectableAgentExtension(String agentId) {
        return agentId == null ? null : selectableAgentById.get(agentId);
    }

    /** @return the descriptors of all selectable agents, in discovery order (for the Questing agent list). */
    public synchronized List<com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor>
            getSelectableAgentDescriptors() {
        return new ArrayList<com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor>(
                agentDescriptors.values());
    }

    public List<PluginCatalogEntry> getCatalog() {
        return catalog;
    }

    public synchronized void shutdown() {
        selectableById.clear();
        selectableAgentById.clear();
        agentDescriptors.clear();
        listeners.clear();
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
        selectableAgentById.clear();
        agentDescriptors.clear();
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
        String sha256 = sha256Of(wrapper.getPluginPath());

        List<WorkspacePluginExtension> workspaceExtensions;
        AgentPluginExtension agentExtension;
        try {
            workspaceExtensions = pluginManager.getExtensions(WorkspacePluginExtension.class, pluginId);
            List<AgentPluginExtension> agentExtensions =
                    pluginManager.getExtensions(AgentPluginExtension.class, pluginId);
            agentExtension = agentExtensions == null || agentExtensions.isEmpty()
                    ? null : agentExtensions.get(0);
        } catch (RuntimeException | Error ex) {
            PluginLoadFailure failure = new PluginLoadFailure(location, pluginId,
                    PluginFailurePhase.EXTENSION_DISCOVERY, "Could not load the plugin's extension.", ex);
            failures.add(failure);
            return PluginCatalogEntry.builder().pluginId(pluginId).location(location).sha256(sha256)
                    .pluginState(String.valueOf(wrapper.getPluginState()))
                    .compatibility(PluginCompatibility.MISSING_EXTENSION).lastError(failure).build();
        }

        boolean hasWorkspace = workspaceExtensions != null && !workspaceExtensions.isEmpty();
        WorkspacePluginDescriptor descriptor = null;
        try {
            if (hasWorkspace) {
                descriptor = workspaceExtensions.get(0).getDescriptor();
            } else if (agentExtension != null) {
                descriptor = toWorkspaceDescriptor(agentExtension.getAgentDescriptor());
            }
        } catch (RuntimeException | Error ex) {
            PluginLoadFailure failure = new PluginLoadFailure(location, pluginId,
                    PluginFailurePhase.DESCRIPTOR_VALIDATION, "The plugin descriptor is invalid.", ex);
            failures.add(failure);
            return PluginCatalogEntry.builder().pluginId(pluginId).location(location).sha256(sha256)
                    .pluginState(String.valueOf(wrapper.getPluginState()))
                    .compatibility(PluginCompatibility.DESCRIPTOR_INVALID).lastError(failure).build();
        }

        PluginCompatibility compatibility = descriptor == null
                ? PluginCompatibility.MISSING_EXTENSION
                : compatibilityChecker.check(descriptor,
                        wrapper.getDescriptor().getPluginId(), wrapper.getDescriptor().getVersion(),
                        1, seenIds);

        String stableId = descriptor == null ? pluginId : descriptor.getId();
        boolean enabled = enablement == null || enablement.isEnabled(stableId);

        if (compatibility == PluginCompatibility.COMPATIBLE && descriptor != null && enabled) {
            seenIds.add(descriptor.getId());
            if (hasWorkspace) {
                selectableById.put(descriptor.getId(), workspaceExtensions.get(0));
            }
            if (agentExtension != null) {
                String agentId = agentExtension.getAgentDescriptor().getId();
                selectableAgentById.put(agentId, agentExtension);
                agentDescriptors.put(agentId, agentExtension.getAgentDescriptor());
            }
        }

        return PluginCatalogEntry.builder()
                .pluginId(pluginId)
                .descriptor(descriptor)
                .compatibility(compatibility)
                .pluginState(String.valueOf(wrapper.getPluginState()))
                .location(location)
                .sha256(sha256)
                .enabled(enabled)
                .build();
    }

    /** Bridge an agent descriptor to the catalog's workspace descriptor (same fields) for display/compat. */
    private static WorkspacePluginDescriptor toWorkspaceDescriptor(
            com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor agent) {
        return WorkspacePluginDescriptor.builder()
                .id(agent.getId())
                .displayName(agent.getDisplayName())
                .description(agent.getDescription())
                .version(agent.getVersion())
                .pluginApiVersion(agent.getPluginApiVersion())
                .provider(agent.getProvider())
                .iconKey(agent.getIconKey())
                .displayOrder(agent.getDisplayOrder())
                .build();
    }

    private static String sha256Of(Path path) {
        if (path == null) {
            return "";
        }
        File file = path.toFile();
        if (!file.isFile()) {
            return ""; // dev-mode unpacked plugin directories have no single-file hash
        }
        InputStream in = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private static final class DaemonThreadFactory implements java.util.concurrent.ThreadFactory {
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-plugin-discovery");
            thread.setDaemon(true);
            return thread;
        }
    }
}
