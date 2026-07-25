package com.aresstack.askai.java8.hf.meta;

import com.aresstack.askai.java8.hf.HuggingFaceInstallPlan;
import io.github.ollama4j.json.OllamaJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the metadata for a frozen install plan's concrete repository + revision and maps the trusted
 * values into {@link OllamaCreateMetadata} for {@code /api/create}. Swing-free and driven only by the
 * frozen {@link HuggingFaceInstallPlan} (never by mutable UI state).
 *
 * <p>Every field is resolved from the highest-ranked source available: structured repository files
 * (config.json / generation_config.json) and GGUF metadata beat the HF model-info API, which beats card
 * metadata, which beats tags, which beats the file name. Conflicts are decided by {@link MetadataValue}
 * (source + confidence), never by "last read wins". Only sufficiently trusted values reach the wire.</p>
 */
public final class HuggingFaceMetadataLoader {

    /** Never treat a license file larger than this as text to send (avoid huge/binary blobs). */
    private static final int MAX_LICENSE_BYTES = 1024 * 1024;

    private final HuggingFaceMetadataGateway gateway;

    public HuggingFaceMetadataLoader(HuggingFaceMetadataGateway gateway) {
        if (gateway == null) {
            throw new IllegalArgumentException("gateway must not be null");
        }
        this.gateway = gateway;
    }

    public OllamaCreateMetadata load(HuggingFaceInstallPlan plan, String fileName) {
        String repo = plan.getRepositoryId();
        // Pin every download and metadata fetch to the same commit, so the file and its metadata match.
        String revision = plan.getPinnedRevision();

        Field<String> family = new Field<String>();
        // The registry is a transformation; the plan's model_type came from config.json → keep that source.
        family.offer(OllamaModelFamilyRegistry.familyValue(plan.getModelType(), MetadataSource.CONFIG_JSON));

        Field<String> quant = new Field<String>();
        quant.offer(GgufQuantization.fromFileNameValue(fileName)); // FILE_NAME / MEDIUM (provenance only)

        Field<String> baseName = new Field<String>();
        Field<String> parameterSize = new Field<String>();
        Field<Integer> contextLength = new Field<Integer>();
        Field<Integer> embeddingLength = new Field<Integer>();
        Field<List<String>> license = new Field<List<String>>();
        Map<String, MetadataValue<Object>> parameters = new LinkedHashMap<String, MetadataValue<Object>>();

        // --- HF model-info API (sha, baseModels, cardData, config, gguf, safetensors, tags) ----
        Map<String, Object> info = gateway.fetchModelInfo(repo, revision);

        // --- structured repository files (config.json direct, else info.config as a fallback) --
        Map<String, Object> config = parseJson(gateway.fetchFile(repo, revision, "config.json"));
        if (config == null && info != null) {
            config = asMap(info.get("config"));
        }
        if (config != null) {
            family.offer(OllamaModelFamilyRegistry.familyValue(string(config, "model_type"), MetadataSource.CONFIG_JSON));
            Integer ctx = firstInt(config, "max_position_embeddings", nested(config, "text_config"), "max_position_embeddings");
            contextLength.offer(positiveInt(ctx, MetadataSource.CONFIG_JSON));
            Integer hidden = firstInt(config, "hidden_size", nested(config, "text_config"), "hidden_size");
            embeddingLength.offer(positiveInt(hidden, MetadataSource.CONFIG_JSON));
        }

        Map<String, Object> generation = parseJson(gateway.fetchFile(repo, revision, "generation_config.json"));
        if (generation != null) {
            parameters.putAll(mapGenerationParameters(generation));
        }

        if (info != null) {
            Map<String, Object> gguf = asMap(info.get("gguf"));
            if (gguf != null) {
                // The gguf block is loosely typed across the HF API — accept several known key spellings.
                quant.offer(quantValue(firstString(gguf, "quantization", "quantization_level", "quant"),
                        MetadataSource.GGUF_METADATA));
                contextLength.offer(positiveInt(firstIntKey(gguf, "context_length", "n_ctx"),
                        MetadataSource.GGUF_METADATA));
                embeddingLength.offer(positiveInt(firstIntKey(gguf, "embedding_length", "n_embd"),
                        MetadataSource.GGUF_METADATA));
                parameterSize.offer(paramSizeValue(firstLongKey(gguf, "total", "parameters", "n_params"),
                        MetadataSource.GGUF_METADATA));
            }
            Map<String, Object> safetensors = asMap(info.get("safetensors"));
            if (safetensors != null) {
                parameterSize.offer(paramSizeValue(longOf(safetensors.get("total")), MetadataSource.HF_MODEL_API));
            }
            // Top-level baseModels is the authoritative HF-API base-model list (highest HF priority).
            baseName.offer(stringValue(baseModelOf(info.get("baseModels")), MetadataSource.HF_MODEL_API));
            Map<String, Object> card = asMap(info.get("cardData"));
            if (card != null) {
                baseName.offer(stringValue(baseModelOf(card.get("base_model")), MetadataSource.HF_CARD_DATA));
                license.offer(licenseValue(string(card, "license"), MetadataSource.HF_CARD_DATA));
            }
            baseName.offer(stringValue(baseModelFromTags(info.get("tags")), MetadataSource.TAG));
            license.offer(licenseValue(licenseFromTags(info.get("tags")), MetadataSource.TAG));
        }

        // --- license file (preferred when small enough to be real text) ------------------------
        license.offer(licenseFile(repo, revision));

        OllamaCreateMetadata.Builder builder = new OllamaCreateMetadata.Builder()
                .capabilities(new ArrayList<String>(plan.getRequiredOllamaCapabilities()))
                .modelFamily(family.best())
                .baseName(baseName.best())
                .quantizationLevel(quant.best())
                .parameterSize(parameterSize.best())
                .contextLength(contextLength.best())
                .embeddingLength(embeddingLength.best())
                .license(license.best());
        if (!parameters.isEmpty()) {
            builder.parameters(parameters);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ generation parameters

    /**
     * Maps only the generation-config keys that translate cleanly to Ollama parameters, with type and
     * range checks. Transformers-internal flags and numeric token ids are deliberately excluded.
     */
    private static Map<String, MetadataValue<Object>> mapGenerationParameters(Map<String, Object> generation) {
        Map<String, MetadataValue<Object>> result = new LinkedHashMap<String, MetadataValue<Object>>();
        offerDouble(result, generation, "temperature", "temperature", 0.0d, 5.0d);
        offerDouble(result, generation, "top_p", "top_p", 0.0d, 1.0d);
        offerInt(result, generation, "top_k", "top_k", 1, Integer.MAX_VALUE);
        offerDouble(result, generation, "min_p", "min_p", 0.0d, 1.0d);
        offerDouble(result, generation, "repetition_penalty", "repeat_penalty", 0.0d, 10.0d);
        offerInt(result, generation, "max_new_tokens", "num_predict", 1, Integer.MAX_VALUE);
        offerInt(result, generation, "seed", "seed", Integer.MIN_VALUE, Integer.MAX_VALUE);
        return result;
    }

    private static void offerDouble(Map<String, MetadataValue<Object>> out, Map<String, Object> source,
                                    String sourceKey, String targetKey, double min, double max) {
        Object raw = source.get(sourceKey);
        if (!(raw instanceof Number)) {
            return;
        }
        double value = ((Number) raw).doubleValue();
        if (value < min || value > max || Double.isNaN(value) || Double.isInfinite(value)) {
            return;
        }
        out.put(targetKey, MetadataValue.<Object>high(value, MetadataSource.GENERATION_CONFIG));
    }

    private static void offerInt(Map<String, MetadataValue<Object>> out, Map<String, Object> source,
                                 String sourceKey, String targetKey, int min, int max) {
        Integer value = intOf(source.get(sourceKey));
        if (value == null || value < min || value > max) {
            return;
        }
        out.put(targetKey, MetadataValue.<Object>high(value, MetadataSource.GENERATION_CONFIG));
    }

    // ------------------------------------------------------------------ value factories

    private MetadataValue<List<String>> licenseFile(String repo, String revision) {
        String text = firstNonBlank(gateway.fetchFile(repo, revision, "LICENSE"),
                gateway.fetchFile(repo, revision, "LICENSE.md"));
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.getBytes().length > MAX_LICENSE_BYTES) {
            return null; // too large or empty to treat as a real license string
        }
        if (trimmed.toLowerCase(Locale.ROOT).contains("<html")) {
            return null; // a proxy/HTML page, not a license
        }
        return MetadataValue.high(singletonList(trimmed), MetadataSource.CONFIG_JSON);
    }

    private static MetadataValue<List<String>> licenseValue(String licenseId, MetadataSource source) {
        String id = licenseId == null ? "" : licenseId.trim();
        if (id.isEmpty() || "other".equalsIgnoreCase(id) || "unknown".equalsIgnoreCase(id)) {
            return null;
        }
        return MetadataValue.high(singletonList(id), source);
    }

    private static MetadataValue<String> stringValue(String value, MetadataSource source) {
        return (value == null || value.trim().isEmpty()) ? null : MetadataValue.high(value.trim(), source);
    }

    private static MetadataValue<String> quantValue(String value, MetadataSource source) {
        return (value == null || value.trim().isEmpty()) ? null
                : MetadataValue.high(value.trim().toUpperCase(Locale.ROOT), source);
    }

    private static MetadataValue<Integer> positiveInt(Integer value, MetadataSource source) {
        return (value == null || value <= 0) ? null : MetadataValue.high(value, source);
    }

    private static MetadataValue<String> paramSizeValue(Long count, MetadataSource source) {
        String formatted = formatParameterSize(count);
        return formatted == null ? null : MetadataValue.high(formatted, source);
    }

    /** Formats a parameter count into Ollama's short form (e.g. 8_030_000_000 → "8B", 270_000_000 → "270M"). */
    static String formatParameterSize(Long count) {
        if (count == null || count <= 0L) {
            return null;
        }
        double billions = count / 1.0e9d;
        if (billions >= 1.0d) {
            return trimZero(billions) + "B";
        }
        double millions = count / 1.0e6d;
        if (millions >= 1.0d) {
            return trimZero(millions) + "M";
        }
        return count.toString();
    }

    private static Long longOf(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static String trimZero(double value) {
        double rounded = Math.round(value * 10.0d) / 10.0d;
        if (rounded == Math.floor(rounded)) {
            return String.valueOf((long) rounded);
        }
        return String.valueOf(rounded);
    }

    // ------------------------------------------------------------------ JSON helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            Object parsed = OllamaJson.parse(text);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> map, String key) {
        return map == null ? null : asMap(map.get(key));
    }

    private static String string(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** The HF {@code gguf} block is loosely typed; try several known key spellings, in order. */
    private static String firstString(Map<String, Object> map, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            String value = string(map, keys[i]);
            if (value.length() > 0) {
                return value;
            }
        }
        return "";
    }

    private static Integer firstIntKey(Map<String, Object> map, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            Integer value = intOf(map.get(keys[i]));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long firstLongKey(Map<String, Object> map, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            Long value = longOf(map.get(keys[i]));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer firstInt(Map<String, Object> primary, String primaryKey,
                                    Map<String, Object> secondary, String secondaryKey) {
        Integer value = primary == null ? null : intOf(primary.get(primaryKey));
        if (value != null) {
            return value;
        }
        return secondary == null ? null : intOf(secondary.get(secondaryKey));
    }

    private static Integer intOf(Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d != Math.floor(d) || Double.isInfinite(d) || d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) {
                // parameter counts can exceed int; clamp via long where needed
                long asLong = (long) d;
                return asLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) asLong;
            }
            return (int) d;
        }
        if (value instanceof String) {
            try {
                return (int) Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static String baseModelOf(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.size() == 1 && list.get(0) != null) {
                return String.valueOf(list.get(0)); // only an unambiguous single base model
            }
        }
        return null;
    }

    private static String baseModelFromTags(Object tags) {
        String single = null;
        if (tags instanceof List) {
            for (Object tag : (List<?>) tags) {
                String text = String.valueOf(tag);
                if (text.startsWith("base_model:")) {
                    String candidate = text.substring("base_model:".length());
                    int colon = candidate.indexOf(':'); // e.g. base_model:quantized:owner/model
                    if (colon >= 0) {
                        candidate = candidate.substring(colon + 1);
                    }
                    if (single != null && !single.equals(candidate)) {
                        return null; // more than one distinct base model → not unambiguous
                    }
                    single = candidate;
                }
            }
        }
        return single;
    }

    private static String licenseFromTags(Object tags) {
        if (tags instanceof List) {
            for (Object tag : (List<?>) tags) {
                String text = String.valueOf(tag);
                if (text.startsWith("license:")) {
                    return text.substring("license:".length());
                }
            }
        }
        return null;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && first.trim().length() > 0) {
            return first;
        }
        return second != null && second.trim().length() > 0 ? second : null;
    }

    private static List<String> singletonList(String value) {
        List<String> list = new ArrayList<String>(1);
        list.add(value);
        return list;
    }

    /** Keeps the single best-ranked candidate for one metadata field. */
    private static final class Field<T> {
        private MetadataValue<T> best;

        void offer(MetadataValue<T> candidate) {
            if (candidate != null && candidate.value() != null && candidate.outranks(best)) {
                best = candidate;
            }
        }

        MetadataValue<T> best() {
            return best;
        }
    }
}
