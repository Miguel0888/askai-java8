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

    public static Map<String, String> defaultParameters(AudioBlockType type) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        switch (type) {
            case CHANNEL_MIXER:
                values.put("channels", "1");
                break;
            case LOW_PASS:
                values.put("implementation", "FIR_65");
                values.put("cutoffHz", "7200");
                values.put("order", "4");
                break;
            case HIGH_PASS:
                values.put("implementation", "LEGACY_IIR");
                values.put("cutoffHz", "80");
                values.put("order", "2");
                break;
            case BAND_PASS:
            case BAND_STOP:
                values.put("centerHz", "1000");
                values.put("widthHz", "500");
                values.put("order", "2");
                break;
            case RESAMPLER:
                values.put("targetRateHz", "16000");
                values.put("quality", "BALANCED");
                values.put("hiddenAntiAliasing", "false");
                break;
            case NOISE_GATE:
                values.put("threshold", "300");
                values.put("closedGain", "0.3");
                values.put("attackMillis", "5");
                values.put("releaseMillis", "150");
                break;
            case COMPRESSOR:
                values.put("threshold", "12000");
                values.put("ratio", "3");
                values.put("attackMillis", "5");
                values.put("releaseMillis", "100");
                break;
            case LIMITER:
                values.put("ceiling", "30000");
                break;
            case DC_OFFSET_REMOVAL:
                break;
            default:
                throw new IllegalArgumentException("Unsupported block type: " + type);
        }
        return values;
    }
}
