package com.aresstack.audio.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Store one configurable block without coupling profile persistence to concrete DSP classes. */
public final class AudioBlockDefinition {

    private final String id;
    private final AudioBlockType type;
    private final boolean enabled;
    private final Map<String, String> parameters;

    public AudioBlockDefinition(String id, AudioBlockType type, boolean enabled,
                                Map<String, String> parameters) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Block id must not be empty.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Block type must not be null.");
        }
        this.id = id.trim();
        this.type = type;
        this.enabled = enabled;
        Map<String, String> copy = new LinkedHashMap<String, String>();
        if (parameters != null) {
            copy.putAll(parameters);
        }
        this.parameters = Collections.unmodifiableMap(copy);
    }

    public static AudioBlockDefinition of(String id, AudioBlockType type) {
        return new AudioBlockDefinition(id, type, true, defaultParameters(type));
    }

    public String getId() {
        return id;
    }

    public AudioBlockType getType() {
        return type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public String getParameter(String key, String fallback) {
        String value = parameters.get(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    public int getIntParameter(String key, int fallback) {
        try {
            return Integer.parseInt(getParameter(key, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public double getDoubleParameter(String key, double fallback) {
        try {
            return Double.parseDouble(getParameter(key, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public boolean getBooleanParameter(String key, boolean fallback) {
        String value = getParameter(key, String.valueOf(fallback));
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return fallback;
    }

    public AudioBlockDefinition withEnabled(boolean value) {
        return new AudioBlockDefinition(id, type, value, parameters);
    }

    public AudioBlockDefinition withType(AudioBlockType value) {
        return new AudioBlockDefinition(id, value, enabled, defaultParameters(value));
    }

    public AudioBlockDefinition withParameter(String key, String value) {
        Map<String, String> changed = new LinkedHashMap<String, String>(parameters);
        if (value == null) {
            changed.remove(key);
        } else {
            changed.put(key, value.trim());
        }
        return new AudioBlockDefinition(id, type, enabled, changed);
    }

    /**
     * @return the default parameters for a block type. The values come from the single source of truth —
     *         the block's descriptor in {@link com.aresstack.audio.pipeline.AudioBlockRegistry} — so there
     *         is no second per-type parameter switch to keep in sync.
     */
    public static Map<String, String> defaultParameters(AudioBlockType type) {
        return com.aresstack.audio.pipeline.AudioBlockRegistry.getInstance().defaultParameters(type);
    }
}
