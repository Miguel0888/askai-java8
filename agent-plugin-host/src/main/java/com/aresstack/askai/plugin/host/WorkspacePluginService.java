package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;
import com.aresstack.askai.plugin.pf4j.api.WorkspacePluginExtension;

import org.pf4j.PluginDescriptor;
import org.pf4j.PluginWrapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discovers workspace/agent plugins from a single controlled root, off the EDT, and publishes a validated
 * {@link PluginCatalogSnapshot} to all listeners on the EDT. It owns the PF4J runtime as a single
 * {@link PluginRuntimeGeneration}: a refresh builds a fresh candidate generation entirely in local variables,
 * validates it, and only if it succeeds atomically swaps it in — closing the outgoing generation's agent
 * sessions first and retiring (stop + unload) the previous generation afterwards. A failed candidate never
 * destroys the working generation.
 *
 * <p>Disable semantics: a disabled plugin is <em>loaded but never started</em>. Its {@code Plugin.start()} is
 * not called, it exposes no selectable extension, and the catalog shows {@code Enabled=false} with its honest
 * PF4J state. Enablement is keyed on the stable PF4J plugin id (the manifest {@code Plugin-Id}); for compatible
 * plugins that id equals the descriptor id (enforced by {@link PluginCompatibilityChecker}), so existing
 * persisted keys need no migration.</p>
 *
 * <p>A broken plugin is captured as a {@link PluginLoadFailure} row, never propagated, so the host and normal
 * chat still start; a global discovery/start failure keeps the previous generation and is reported as a global
 * failure with {@code generationFailed = true}.</p>
 */
public final class WorkspacePluginService {

    private final Path pluginsRoot;
    private final String systemVersion;
    private final UiExecutor uiExecutor;
    private final PluginCompatibilityChecker compatibilityChecker;
    private final PluginEnablementService enablement;
    private final PluginManagerFactory managerFactory;
    private final ExecutorService discoveryExecutor;
    private final CopyOnWriteArrayList<WorkspaceCatalogListener> listeners =
            new CopyOnWriteArrayList<WorkspaceCatalogListener>();

    /** The single active runtime generation; {@code null} until the first successful discovery. */
    private volatile PluginRuntimeGeneration activeGeneration;
    private volatile PluginCatalogSnapshot latestSnapshot =
            new PluginCatalogSnapshot(0L, Collections.<PluginCatalogEntry>emptyList(),
                    Collections.<PluginLoadFailure>emptyList(), 0L, false);

    /**
     * Invoked on the EDT immediately before a newly-built generation is published, so the host can close the
     * outgoing generation's agent sessions before its classloaders are retired. No classloader-crossing session
     * is ever reused; sessions are recreated lazily against the new generation.
     */
    private volatile Runnable generationSwapHook;

    // Lifecycle hardening: once shutting down, no new refresh is accepted or delivered; only the newest refresh
    // generation is applied so a stale off-EDT result can never overwrite a newer catalog.
    private final java.util.concurrent.atomic.AtomicBoolean shuttingDown =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong refreshGeneration =
            new java.util.concurrent.atomic.AtomicLong(0L);

    public WorkspacePluginService(Path pluginsRoot, String systemVersion, int supportedApiVersion,
                                  UiExecutor uiExecutor, PluginEnablementService enablement) {
        this(pluginsRoot, systemVersion, supportedApiVersion, uiExecutor, enablement, null);
    }

    /**
     * Package-private overload with an injectable {@link PluginManagerFactory}, used by tests to deterministically
     * simulate a global discovery failure (a manager whose {@code loadPlugins()} throws) and verify the previous
     * generation is kept. Production always uses the default factory (a fresh {@link AskAiPluginManager}).
     */
    WorkspacePluginService(Path pluginsRoot, String systemVersion, int supportedApiVersion,
                           UiExecutor uiExecutor, PluginEnablementService enablement,
                           PluginManagerFactory managerFactory) {
        this.pluginsRoot = pluginsRoot;
        this.systemVersion = systemVersion;
        this.uiExecutor = uiExecutor;
        this.enablement = enablement;
        this.managerFactory = managerFactory != null ? managerFactory : new PluginManagerFactory() {
            public AskAiPluginManager create() {
                return new AskAiPluginManager(pluginsRoot, systemVersion);
            }
        };
        this.compatibilityChecker = new PluginCompatibilityChecker(supportedApiVersion);
        this.discoveryExecutor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
    }

    /** Creates the PF4J manager for a candidate generation. A test seam for fault injection. */
    interface PluginManagerFactory {
        AskAiPluginManager create() throws Exception;
    }

    public void addCatalogListener(WorkspaceCatalogListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeCatalogListener(WorkspaceCatalogListener listener) {
        listeners.remove(listener);
    }

    /**
     * Set the hook invoked on the EDT just before a fresh generation is published (used to close the outgoing
     * generation's agent sessions). May be {@code null}.
     */
    public void setGenerationSwapHook(Runnable hook) {
        this.generationSwapHook = hook;
    }

    /**
     * Re-discovers and validates plugins off-EDT, then transactionally swaps in the new generation on the EDT.
     * Rejected once shutdown has begun. Concurrent refreshes are serialized on a single-thread executor and only
     * the newest generation is applied, so a slow discovery cannot overwrite a newer catalog or fire after
     * shutdown, and a refresh requested during shutdown is dropped.
     */
    public void refreshAsync() {
        if (shuttingDown.get()) {
            return;
        }
        final long generation = refreshGeneration.incrementAndGet();
        try {
            discoveryExecutor.execute(new Runnable() {
                public void run() {
                    if (stale(generation)) {
                        return;
                    }
                    final Candidate candidate = buildCandidate(generation);
                    if (stale(generation)) {
                        candidate.retireIfBuilt();
                        return;
                    }
                    uiExecutor.execute(new Runnable() {
                        public void run() {
                            if (stale(generation)) {
                                candidate.retireIfBuilt();
                                return;
                            }
                            publish(generation, candidate);
                        }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The executor was already shut down; nothing to deliver.
        }
    }

    private boolean stale(long generation) {
        return shuttingDown.get() || generation != refreshGeneration.get();
    }

    /** EDT: atomically install a successful candidate (or keep the previous generation on global failure). */
    private void publish(long generation, Candidate candidate) {
        PluginRuntimeGeneration previous = activeGeneration;
        long now = System.currentTimeMillis();
        if (candidate.generation == null) {
            // Global failure: the previous generation stays active and functional.
            List<PluginCatalogEntry> keptEntries = previous == null
                    ? Collections.<PluginCatalogEntry>emptyList() : previous.entries();
            PluginCatalogSnapshot snapshot = new PluginCatalogSnapshot(generation, keptEntries,
                    candidate.globalFailures, now, true);
            latestSnapshot = snapshot;
            deliver(snapshot);
            return;
        }
        // Success: close the outgoing generation's sessions before its classloaders are retired.
        Runnable hook = generationSwapHook;
        if (hook != null) {
            try {
                hook.run();
            } catch (RuntimeException ignored) {
                // best-effort; a listener failure must not abort the swap
            }
        }
        activeGeneration = candidate.generation;
        PluginCatalogSnapshot snapshot = new PluginCatalogSnapshot(generation,
                candidate.generation.entries(), candidate.generation.globalFailures(), now, false);
        latestSnapshot = snapshot;
        deliver(snapshot);
        if (previous != null) {
            final PluginRuntimeGeneration retiring = previous;
            try {
                discoveryExecutor.execute(new Runnable() {
                    public void run() {
                        retiring.retire();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                retiring.retire(); // executor gone (shutdown races): retire inline so nothing leaks
            }
        }
    }

    private void deliver(PluginCatalogSnapshot snapshot) {
        for (WorkspaceCatalogListener listener : listeners) {
            listener.onCatalogSnapshot(snapshot);
        }
    }

    /** @return the compatible, enabled workspace extension for a descriptor id, or {@code null}. */
    public WorkspacePluginExtension getSelectableExtension(String descriptorId) {
        PluginRuntimeGeneration generation = activeGeneration;
        return generation == null ? null : generation.selectableExtension(descriptorId);
    }

    /** @return the compatible, enabled <em>agent</em> extension for an agent id, or {@code null}. */
    public AgentPluginExtension getSelectableAgentExtension(String agentId) {
        PluginRuntimeGeneration generation = activeGeneration;
        return generation == null ? null : generation.selectableAgentExtension(agentId);
    }

    /** @return the descriptors of all selectable agents, in discovery order (for the Questing agent list). */
    public List<com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor> getSelectableAgentDescriptors() {
        PluginRuntimeGeneration generation = activeGeneration;
        return generation == null
                ? new ArrayList<com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor>()
                : generation.agentDescriptors();
    }

    public List<PluginCatalogEntry> getCatalog() {
        PluginRuntimeGeneration generation = activeGeneration;
        return generation == null ? Collections.<PluginCatalogEntry>emptyList() : generation.entries();
    }

    /** @return the most recent snapshot (generation id, entries, global failures, failed-refresh flag). */
    public PluginCatalogSnapshot getCatalogSnapshot() {
        return latestSnapshot;
    }

    /** @return the id of the currently active generation, or {@code 0} if none has been published. */
    public long getActiveGenerationId() {
        PluginRuntimeGeneration generation = activeGeneration;
        return generation == null ? 0L : generation.generationId();
    }

    /**
     * Serialized, idempotent shutdown. Order: mark shutting-down (so no refresh is accepted or delivered) → stop
     * the discovery executor → release listeners → retire the active generation (stop + unload each plugin
     * individually). Retiring one-by-one avoids PF4J's {@code stopPlugins()} CME; an error in one plugin is
     * isolated and does not stop the others.
     */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return; // idempotent
        }
        discoveryExecutor.shutdownNow();
        listeners.clear();
        PluginRuntimeGeneration generation = activeGeneration;
        activeGeneration = null;
        if (generation != null) {
            generation.retire();
        }
    }

    // ------------------------------------------------------------------ discovery (off-EDT)

    /** Result of an off-EDT candidate build: either a fully-built generation, or a global failure. */
    private static final class Candidate {
        private final PluginRuntimeGeneration generation; // null on global failure
        private final List<PluginLoadFailure> globalFailures;

        private Candidate(PluginRuntimeGeneration generation, List<PluginLoadFailure> globalFailures) {
            this.generation = generation;
            this.globalFailures = globalFailures;
        }

        void retireIfBuilt() {
            if (generation != null) {
                generation.retire();
            }
        }
    }

    /**
     * Build a fresh candidate generation off the EDT: new manager → load all plugins → start only <em>enabled</em>
     * plugins individually (keyed on the stable PF4J plugin id) → inspect started plugins and validate → add
     * honest disabled rows for loaded-but-not-started plugins. On a global load failure the half-built manager is
     * retired and a global failure is returned so the caller keeps the previous generation.
     */
    private Candidate buildCandidate(long generation) {
        AskAiPluginManager manager;
        try {
            manager = managerFactory.create();
            manager.loadPlugins();
        } catch (Exception | Error ex) {
            List<PluginLoadFailure> failures = new ArrayList<PluginLoadFailure>();
            failures.add(new PluginLoadFailure(String.valueOf(pluginsRoot), "",
                    PluginFailurePhase.EXTENSION_DISCOVERY, "Plugin discovery failed.", ex));
            return new Candidate(null, failures);
        }

        PluginRuntimeGeneration.Builder builder = PluginRuntimeGeneration.builder(generation, manager);

        // Decide enablement per plugin BEFORE starting anything, so a disabled plugin is never started.
        List<PluginWrapper> loaded = new ArrayList<PluginWrapper>();
        try {
            loaded.addAll(manager.getPlugins());
        } catch (RuntimeException | Error ex) {
            builder.build().retire(); // release the half-built manager; keep the previous generation
            List<PluginLoadFailure> failures = new ArrayList<PluginLoadFailure>();
            failures.add(new PluginLoadFailure(String.valueOf(pluginsRoot), "",
                    PluginFailurePhase.EXTENSION_DISCOVERY, "Could not enumerate loaded plugins.", ex));
            return new Candidate(null, failures);
        }

        Set<String> startedIds = new HashSet<String>();
        Set<String> failedStartIds = new HashSet<String>();
        for (PluginWrapper wrapper : loaded) {
            String pf4jId = wrapper.getPluginId();
            boolean enabled = enablement == null || enablement.isEnabled(pf4jId);
            if (!enabled) {
                continue; // loaded but not started; an honest disabled row is added below
            }
            try {
                manager.startPlugin(pf4jId);
                startedIds.add(pf4jId);
            } catch (RuntimeException | Error ex) {
                failedStartIds.add(pf4jId);
                builder.addEntry(disabledOrFailedRow(wrapper, true,
                        new PluginLoadFailure(String.valueOf(wrapper.getPluginPath()), pf4jId,
                                PluginFailurePhase.EXTENSION_DISCOVERY,
                                "The plugin failed to start.", ex)));
            }
        }

        Set<String> seenIds = new HashSet<String>();
        for (PluginWrapper wrapper : loaded) {
            String pf4jId = wrapper.getPluginId();
            if (startedIds.contains(pf4jId)) {
                inspectStarted(manager, wrapper, seenIds, builder);
            } else if (failedStartIds.contains(pf4jId)) {
                continue; // enabled but start failed: a failed row was already added above
            } else {
                builder.addEntry(disabledOrFailedRow(wrapper, false, null)); // honest disabled row
            }
        }
        PluginRuntimeGeneration built = builder.build();
        return new Candidate(built, built.globalFailures());
    }

    /** Inspect a started plugin: read its single extension, validate compatibility, register if selectable. */
    private void inspectStarted(AskAiPluginManager manager, PluginWrapper wrapper, Set<String> seenIds,
                                PluginRuntimeGeneration.Builder builder) {
        String pluginId = wrapper.getPluginId();
        String location = String.valueOf(wrapper.getPluginPath());
        String sha256 = sha256Of(wrapper.getPluginPath());

        List<WorkspacePluginExtension> workspaceExtensions;
        AgentPluginExtension agentExtension;
        try {
            workspaceExtensions = manager.getExtensions(WorkspacePluginExtension.class, pluginId);
            List<AgentPluginExtension> agentExtensions =
                    manager.getExtensions(AgentPluginExtension.class, pluginId);
            agentExtension = agentExtensions == null || agentExtensions.isEmpty()
                    ? null : agentExtensions.get(0);
        } catch (RuntimeException | Error ex) {
            PluginLoadFailure failure = new PluginLoadFailure(location, pluginId,
                    PluginFailurePhase.EXTENSION_DISCOVERY, "Could not load the plugin's extension.", ex);
            builder.addEntry(PluginCatalogEntry.builder().pluginId(pluginId).location(location).sha256(sha256)
                    .pluginState(String.valueOf(wrapper.getPluginState()))
                    .compatibility(PluginCompatibility.MISSING_EXTENSION).lastError(failure).build());
            return;
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
            builder.addEntry(PluginCatalogEntry.builder().pluginId(pluginId).location(location).sha256(sha256)
                    .pluginState(String.valueOf(wrapper.getPluginState()))
                    .compatibility(PluginCompatibility.DESCRIPTOR_INVALID).lastError(failure).build());
            return;
        }

        PluginCompatibility compatibility = descriptor == null
                ? PluginCompatibility.MISSING_EXTENSION
                : compatibilityChecker.check(descriptor,
                        wrapper.getDescriptor().getPluginId(), wrapper.getDescriptor().getVersion(),
                        1, seenIds);

        if (compatibility == PluginCompatibility.COMPATIBLE && descriptor != null) {
            seenIds.add(descriptor.getId());
            if (hasWorkspace) {
                builder.addSelectableWorkspace(descriptor.getId(), workspaceExtensions.get(0));
            }
            if (agentExtension != null) {
                String agentId = agentExtension.getAgentDescriptor().getId();
                builder.addSelectableAgent(agentId, agentExtension, agentExtension.getAgentDescriptor());
            }
        }

        builder.addEntry(PluginCatalogEntry.builder()
                .pluginId(pluginId)
                .descriptor(descriptor)
                .compatibility(compatibility)
                .pluginState(String.valueOf(wrapper.getPluginState()))
                .location(location)
                .sha256(sha256)
                .enabled(true)
                .build());
    }

    /** Build an honest row for a loaded-but-not-started plugin (disabled, or enabled-but-failed-to-start). */
    private static PluginCatalogEntry disabledOrFailedRow(PluginWrapper wrapper, boolean enabled,
                                                          PluginLoadFailure failure) {
        String location = String.valueOf(wrapper.getPluginPath());
        String sha256 = sha256Of(wrapper.getPluginPath());
        WorkspacePluginDescriptor descriptor = fromPf4jDescriptor(wrapper.getDescriptor());
        return PluginCatalogEntry.builder()
                .pluginId(wrapper.getPluginId())
                .descriptor(descriptor)
                .compatibility(failure == null ? PluginCompatibility.COMPATIBLE
                        : PluginCompatibility.MISSING_EXTENSION)
                .pluginState(String.valueOf(wrapper.getPluginState()))
                .location(location)
                .sha256(sha256)
                .enabled(enabled)
                .lastError(failure)
                .build();
    }

    /** Minimal display descriptor for a not-started plugin, from the PF4J manifest (no extension read). */
    private static WorkspacePluginDescriptor fromPf4jDescriptor(PluginDescriptor pf4j) {
        if (pf4j == null) {
            return null;
        }
        String id = pf4j.getPluginId() == null ? "" : pf4j.getPluginId();
        String description = pf4j.getPluginDescription();
        String displayName = description == null || description.trim().isEmpty() ? id : description;
        return WorkspacePluginDescriptor.builder()
                .id(id)
                .displayName(displayName)
                .description(description == null ? "" : description)
                .version(pf4j.getVersion() == null ? "" : pf4j.getVersion())
                .pluginApiVersion(1)
                .provider(pf4j.getProvider() == null ? "" : pf4j.getProvider())
                .displayOrder(0)
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
