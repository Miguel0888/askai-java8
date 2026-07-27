package com.aresstack.askai.plugin.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The structured outcome of retiring a {@link PluginRuntimeGeneration}: which plugins were stopped and unloaded,
 * and any stop/unload errors keyed by plugin id. A generation is only {@link #isComplete() complete} when every
 * loaded plugin was unloaded with no errors; an incomplete retirement is kept for a later retry so a plugin that
 * still holds its classloader or JAR lock is never silently forgotten.
 */
public final class GenerationRetirementResult {

    private final List<String> stopped;
    private final List<String> unloaded;
    private final Map<String, String> stopFailures;
    private final Map<String, String> unloadFailures;
    private final boolean complete;

    GenerationRetirementResult(List<String> stopped, List<String> unloaded,
                               Map<String, String> stopFailures, Map<String, String> unloadFailures,
                               boolean complete) {
        this.stopped = Collections.unmodifiableList(new ArrayList<String>(stopped));
        this.unloaded = Collections.unmodifiableList(new ArrayList<String>(unloaded));
        this.stopFailures = Collections.unmodifiableMap(new LinkedHashMap<String, String>(stopFailures));
        this.unloadFailures = Collections.unmodifiableMap(new LinkedHashMap<String, String>(unloadFailures));
        this.complete = complete;
    }

    public List<String> getStopped() {
        return stopped;
    }

    public List<String> getUnloaded() {
        return unloaded;
    }

    public Map<String, String> getStopFailures() {
        return stopFailures;
    }

    public Map<String, String> getUnloadFailures() {
        return unloadFailures;
    }

    public boolean isComplete() {
        return complete;
    }

    public boolean hasFailures() {
        return !stopFailures.isEmpty() || !unloadFailures.isEmpty();
    }
}
