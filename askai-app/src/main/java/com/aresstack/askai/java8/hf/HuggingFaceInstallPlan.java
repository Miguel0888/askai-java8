package com.aresstack.askai.java8.hf;

import io.github.ollama4j.json.OllamaJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable "installation contract" derived from a selected Hugging Face model: which capabilities
 * the search declared and, canonically mapped, which {@code /api/show} capability tags Ollama must
 * therefore report for the import to count as complete, plus the config.json {@code model_type} so the
 * family can be derived. Swing-free so it can be carried through the async download and re-loaded later.
 *
 * <p>Persisted as a sidecar next to the downloaded GGUF ({@code <model>.askai-install.json}) so a later
 * install from "Downloaded files" still knows the repository and the declared capabilities. A GGUF
 * without a sidecar is treated as a plain manual import with no declared capabilities.</p>
 *
 * <p>The sidecar is versioned: an absent {@code schemaVersion} is read as the original v1 shape, the
 * current {@code schemaVersion} is validated strictly (required fields and field types), and any unknown
 * future version is rejected rather than silently mis-read. Writes are atomic (temp file + rename) so a
 * crash mid-write cannot leave a half-written, "present but invalid" sidecar.</p>
 */
public final class HuggingFaceInstallPlan {

    private static final String SIDECAR_SUFFIX = ".askai-install.json";

    /** The sidecar shape this build writes. Absent in the wild means the original (v1) shape. */
    private static final int SCHEMA_VERSION = 3;

    private final String repositoryId;
    private final String revision;                         // the requested revision (branch/tag), e.g. "main"
    private final String resolvedRevisionSha;              // the pinned commit SHA the file was taken from
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
        this(repositoryId, revision, "", targetModelName, declaredCapabilities, requiredOllamaCapabilities, modelType);
    }

    public HuggingFaceInstallPlan(String repositoryId, String revision, String resolvedRevisionSha,
                                  String targetModelName, List<String> declaredCapabilities,
                                  List<String> requiredOllamaCapabilities, String modelType) {
        this.repositoryId = repositoryId == null ? "" : repositoryId;
        this.revision = revision == null || revision.trim().isEmpty() ? "main" : revision.trim();
        this.resolvedRevisionSha = resolvedRevisionSha == null ? "" : resolvedRevisionSha.trim();
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

    /** @return the pinned commit SHA the file was taken from, or "" when not resolved. */
    public String getResolvedRevisionSha() {
        return resolvedRevisionSha;
    }

    /**
     * @return the revision to pin all downloads and metadata fetches to: the resolved commit SHA when
     *         known, else the requested revision. Ensures the file and its metadata come from one commit.
     */
    public String getPinnedRevision() {
        return resolvedRevisionSha.isEmpty() ? revision : resolvedRevisionSha;
    }

    /** @return a copy of this plan re-targeted to a different install name. */
    public HuggingFaceInstallPlan withTargetModelName(String newTargetModelName) {
        return new HuggingFaceInstallPlan(repositoryId, revision, resolvedRevisionSha, newTargetModelName,
                declaredCapabilities, requiredOllamaCapabilities, modelType);
    }

    /** @return a copy of this plan pinned to the given resolved commit SHA. */
    public HuggingFaceInstallPlan withResolvedRevisionSha(String sha) {
        return new HuggingFaceInstallPlan(repositoryId, revision, sha, targetModelName,
                declaredCapabilities, requiredOllamaCapabilities, modelType);
    }

    // ------------------------------------------------------------------ sidecar

    private static File sidecarFor(File modelFile) {
        return new File(modelFile.getParentFile(), modelFile.getName() + SIDECAR_SUFFIX);
    }

    /**
     * Writes the plan next to {@code modelFile} atomically: a temp file is written and then renamed over
     * the target, so a crash mid-write cannot leave a half-written sidecar. A failure is surfaced, not
     * swallowed — losing the contract silently would let a later re-install fall back to a plain manual
     * import without the declared capabilities.
     */
    public void writeSidecar(File modelFile) throws IOException {
        File target = sidecarFor(modelFile);
        File directory = target.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create directory for install sidecar: " + directory);
        }
        File temp = File.createTempFile(modelFile.getName() + ".", ".askai-install.tmp", directory);
        try {
            byte[] bytes = OllamaJson.toJson(toMap()).getBytes(StandardCharsets.UTF_8);
            Files.write(temp.toPath(), bytes);
            try {
                // A fresh plan must overwrite a stale sidecar, atomically where the platform allows it.
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    /** @return true when an install sidecar exists next to {@code modelFile}. */
    public static boolean hasSidecar(File modelFile) {
        return sidecarFor(modelFile).isFile();
    }

    /**
     * @return the plan persisted next to {@code modelFile}, or {@code null} when no sidecar exists.
     * @throws IOException when a sidecar exists but cannot be read, parsed or validated (bad JSON, empty
     *         object, missing/typed-wrong required fields, or an unsupported schema version). The caller
     *         must surface a clear error (or an explicit manual import), never treat it as an empty plan.
     */
    public static HuggingFaceInstallPlan readSidecar(File modelFile) throws IOException {
        File sidecar = sidecarFor(modelFile);
        if (!sidecar.isFile()) {
            return null; // no sidecar → a plain manual GGUF import
        }
        Object parsed;
        try {
            byte[] bytes = Files.readAllBytes(sidecar.toPath());
            parsed = OllamaJson.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw invalid(sidecar, ex.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw invalid(sidecar, "not a JSON object");
        }
        Map<?, ?> map = (Map<?, ?>) parsed;

        int version = readSchemaVersion(sidecar, map);
        if (version > SCHEMA_VERSION) {
            throw invalid(sidecar, "unsupported schemaVersion " + version + " (this build understands up to "
                    + SCHEMA_VERSION + ")");
        }
        // repositoryId is the mandatory marker of a real HF plan: this also rejects an empty "{}".
        String repositoryId = requireNonEmptyString(sidecar, map, "repositoryId");
        String revision = optionalString(sidecar, map, "revision");
        String resolvedRevisionSha = optionalString(sidecar, map, "resolvedRevisionSha"); // absent < v3 → ""
        String targetModelName = optionalString(sidecar, map, "targetModelName");
        String modelType = optionalString(sidecar, map, "modelType"); // absent in v1 → ""
        List<String> declared = optionalStringList(sidecar, map, "declaredCapabilities");
        List<String> required = optionalStringList(sidecar, map, "requiredOllamaCapabilities");

        return new HuggingFaceInstallPlan(repositoryId, revision, resolvedRevisionSha, targetModelName,
                declared, required, modelType);
    }

    private Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("schemaVersion", SCHEMA_VERSION);
        map.put("repositoryId", repositoryId);
        map.put("revision", revision);
        map.put("resolvedRevisionSha", resolvedRevisionSha);
        map.put("targetModelName", targetModelName);
        map.put("modelType", modelType);
        map.put("declaredCapabilities", new ArrayList<String>(declaredCapabilities));
        map.put("requiredOllamaCapabilities", new ArrayList<String>(requiredOllamaCapabilities));
        return map;
    }

    // ------------------------------------------------------------------ validation helpers

    private static int readSchemaVersion(File sidecar, Map<?, ?> map) throws IOException {
        Object value = map.get("schemaVersion");
        if (value == null) {
            return 1; // legacy sidecar without a version field
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ex) {
                throw invalid(sidecar, "schemaVersion is not a number");
            }
        }
        throw invalid(sidecar, "schemaVersion is not a number");
    }

    private static String requireNonEmptyString(File sidecar, Map<?, ?> map, String key) throws IOException {
        String value = optionalString(sidecar, map, key);
        if (value.trim().isEmpty()) {
            throw invalid(sidecar, "missing required field '" + key + "'");
        }
        return value;
    }

    private static String optionalString(File sidecar, Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String)) {
            throw invalid(sidecar, "field '" + key + "' must be a string");
        }
        return (String) value;
    }

    private static List<String> optionalStringList(File sidecar, Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List)) {
            throw invalid(sidecar, "field '" + key + "' must be an array");
        }
        List<String> result = new ArrayList<String>();
        for (Object element : (List<?>) value) {
            if (!(element instanceof String)) {
                throw invalid(sidecar, "field '" + key + "' must contain only strings");
            }
            result.add((String) element);
        }
        return result;
    }

    private static IOException invalid(File sidecar, String detail) {
        return new IOException("Invalid install sidecar " + sidecar.getName() + ": " + detail);
    }
}
