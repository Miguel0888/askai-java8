package com.aresstack.askai.java8.batch.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable event describing the asynchronously resolved batch selection catalog. */
public final class BatchSelectionCatalogLoadedEvent {

    private final List<String> audioModelNames;
    private final boolean successful;
    private final String message;

    private BatchSelectionCatalogLoadedEvent(List<String> audioModelNames, boolean successful,
                                             String message) {
        this.audioModelNames = audioModelNames == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(audioModelNames));
        this.successful = successful;
        this.message = message == null ? "" : message;
    }

    public static BatchSelectionCatalogLoadedEvent loaded(List<String> audioModelNames) {
        return new BatchSelectionCatalogLoadedEvent(audioModelNames, true, "");
    }

    public static BatchSelectionCatalogLoadedEvent failed(String message) {
        return new BatchSelectionCatalogLoadedEvent(Collections.<String>emptyList(), false, message);
    }

    /** Only models whose Ollama {@code /api/show} capabilities contain the exact {@code audio} capability. */
    public List<String> getAudioModelNames() { return audioModelNames; }

    public boolean isSuccessful() { return successful; }

    public String getMessage() { return message; }
}
