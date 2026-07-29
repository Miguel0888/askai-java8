package com.aresstack.askai.java8.localmodels;

import com.aresstack.windirectml.catalog.CatalogBackend;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ModelCapability;

import io.github.ollama4j.json.OllamaJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The provenance manifest ({@code askai-local-model.json}) written LAST after a verified install, and read
 * by the host catalog and the sidecar to publish only RUNNABLE models. Schema v2 carries the neutral
 * catalog facts (family, package, capabilities, backends, source format, install time); the v1 reranker
 * manifest (schemaVersion 1, {@code capabilities:[rerank]}, {@code backendSupport:[cpu,directml]}) is still
 * read so existing installations keep working.
 */
public final class LocalModelManifest {

    public static final int SCHEMA_VERSION = 2;

    private final int schemaVersion;
    private final String virtualName;
    private final String huggingFaceRepository;
    private final String resolvedRevision;
    private final String runtimeModelId;
    private final String runtimeFamily;
    private final String runtimePackage;
    private final List<String> capabilities;
    private final List<String> supportedBackends;
    private final String sourceFormat;
    private final String state;
    private final long installedAt;

    private LocalModelManifest(int schemaVersion, String virtualName, String huggingFaceRepository,
                               String resolvedRevision, String runtimeModelId, String runtimeFamily,
                               String runtimePackage, List<String> capabilities,
                               List<String> supportedBackends, String sourceFormat, String state,
                               long installedAt) {
        this.schemaVersion = schemaVersion;
        this.virtualName = nz(virtualName);
        this.huggingFaceRepository = nz(huggingFaceRepository);
        this.resolvedRevision = nz(resolvedRevision);
        this.runtimeModelId = nz(runtimeModelId);
        this.runtimeFamily = nz(runtimeFamily);
        this.runtimePackage = nz(runtimePackage);
        this.capabilities = unmodifiable(capabilities);
        this.supportedBackends = unmodifiable(supportedBackends);
        this.sourceFormat = nz(sourceFormat);
        this.state = state == null || state.trim().isEmpty() ? "RUNNABLE" : state.trim();
        this.installedAt = installedAt;
    }

    /** Build a v2 manifest for a verified install from the neutral catalog descriptor. */
    public static LocalModelManifest forInstall(LocalRuntimeModelDescriptor descriptor, String virtualName,
                                                String resolvedRevision, long installedAtEpochMillis) {
        List<String> caps = LocalRuntimeCapability.tags(descriptor.capabilities());
        List<String> backends = new ArrayList<String>();
        for (CatalogBackend b : descriptor.supportedBackends()) {
            backends.add(b.name().toLowerCase(Locale.ROOT));
        }
        return new LocalModelManifest(SCHEMA_VERSION, virtualName,
                descriptor.huggingFaceRepositoryId(), resolvedRevision, descriptor.runtimeModelId(),
                descriptor.runtimeFamily().token(), descriptor.runtimePackageFileName(), caps, backends,
                descriptor.sourceFormat().token(), "RUNNABLE", installedAtEpochMillis);
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("schemaVersion", schemaVersion);
        m.put("virtualName", virtualName);
        m.put("huggingFaceRepository", huggingFaceRepository);
        m.put("resolvedRevision", resolvedRevision);
        m.put("runtimeModelId", runtimeModelId);
        m.put("runtimeFamily", runtimeFamily);
        m.put("runtimePackage", runtimePackage);
        m.put("capabilities", capabilities);
        m.put("supportedBackends", supportedBackends);
        m.put("sourceFormat", sourceFormat);
        m.put("state", state);
        m.put("installedAt", installedAt);
        return OllamaJson.toJson(m);
    }

    /** Parse a v1 or v2 manifest. Returns {@code null} when the JSON is unreadable or lacks a virtualName. */
    @SuppressWarnings("unchecked")
    public static LocalModelManifest parse(String json) {
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
        String virtualName = str(m.get("virtualName"));
        if (virtualName.isEmpty()) {
            return null;
        }
        int schema = intValue(m.get("schemaVersion"), 1);
        List<String> capabilities = stringList(m.get("capabilities"));
        // v1 backend field was "backendSupport"; v2 uses "supportedBackends".
        List<String> backends = m.containsKey("supportedBackends")
                ? stringList(m.get("supportedBackends")) : stringList(m.get("backendSupport"));
        return new LocalModelManifest(schema, virtualName, str(m.get("huggingFaceRepository")),
                str(m.get("resolvedRevision")), str(m.get("runtimeModelId")), str(m.get("runtimeFamily")),
                str(m.get("runtimePackage")), capabilities, backends, str(m.get("sourceFormat")),
                str(m.get("state")), longValue(m.get("installedAt")));
    }

    public int getSchemaVersion() { return schemaVersion; }
    public String getVirtualName() { return virtualName; }
    public String getHuggingFaceRepository() { return huggingFaceRepository; }
    public String getResolvedRevision() { return resolvedRevision; }
    public String getRuntimeModelId() { return runtimeModelId; }
    public String getRuntimeFamily() { return runtimeFamily; }
    public String getRuntimePackage() { return runtimePackage; }
    public List<String> getCapabilities() { return capabilities; }
    public List<String> getSupportedBackends() { return supportedBackends; }
    public String getSourceFormat() { return sourceFormat; }
    public String getState() { return state; }
    public long getInstalledAt() { return installedAt; }

    public boolean isRunnable() {
        return "RUNNABLE".equalsIgnoreCase(state);
    }

    public boolean hasCapability(ModelCapability capability) {
        return capabilities.contains(LocalRuntimeCapability.fromCatalog(capability).getOllamaTag());
    }

    // ------------------------------------------------------------------ helpers

    private static String nz(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> unmodifiable(List<String> in) {
        return in == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(in));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<String>();
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
