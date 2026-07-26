package com.aresstack.audio.pipeline;

import java.util.HashMap;
import java.util.Map;

/**
 * Carry shared, per-run metadata between blocks of one pipeline execution (for example a learned noise
 * profile or a speech-probability track produced by an analysis block and consumed by a later block).
 *
 * <p>This is transient runtime state for a single {@code process(...)} call — it is never persisted into a
 * profile. Phase 1 ships it empty but real, so later analysis/adaptive blocks have a defined channel.</p>
 */
public final class AudioProcessingContext {

    private final Map<String, Object> metadata = new HashMap<String, Object>();

    public void put(String key, Object value) {
        metadata.put(key, value);
    }

    public Object get(String key) {
        return metadata.get(key);
    }

    public boolean has(String key) {
        return metadata.containsKey(key);
    }
}
