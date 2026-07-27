package com.aresstack.askai.plugin.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable result of one discovery/refresh: the id of the runtime generation that produced it, the catalog
 * rows, any global (non-plugin-specific) discovery/start failures, and the completion timestamp. Delivered to
 * {@link WorkspaceCatalogListener}s so plugin management can show both per-plugin failure rows and global
 * failures, and distinguish "a fresh generation was activated" from "refresh failed; previous generation
 * remains active" (the latter carries {@code generationFailed = true} with a non-empty {@link #getGlobalFailures()}).
 */
public final class PluginCatalogSnapshot {

    private final long generationId;
    private final List<PluginCatalogEntry> entries;
    private final List<PluginLoadFailure> globalFailures;
    private final long completedAt;
    private final boolean generationFailed;

    public PluginCatalogSnapshot(long generationId, List<PluginCatalogEntry> entries,
                                 List<PluginLoadFailure> globalFailures, long completedAt,
                                 boolean generationFailed) {
        this.generationId = generationId;
        this.entries = Collections.unmodifiableList(new ArrayList<PluginCatalogEntry>(
                entries == null ? Collections.<PluginCatalogEntry>emptyList() : entries));
        this.globalFailures = Collections.unmodifiableList(new ArrayList<PluginLoadFailure>(
                globalFailures == null ? Collections.<PluginLoadFailure>emptyList() : globalFailures));
        this.completedAt = completedAt;
        this.generationFailed = generationFailed;
    }

    public long getGenerationId() {
        return generationId;
    }

    public List<PluginCatalogEntry> getEntries() {
        return entries;
    }

    /** @return global (non-plugin-specific) discovery/start failures; empty when discovery succeeded. */
    public List<PluginLoadFailure> getGlobalFailures() {
        return globalFailures;
    }

    public long getCompletedAt() {
        return completedAt;
    }

    /**
     * @return true when this refresh's candidate generation could not be built and the previously active
     *         generation was kept; the entries then describe the still-active previous generation.
     */
    public boolean isGenerationFailed() {
        return generationFailed;
    }
}
