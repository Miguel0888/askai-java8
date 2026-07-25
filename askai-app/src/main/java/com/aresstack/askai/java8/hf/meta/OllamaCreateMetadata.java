package com.aresstack.askai.java8.hf.meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The typed, immutable set of metadata AskAI wants Ollama to record for an imported model. It is the
 * single place that decides which values are trusted enough to become functional {@code /api/create}
 * fields: {@link #toInfoMap()} emits only non-empty, sufficiently trusted values, so an uncertain guess
 * can never overwrite Ollama's own GGUF-derived detection.
 *
 * <p>Built through {@link Builder}. Callers that only have a capability list use
 * {@link #ofCapabilities(List)} and get byte-identical wire output to the previous capability-only path.</p>
 */
public final class OllamaCreateMetadata {

    private final List<String> capabilities;
    private final MetadataValue<String> modelFamily;
    private final MetadataValue<String> baseName;
    private final MetadataValue<String> quantizationLevel;
    private final MetadataValue<String> parameterSize;
    private final MetadataValue<Integer> contextLength;
    private final MetadataValue<Integer> embeddingLength;
    private final List<String> licenses;
    private final Map<String, Object> parameters;

    private OllamaCreateMetadata(Builder builder) {
        this.capabilities = immutable(builder.capabilities);
        this.modelFamily = builder.modelFamily;
        this.baseName = builder.baseName;
        this.quantizationLevel = builder.quantizationLevel;
        this.parameterSize = builder.parameterSize;
        this.contextLength = builder.contextLength;
        this.embeddingLength = builder.embeddingLength;
        this.licenses = immutable(builder.licenses);
        this.parameters = builder.parameters == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.parameters));
    }

    /** @return metadata carrying only the given (already normalized) capability tags. */
    public static OllamaCreateMetadata ofCapabilities(List<String> capabilities) {
        return new Builder().capabilities(capabilities).build();
    }

    /** @return metadata with nothing set — a manual GGUF import. */
    public static OllamaCreateMetadata empty() {
        return new Builder().build();
    }

    public List<String> capabilities() {
        return capabilities;
    }

    public List<String> licenses() {
        return licenses;
    }

    public Map<String, Object> parameters() {
        return parameters;
    }

    public MetadataValue<String> modelFamily() {
        return modelFamily;
    }

    public MetadataValue<String> quantizationLevel() {
        return quantizationLevel;
    }

    /**
     * @return the {@code info} object for {@code /api/create}, containing capabilities and every trusted,
     *         non-empty metadata field. Empty when nothing is known (the caller then omits {@code info}).
     */
    public Map<String, Object> toInfoMap() {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        if (!capabilities.isEmpty()) {
            info.put("capabilities", new ArrayList<String>(capabilities));
        }
        putTrustedText(info, "model_family", modelFamily);
        putTrustedText(info, "base_name", baseName);
        putTrustedText(info, "quantization_level", quantizationLevel);
        putTrustedText(info, "parameter_size", parameterSize);
        putTrustedPositiveInt(info, "context_length", contextLength);
        putTrustedPositiveInt(info, "embedding_length", embeddingLength);
        return info;
    }

    /** @return true when nothing at all would be written — a plain manual import. */
    public boolean isEmpty() {
        return toInfoMap().isEmpty() && licenses.isEmpty() && parameters.isEmpty();
    }

    private static void putTrustedText(Map<String, Object> info, String key, MetadataValue<String> value) {
        if (value != null && value.isTrusted(false) && value.value().trim().length() > 0) {
            info.put(key, value.value().trim());
        }
    }

    private static void putTrustedPositiveInt(Map<String, Object> info, String key,
                                              MetadataValue<Integer> value) {
        if (value != null && value.isTrusted(false) && value.value() != null && value.value() > 0) {
            info.put(key, value.value());
        }
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {
        private List<String> capabilities = Collections.emptyList();
        private MetadataValue<String> modelFamily;
        private MetadataValue<String> baseName;
        private MetadataValue<String> quantizationLevel;
        private MetadataValue<String> parameterSize;
        private MetadataValue<Integer> contextLength;
        private MetadataValue<Integer> embeddingLength;
        private List<String> licenses = Collections.emptyList();
        private Map<String, Object> parameters;

        public Builder capabilities(List<String> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder modelFamily(MetadataValue<String> value) {
            this.modelFamily = value;
            return this;
        }

        public Builder baseName(MetadataValue<String> value) {
            this.baseName = value;
            return this;
        }

        public Builder quantizationLevel(MetadataValue<String> value) {
            this.quantizationLevel = value;
            return this;
        }

        public Builder parameterSize(MetadataValue<String> value) {
            this.parameterSize = value;
            return this;
        }

        public Builder contextLength(MetadataValue<Integer> value) {
            this.contextLength = value;
            return this;
        }

        public Builder embeddingLength(MetadataValue<Integer> value) {
            this.embeddingLength = value;
            return this;
        }

        public Builder licenses(List<String> licenses) {
            this.licenses = licenses;
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }

        public OllamaCreateMetadata build() {
            return new OllamaCreateMetadata(this);
        }
    }
}
