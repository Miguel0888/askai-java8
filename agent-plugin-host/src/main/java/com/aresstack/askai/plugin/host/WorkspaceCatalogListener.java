package com.aresstack.askai.plugin.host;

import java.util.List;

/**
 * Receives the built plugin catalog on the EDT after async discovery. The rich {@link #onCatalogSnapshot} form
 * carries the full {@link PluginCatalogSnapshot} (generation id, global failures, whether a refresh failed and
 * the previous generation was kept); it defaults to the legacy {@link #onCatalogReady} form so existing
 * listeners keep working unchanged.
 */
public interface WorkspaceCatalogListener {

    /** Legacy form: catalog rows plus captured failures (global failures included in {@code failures}). */
    void onCatalogReady(List<PluginCatalogEntry> catalog, List<PluginLoadFailure> failures);

    /** Rich form; defaults to {@link #onCatalogReady}. Override to see the generation id / failed-refresh flag. */
    default void onCatalogSnapshot(PluginCatalogSnapshot snapshot) {
        onCatalogReady(snapshot.getEntries(), snapshot.getGlobalFailures());
    }
}
