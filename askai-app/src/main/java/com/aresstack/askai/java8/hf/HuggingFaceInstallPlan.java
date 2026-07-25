package com.aresstack.askai.java8.hf;

import io.github.ollama4j.json.OllamaJson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The immutable "installation contract" derived from a selected Hugging Face model: which capabilities
 * the search declared and, canonically mapped, which {@code /api/show} capability tags Ollama must
 * therefore report for the import to count as complete. Swing-free so it can be carried through the
 * async download and re-loaded later.
 *
 * <p>Persisted as a sidecar next to the downloaded GGUF ({@code <model>.askai-install.json}) so a later
 * install from "Downloaded files" still knows the repository and the declared capabilities. A GGUF
 * without a sidecar is treated as a plain manual import with no declared capabilities.</p>
 */
public final class HuggingFaceInstallPlan {

    private static final String SIDECAR_SUFFIX = ".askai-install.json";

    /** Bumped when the sidecar's serialized shape changes; readers stay backward compatible. */
    private static final int SCHEMA_VERSION = 2;

    private final String repositoryId;
    private final String revision;
    private final String targetModelName;
    private final List<String> declaredCapabilities;      // ModelCapability names, e.g. ["TEXT","AUDIO"]
    private final List<String> requiredOllamaCapabilities; // canonical tags, e.g. ["completion","audio"]
    private final String modelType;                        // config.json model_type, e.g. "qwen3" ("" if unknown)

    public HuggingFaceInstallPlan(String repositoryId, String revision, String targetModelName,
                                  List<String> declaredCapabilities, List<String> requiredOllamaCapabilities) {
        this(repositoryId, revision, targetModelName, declaredCapabilities, requiredOllamaCapabilities, "");
    }

    public HuggingFaceInstallPlan(String repositoryId, String revision, String targetModelName,
                                  List<String> declaredCapabilities, List<String> requiredOllamaCapabilities,
                                  String modelType) {
        this.repositoryId = repositoryId == null ? "" : repositoryId;
        this.revision = revision == null || revision.trim().isEmpty() ? "main" : revision.trim();
        this.targetModelName = targetModelName == null ? "" : targetModelName;
        this.declaredCapabilities = immutable(declaredCapabilities);
        this.requiredOllamaCapabilities = immutable(requiredOllamaCapabilities);
        this.modelType = modelType == null ? "" : modelType.trim();
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public String getRevision() {
        return revision;
    }

    public String getTargetModelName() {
        return targetModelName;
    }

    public List<String> getDeclaredCapabilities() {
        return declaredCapabilities;
    }

    /** @return the canonical {@code /api/show} tags that must be present after install (e.g. "audio"). */
    public List<String> getRequiredOllamaCapabilities() {
        return requiredOllamaCapabilities;
    }

    /** @return the {@code config.json} {@code model_type} captured at download, or "" when unknown. */
    public String getModelType() {
        return modelType;
    }

    // ------------------------------------------------------------------ sidecar

    private static File sidecarFor(File modelFile) {
        return new File(modelFile.getParentFile(), modelFile.getName() + SIDECAR_SUFFIX);
    }

    /**
     * Writes the plan next to {@code modelFile}. A write failure is surfaced, not swallowed: losing the
     * install contract silently would let a later re-install fall back to a plain manual import without
     * the declared capabilities.
     */
    public void writeSidecar(File modelFile) throws IOException {
        OutputStream out = null;
        try {
            out = new FileOutputStream(sidecarFor(modelFile));
            out.write(toJson().getBytes(StandardCharsets.UTF_8));
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    /** @return true when an install sidecar exists next to {@code modelFile}. */
    public static boolean hasSidecar(File modelFile) {
        return sidecarFor(modelFile).isFile();
    }

    /**
     * @return the plan persisted next to {@code modelFile}, or {@code null} when no sidecar exists.
     * @throws IOException when a sidecar exists but cannot be read/parsed — the caller must surface a
     *         clear error (or an explicit manual import), never silently treat it as an empty plan.
     */
    public static HuggingFaceInstallPlan readSidecar(File modelFile) throws IOException {
        File sidecar = sidecarFor(modelFile);
        if (!sidecar.isFile()) {
            return null; // no sidecar → a plain manual GGUF import
        }
        Object parsed;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(sidecar.toPath());
            parsed = OllamaJson.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IOException("Invalid install sidecar " + sidecar.getName() + ": " + ex.getMessage(), ex);
        }
        if (!(parsed instanceof Map)) {
            throw new IOException("Invalid install sidecar " + sidecar.getName() + ": not a JSON object.");
        }
        Map map = (Map) parsed;
        // modelType is absent in v1 sidecars — string(...) yields "" there, which is the correct default.
        return new HuggingFaceInstallPlan(string(map, "repositoryId"), string(map, "revision"),
                string(map, "targetModelName"), stringList(map.get("declaredCapabilities")),
                stringList(map.get("requiredOllamaCapabilities")), string(map, "modelType"));
    }

    private String toJson() {
        StringBuilder builder = new StringBuilder("{");
        builder.append("\"schemaVersion\":").append(SCHEMA_VERSION).append(",");
        builder.append("\"repositoryId\":\"").append(escape(repositoryId)).append("\",");
        builder.append("\"revision\":\"").append(escape(revision)).append("\",");
        builder.append("\"targetModelName\":\"").append(escape(targetModelName)).append("\",");
        builder.append("\"modelType\":\"").append(escape(modelType)).append("\",");
        builder.append("\"declaredCapabilities\":").append(jsonArray(declaredCapabilities)).append(",");
        builder.append("\"requiredOllamaCapabilities\":").append(jsonArray(requiredOllamaCapabilities));
        return builder.append('}').toString();
    }

    private static String jsonArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(escape(values.get(i))).append('"');
        }
        return builder.append(']').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String string(Map map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            for (Object element : (List<Object>) value) {
                if (element != null) {
                    result.add(String.valueOf(element));
                }
            }
        }
        return result;
    }
}
