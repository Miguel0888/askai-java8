package com.aresstack.askai.plugin.host;

import java.util.List;

/** Receives the built plugin catalog (and captured failures) on the EDT after async discovery. */
public interface WorkspaceCatalogListener {

    void onCatalogReady(List<PluginCatalogEntry> catalog, List<PluginLoadFailure> failures);
}
