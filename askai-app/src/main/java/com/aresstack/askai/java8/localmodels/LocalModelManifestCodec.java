package com.aresstack.askai.java8.localmodels;

import com.aresstack.windirectml.catalog.InstalledModelManifest;

import io.github.ollama4j.json.OllamaJson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-side JSON I/O for the neutral {@link InstalledModelManifest}. The value object and its trust rules
 * live in the shared catalog module (so the sidecar applies the SAME rules); only the JSON encoding lives
 * here, on the host's existing JSON library.
 */
public final class LocalModelManifestCodec {

    private LocalModelManifestCodec() {
    }

    public static String toJson(InstalledModelManifest manifest) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("schemaVersion", manifest.getSchemaVersion());
        m.put("virtualName", manifest.getVirtualName());
        m.put("huggingFaceRepository", manifest.getHuggingFaceRepository());
        m.put("resolvedRevision", manifest.getResolvedRevision());
        m.put("runtimeModelId", manifest.getRuntimeModelId());
        m.put("runtimeFamily", manifest.getRuntimeFamily());
        m.put("runtimePackage", manifest.getRuntimePackage());
        m.put("capabilities", manifest.getCapabilities());
        m.put("supportedBackends", manifest.getSupportedBackends());
        m.put("sourceFormat", manifest.getSourceFormat());
        m.put("state", manifest.getState());
        m.put("installedAt", manifest.getInstalledAt());
        return OllamaJson.toJson(m);
    }

    /**
     * Parse a manifest JSON into the neutral value object. Returns {@code null} when the JSON is
     * structurally unreadable. A manifest with no {@code schemaVersion} is treated as the historical
     * schema 1 (the only pre-versioned form ever written). Callers still run
     * {@link InstalledModelManifest#validate(int)} — parsing never implies trust.
     */
    @SuppressWarnings("unchecked")
    public static InstalledModelManifest parse(String json) {
        Object parsed;
        try {
            parsed = OllamaJson.parse(json);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (!(parsed instanceof Map)) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) parsed;
        int schema = intValue(m.get("schemaVersion"),
                InstalledModelManifest.LEGACY_RERANKER_SCHEMA_VERSION);
        // v1 used "backendSupport"; v2 uses "supportedBackends".
        List<String> backends = m.containsKey("supportedBackends")
                ? stringList(m.get("supportedBackends")) : stringList(m.get("backendSupport"));
        return new InstalledModelManifest(schema, str(m.get("virtualName")),
                str(m.get("huggingFaceRepository")), str(m.get("resolvedRevision")),
                str(m.get("runtimeModelId")), str(m.get("runtimeFamily")), str(m.get("runtimePackage")),
                stringList(m.get("capabilities")), backends, str(m.get("sourceFormat")),
                str(m.get("state")), longValue(m.get("installedAt")));
    }

    // ------------------------------------------------------------------ helpers

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
