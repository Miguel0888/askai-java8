package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;
import com.aresstack.askai.plugin.pf4j.api.WorkspacePluginExtension;

import org.pf4j.PluginWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One immutable, fully-built PF4J runtime generation: its {@link AskAiPluginManager}, the validated catalog it
 * produced, the selectable (enabled + compatible) extension maps, and any global (non-plugin-specific) discovery
 * failures. A generation is assembled entirely in local variables by {@link WorkspacePluginService} and only
 * published as the single {@code activeGeneration} once it is complete, so a failed candidate can never destroy a
 * working generation.
 *
 * <p>Exactly one generation is active at a time. When a newer generation is published the previous one is
 * {@linkplain #retire() retired} — every plugin stopped and unloaded individually — so no old
 * {@code AskAiPluginManager}, classloader or JAR lock is ever leaked.</p>
 */
final class PluginRuntimeGeneration {

    private final long generationId;
    private final AskAiPluginManager pluginManager;
    private final List<PluginCatalogEntry> entries;
    private final Map<String, WorkspacePluginExtension> selectableById;
    private final Map<String, AgentPluginExtension> selectableAgentById;
    private final Map<String, AgentPluginDescriptor> agentDescriptors;
    private final List<PluginLoadFailure> globalFailures;

    private PluginRuntimeGeneration(long generationId, AskAiPluginManager pluginManager,
                                    List<PluginCatalogEntry> entries,
                                    Map<String, WorkspacePluginExtension> selectableById,
                                    Map<String, AgentPluginExtension> selectableAgentById,
                                    Map<String, AgentPluginDescriptor> agentDescriptors,
                                    List<PluginLoadFailure> globalFailures) {
        this.generationId = generationId;
        this.pluginManager = pluginManager;
        this.entries = Collections.unmodifiableList(new ArrayList<PluginCatalogEntry>(entries));
        this.selectableById = Collections.unmodifiableMap(
                new LinkedHashMap<String, WorkspacePluginExtension>(selectableById));
        this.selectableAgentById = Collections.unmodifiableMap(
                new LinkedHashMap<String, AgentPluginExtension>(selectableAgentById));
        this.agentDescriptors = Collections.unmodifiableMap(
                new LinkedHashMap<String, AgentPluginDescriptor>(agentDescriptors));
        this.globalFailures = Collections.unmodifiableList(new ArrayList<PluginLoadFailure>(globalFailures));
    }

    long generationId() {
        return generationId;
    }

    List<PluginCatalogEntry> entries() {
        return entries;
    }

    List<PluginLoadFailure> globalFailures() {
        return globalFailures;
    }

    WorkspacePluginExtension selectableExtension(String descriptorId) {
        return descriptorId == null ? null : selectableById.get(descriptorId);
    }

    AgentPluginExtension selectableAgentExtension(String agentId) {
        return agentId == null ? null : selectableAgentById.get(agentId);
    }

    List<AgentPluginDescriptor> agentDescriptors() {
        return new ArrayList<AgentPluginDescriptor>(agentDescriptors.values());
    }

    /**
     * Stop and unload every plugin of this generation individually over a stable copy of the id list. Stopping
     * one-by-one avoids PF4J {@code stopPlugins()} iterating its own started-plugins list while mutating it (the
     * {@link java.util.ConcurrentModificationException} seen previously); an error in one plugin is isolated so
     * the rest are still retired and their classloaders/JAR locks are released. The returned
     * {@link GenerationRetirementResult} reports exactly what succeeded and what failed, so an incomplete
     * retirement can be retried instead of being silently forgotten.
     */
    GenerationRetirementResult retire() {
        return retire(opsFor(pluginManager));
    }

    /** Retire a raw (possibly half-built) manager — used to clean up a candidate whose build failed. */
    static GenerationRetirementResult retireManager(AskAiPluginManager manager) {
        return retire(opsFor(manager));
    }

    /** The stop/unload steps expressed over a small seam so the result logic is unit-testable without PF4J. */
    interface RetireOps {
        List<String> startedIds();

        List<String> loadedIds();

        void stop(String pluginId);

        /** @return whether the manager confirms the plugin was unloaded. */
        boolean unload(String pluginId);
    }

    /**
     * Retire per plugin, not in two independent passes: a started plugin is stopped and the stop <em>confirmed</em>
     * (no longer among the started ids) before it is unloaded — so a plugin whose {@code stop()} failed and whose
     * threads/objects may still be live never has its classloader unloaded; it is kept for a retry instead. Unload
     * checks the manager's boolean return and re-confirms the plugin is no longer loaded. The generation is only
     * {@code complete} when every originally-loaded plugin is confirmed neither started nor loaded — not merely
     * "no exception was thrown this attempt".
     */
    static GenerationRetirementResult retire(RetireOps ops) {
        List<String> stopped = new ArrayList<String>();
        List<String> unloaded = new ArrayList<String>();
        java.util.LinkedHashMap<String, String> stopFailures = new java.util.LinkedHashMap<String, String>();
        java.util.LinkedHashMap<String, String> unloadFailures = new java.util.LinkedHashMap<String, String>();

        List<String> originallyLoaded;
        try {
            originallyLoaded = new ArrayList<String>(ops.loadedIds());
        } catch (RuntimeException | Error ex) {
            unloadFailures.put("<enumerate-loaded>", message(ex));
            return new GenerationRetirementResult(stopped, unloaded, stopFailures, unloadFailures, false);
        }
        Set<String> started;
        try {
            started = new HashSet<String>(ops.startedIds());
        } catch (RuntimeException | Error ex) {
            started = new HashSet<String>(originallyLoaded); // assume the worst: treat all as still started
            stopFailures.put("<enumerate-started>", message(ex));
        }

        for (String id : originallyLoaded) {
            if (started.contains(id)) {
                try {
                    ops.stop(id);
                } catch (RuntimeException | Error ex) {
                    stopFailures.put(id, message(ex));
                    continue; // NEVER unload a plugin whose stop failed
                }
                if (isStillStarted(ops, id)) {
                    stopFailures.put(id, "stop not confirmed");
                    continue; // stop unconfirmed → keep the classloader loaded, retry later
                }
                stopped.add(id);
            }
            try {
                if (!ops.unload(id)) {
                    unloadFailures.put(id, "unloadPlugin returned false");
                    continue;
                }
            } catch (RuntimeException | Error ex) {
                unloadFailures.put(id, message(ex));
                continue;
            }
            if (isStillLoaded(ops, id)) {
                unloadFailures.put(id, "unload not confirmed");
            } else {
                unloaded.add(id);
            }
        }

        boolean complete = true;
        Set<String> finalStarted = safeSet(ops, true, originallyLoaded);
        Set<String> finalLoaded = safeSet(ops, false, originallyLoaded);
        for (String id : originallyLoaded) {
            if (finalStarted.contains(id) || finalLoaded.contains(id)) {
                complete = false;
                break;
            }
        }
        return new GenerationRetirementResult(stopped, unloaded, stopFailures, unloadFailures, complete);
    }

    private static boolean isStillStarted(RetireOps ops, String id) {
        try {
            return ops.startedIds().contains(id);
        } catch (RuntimeException | Error ex) {
            return true; // cannot confirm → assume still started
        }
    }

    private static boolean isStillLoaded(RetireOps ops, String id) {
        try {
            return ops.loadedIds().contains(id);
        } catch (RuntimeException | Error ex) {
            return true; // cannot confirm → assume still loaded
        }
    }

    private static Set<String> safeSet(RetireOps ops, boolean started, List<String> fallback) {
        try {
            return new HashSet<String>(started ? ops.startedIds() : ops.loadedIds());
        } catch (RuntimeException | Error ex) {
            return new HashSet<String>(fallback); // cannot confirm → assume everything is still present
        }
    }

    private static RetireOps opsFor(final AskAiPluginManager manager) {
        return new RetireOps() {
            public List<String> startedIds() {
                List<String> ids = new ArrayList<String>();
                if (manager != null) {
                    for (PluginWrapper wrapper : manager.getStartedPlugins()) {
                        ids.add(wrapper.getPluginId());
                    }
                }
                return ids;
            }

            public List<String> loadedIds() {
                List<String> ids = new ArrayList<String>();
                if (manager != null) {
                    for (PluginWrapper wrapper : manager.getPlugins()) {
                        ids.add(wrapper.getPluginId());
                    }
                }
                return ids;
            }

            public void stop(String pluginId) {
                manager.stopPlugin(pluginId);
            }

            public boolean unload(String pluginId) {
                return manager.unloadPlugin(pluginId);
            }
        };
    }

    private static String message(Throwable ex) {
        String m = ex.getMessage();
        return ex.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    static Builder builder(long generationId, AskAiPluginManager pluginManager) {
        return new Builder(generationId, pluginManager);
    }

    /** Mutable assembler used off-EDT while a candidate generation is being built. */
    static final class Builder {
        private final long generationId;
        private final AskAiPluginManager pluginManager;
        private final List<PluginCatalogEntry> entries = new ArrayList<PluginCatalogEntry>();
        private final Map<String, WorkspacePluginExtension> selectableById =
                new LinkedHashMap<String, WorkspacePluginExtension>();
        private final Map<String, AgentPluginExtension> selectableAgentById =
                new LinkedHashMap<String, AgentPluginExtension>();
        private final Map<String, AgentPluginDescriptor> agentDescriptors =
                new LinkedHashMap<String, AgentPluginDescriptor>();
        private final List<PluginLoadFailure> globalFailures = new ArrayList<PluginLoadFailure>();

        private Builder(long generationId, AskAiPluginManager pluginManager) {
            this.generationId = generationId;
            this.pluginManager = pluginManager;
        }

        AskAiPluginManager pluginManager() {
            return pluginManager;
        }

        void addEntry(PluginCatalogEntry entry) {
            entries.add(entry);
        }

        void addSelectableWorkspace(String descriptorId, WorkspacePluginExtension extension) {
            selectableById.put(descriptorId, extension);
        }

        void addSelectableAgent(String agentId, AgentPluginExtension extension,
                                AgentPluginDescriptor descriptor) {
            selectableAgentById.put(agentId, extension);
            agentDescriptors.put(agentId, descriptor);
        }

        void addGlobalFailure(PluginLoadFailure failure) {
            globalFailures.add(failure);
        }

        PluginRuntimeGeneration build() {
            return new PluginRuntimeGeneration(generationId, pluginManager, entries, selectableById,
                    selectableAgentById, agentDescriptors, globalFailures);
        }
    }
}
