package com.aresstack.askai.localruntime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The per-model installation manifest {@code askai-local-model.json} — provenance and installation
 * state of ONE locally installed model, never a catalog. Written by the Java-8 host at the end of a
 * successful local installation; the sidecar only READS it to publish the virtual model.
 */
record LocalModelManifest(int schemaVersion, String virtualName, String huggingFaceRepository,
                          String resolvedRevision, String runtimeModelId, List<String> capabilities,
                          List<String> backendSupport, String state, Path modelDirectory) {

    static final String FILE_NAME = "askai-local-model.json";
    static final String STATE_RUNNABLE = "RUNNABLE";

    boolean isRunnable() {
        return STATE_RUNNABLE.equals(state);
    }

    /** @return the manifest, or null when the directory carries none or it is unreadable. */
    static LocalModelManifest read(Path modelDirectory) {
        Path file = modelDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Map<String, Object> json = LocalJson.parseObject(Files.readString(file));
            return new LocalModelManifest(
                    json.get("schemaVersion") instanceof Number n ? n.intValue() : 0,
                    LocalJson.str(json, "virtualName"),
                    LocalJson.str(json, "huggingFaceRepository"),
                    LocalJson.str(json, "resolvedRevision"),
                    LocalJson.str(json, "runtimeModelId"),
                    LocalJson.strings(json, "capabilities"),
                    LocalJson.strings(json, "backendSupport"),
                    LocalJson.str(json, "state"),
                    modelDirectory);
        } catch (IOException | RuntimeException unreadable) {
            System.err.println("[local-runtime] unreadable manifest in " + modelDirectory + ": "
                    + unreadable.getMessage());
            return null;
        }
    }
}
