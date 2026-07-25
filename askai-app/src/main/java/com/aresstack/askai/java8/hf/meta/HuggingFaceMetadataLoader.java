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

    /** The enriched metadata plus its provenance ledger. */
    public static final class Result {
        private final OllamaCreateMetadata metadata;
        private final HuggingFaceImportProvenance provenance;

        Result(OllamaCreateMetadata metadata, HuggingFaceImportProvenance provenance) {
            this.metadata = metadata;
            this.provenance = provenance;
        }

        public OllamaCreateMetadata metadata() {
            return metadata;
        }

        public HuggingFaceImportProvenance provenance() {
            return provenance;
        }
    }

    public OllamaCreateMetadata load(HuggingFaceInstallPlan plan, String fileName) {
        return loadWithProvenance(plan, fileName, "", 0L).metadata();
    }

    /**
     * Loads the metadata and, alongside it, the full provenance ledger (repository provenance, per-field
     * source/confidence and a copy of what will be sent to {@code /api/create}) for the audit sidecar.
     */
    public Result loadWithProvenance(HuggingFaceInstallPlan plan, String fileName, String fileSha256, long fileSize) {
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

        // --- chat template + tokenizer config (only to DETECT capabilities, never to send raw) ---
        String tokenizerConfigRaw = gateway.fetchFile(repo, revision, "tokenizer_config.json");
        String chatTemplate = extractChatTemplate(parseJson(tokenizerConfigRaw));
        String chatTemplateFile = gateway.fetchFile(repo, revision, "chat_template.jinja");
        if (chatTemplateFile != null) {
            chatTemplate = chatTemplate + "\n" + chatTemplateFile;
        }

        // Capabilities: the plan's HF modalities PLUS capabilities derived only from structured sources
        // (pipeline_tag, the actual chat template, the tokenizer config) — never from names or README text.
        java.util.LinkedHashSet<String> capabilityTags =
                new java.util.LinkedHashSet<String>(plan.getRequiredOllamaCapabilities());
        capabilityTags.addAll(deriveStructuredCapabilities(info, chatTemplate, tokenizerConfigRaw));

        OllamaCreateMetadata.Builder builder = new OllamaCreateMetadata.Builder()
                .capabilities(new ArrayList<String>(capabilityTags))
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
        // B3: a tested per-family create profile may set renderer/parser/requires. template/system/messages
        // are intentionally not derived (GGUF template wins; HF Jinja needs a separate tested converter).
        applyCreateProfile(builder, family.best());
        OllamaCreateMetadata metadata = builder.build();

        HuggingFaceImportProvenance provenance = buildProvenance(plan, fileName, fileSha256, fileSize, info,
                metadata, family.best(), baseName.best(), quant.best(), parameterSize.best(),
                contextLength.best(), embeddingLength.best(), license.best());
        return new Result(metadata, provenance);
    }

    private HuggingFaceImportProvenance buildProvenance(HuggingFaceInstallPlan plan, String fileName,
            String fileSha256, long fileSize, Map<String, Object> info, OllamaCreateMetadata metadata,
            MetadataValue<String> family, MetadataValue<String> baseName, MetadataValue<String> quant,
            MetadataValue<String> parameterSize, MetadataValue<Integer> contextLength,
            MetadataValue<Integer> embeddingLength, MetadataValue<List<String>> license) {
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("repositoryId", plan.getRepositoryId());
        document.put("author", info == null ? "" : string(info, "author"));
        document.put("requestedRevision", plan.getRevision());
        document.put("resolvedRevisionSha", plan.getResolvedRevisionSha());
        document.put("selectedFilePath", fileName);
        document.put("selectedFileSize", fileSize);
        document.put("selectedFileSha256", fileSha256 == null ? "" : fileSha256);
        document.put("pipelineTag", info == null ? "" : string(info, "pipeline_tag"));
        document.put("libraryName", info == null ? "" : string(info, "library_name"));
        document.put("baseModels", info == null ? new ArrayList<Object>() : listOrEmpty(info.get("baseModels")));
        document.put("rawTags", info == null ? new ArrayList<Object>() : listOrEmpty(info.get("tags")));
        document.put("gated", info == null ? Boolean.FALSE : boolOf(info.get("gated")));
        document.put("private", info == null ? Boolean.FALSE : boolOf(info.get("private")));

        // Per-field source/confidence ledger for the resolved values.
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        putSource(fields, "model_family", family);
        putSource(fields, "base_name", baseName);
        putSource(fields, "quantization_level", quant);
        putSource(fields, "parameter_size", parameterSize);
        putSource(fields, "context_length", contextLength);
        putSource(fields, "embedding_length", embeddingLength);
        putSource(fields, "license", license);
        document.put("fields", fields);

        // A copy of exactly what will be sent to /api/create (never used for the installed display).
        Map<String, Object> sent = new LinkedHashMap<String, Object>(metadata.toInfoMap());
        if (!metadata.licenses().isEmpty()) {
            sent.put("license", metadata.licenses());
        }
        if (!metadata.parameters().isEmpty()) {
            sent.put("parameters", metadata.parameters());
        }
        putIfPresent(sent, "renderer", metadata.renderer());
        putIfPresent(sent, "parser", metadata.parser());
        putIfPresent(sent, "requires", metadata.requires());
        putIfPresent(sent, "template", metadata.template());
        putIfPresent(sent, "system", metadata.system());
        document.put("sentCreateMetadata", sent);

        return new HuggingFaceImportProvenance(document);
    }

    private static void putSource(Map<String, Object> fields, String key, MetadataValue<?> value) {
        if (value == null || value.value() == null) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("value", String.valueOf(value.value()));
        record.put("source", value.source().name());
        record.put("confidence", value.confidence().name());
        fields.put(key, record);
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && value.length() > 0) {
            map.put(key, value);
        }
    }

    private static java.util.List<Object> listOrEmpty(Object value) {
        return value instanceof java.util.List ? new ArrayList<Object>((java.util.List<?>) value)
                : new ArrayList<Object>();
    }

    private static Boolean boolOf(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.valueOf("true".equalsIgnoreCase(String.valueOf(value)));
    }

    private static void applyCreateProfile(OllamaCreateMetadata.Builder builder, MetadataValue<String> family) {
        if (family == null || family.value() == null) {
            return;
        }
        OllamaCreateProfile profile = OllamaCreateProfileRegistry.profileFor(family.value());
        if (profile == null) {
            return;
        }
        if (profile.renderer().length() > 0) {
            builder.renderer(MetadataValue.high(profile.renderer(), MetadataSource.REGISTRY));
        }
        if (profile.parser().length() > 0) {
            builder.parser(MetadataValue.high(profile.parser(), MetadataSource.REGISTRY));
        }
        if (profile.requires().length() > 0) {
            builder.requires(MetadataValue.high(profile.requires(), MetadataSource.REGISTRY));
        }
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
        offerDouble(result, generation, "typical_p", "typical_p", 0.0d, 1.0d);
        offerStops(result, generation.get("stop_strings"));
        return result;
    }

    /**
     * Maps HF {@code stop_strings} (a string or a list of strings) to Ollama's {@code stop}. Only textual
     * stop sequences are taken — numeric token ids are never sent as stops.
     */
    private static void offerStops(Map<String, MetadataValue<Object>> out, Object stopStrings) {
        List<String> stops = new ArrayList<String>();
        if (stopStrings instanceof String) {
            String single = ((String) stopStrings).trim();
            if (single.length() > 0) {
                stops.add(single);
            }
        } else if (stopStrings instanceof List) {
            for (Object element : (List<?>) stopStrings) {
                if (element instanceof String && ((String) element).trim().length() > 0) {
                    stops.add(((String) element).trim());
                }
            }
        }
        if (!stops.isEmpty()) {
            out.put("stop", MetadataValue.<Object>high(stops, MetadataSource.GENERATION_CONFIG));
        }
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

    // ------------------------------------------------------------------ capability derivation

    /**
     * Derives extra Ollama capability tags strictly from structured sources: the HF {@code pipeline_tag}
     * and the actual chat template / tokenizer config. Never derived from the model or repository name,
     * README prose or marketing. VISION/AUDIO are intentionally not derived here — they are decided by the
     * real installed runtime (main GGUF and any attached projector), not by HF metadata.
     *
     * @param info the HF model-info map (for {@code pipeline_tag}), may be {@code null}
     * @param chatTemplate the combined chat-template text (tokenizer_config.chat_template + chat_template.jinja)
     * @param tokenizerConfigRaw the raw tokenizer_config.json text (holds FIM special tokens), may be {@code null}
     * @return the set of extra Ollama capability tags to union into the plan's capabilities
     */
    private static java.util.Set<String> deriveStructuredCapabilities(Map<String, Object> info,
                                                                      String chatTemplate,
                                                                      String tokenizerConfigRaw) {
        java.util.Set<String> tags = new java.util.LinkedHashSet<String>();

        // EMBEDDING: only from an unambiguous embedding pipeline_tag.
        String pipelineTag = info == null ? "" : string(info, "pipeline_tag").toLowerCase(Locale.ROOT).trim();
        if ("feature-extraction".equals(pipelineTag) || "sentence-similarity".equals(pipelineTag)) {
            tags.add("embedding");
        }

        // TOOLS / THINKING: only from a usable chat template that actually renders tool calls / reasoning.
        String template = chatTemplate == null ? "" : chatTemplate.toLowerCase(Locale.ROOT);
        if (template.contains("tool_call") || template.contains("tool_calls")
                || template.contains("tojson") && template.contains("tools")) {
            tags.add("tools");
        }
        if (template.contains("<think") || template.contains("</think")
                || template.contains("reasoning_content")) {
            tags.add("thinking");
        }

        // INSERT (fill-in-the-middle): only from a verified FIM template/token set, not from code models in general.
        String fimSource = template + "\n" + (tokenizerConfigRaw == null ? "" : tokenizerConfigRaw.toLowerCase(Locale.ROOT));
        if (fimSource.contains("fim_prefix") || fimSource.contains("fim_middle") || fimSource.contains("fim_suffix")
                || fimSource.contains("<|fim") || fimSource.contains("<fim_")) {
            tags.add("insert");
        }
        return tags;
    }

    /**
     * Extracts the chat-template text from a parsed tokenizer_config map. Supports both a single string and
     * a list of {@code {name, template}} maps (default / tool_use variants) — all variants are concatenated
     * because the text is used only to detect capabilities, never sent to Ollama.
     */
    private static String extractChatTemplate(Map<String, Object> tokenizerConfig) {
        if (tokenizerConfig == null) {
            return "";
        }
        Object raw = tokenizerConfig.get("chat_template");
        if (raw instanceof String) {
            return (String) raw;
        }
        if (raw instanceof List) {
            StringBuilder combined = new StringBuilder();
            for (Object element : (List<?>) raw) {
                if (element instanceof Map) {
                    Object template = ((Map<?, ?>) element).get("template");
                    if (template != null) {
                        combined.append(String.valueOf(template)).append('\n');
                    }
                } else if (element instanceof String) {
                    combined.append((String) element).append('\n');
                }
            }
            return combined.toString();
        }
        return "";
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
