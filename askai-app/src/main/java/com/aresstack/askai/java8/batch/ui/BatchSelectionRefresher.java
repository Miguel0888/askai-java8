package com.aresstack.askai.java8.batch.ui;

import com.aresstack.askai.java8.batch.service.BatchProfileCatalogLoadedEvent;
import com.aresstack.askai.java8.batch.service.BatchSelectionCatalogLoadedEvent;

import java.util.function.Consumer;

/**
 * UI-facing port the {@link BatchTranscriptionPanel} uses to reload its two selection catalogs. Both loads
 * run off the EDT and deliver their result (success or failure) to the callback; the panel marshals the
 * application back onto the EDT. Keeps the panel free of any concrete model/profile or file-system class.
 */
public interface BatchSelectionRefresher {

    /** Reload the audio-capable model names (same {@code audio}-capability rule as the initial load). */
    void loadModels(Consumer<BatchSelectionCatalogLoadedEvent> callback);

    /** Reload the audio-processing profiles from their source. */
    void loadProfiles(Consumer<BatchProfileCatalogLoadedEvent> callback);
}
