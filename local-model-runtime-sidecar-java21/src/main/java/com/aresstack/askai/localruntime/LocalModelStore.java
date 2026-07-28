package com.aresstack.askai.localruntime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stateless view over the local model root ({@code …\models\local}): every subdirectory with a
 * RUNNABLE {@code askai-local-model.json} is one virtual model. Scanned per request so an
 * installation finished by the host appears on the next {@code /api/tags} without restarts.
 */
final class LocalModelStore {

    private final Path modelRoot;

    LocalModelStore(Path modelRoot) {
        this.modelRoot = modelRoot;
    }

    List<LocalModelManifest> runnableModels() {
        List<LocalModelManifest> models = new ArrayList<>();
        if (!Files.isDirectory(modelRoot)) {
            return models;
        }
        try (Stream<Path> children = Files.list(modelRoot)) {
            children.filter(Files::isDirectory).sorted().forEach(dir -> {
                LocalModelManifest manifest = LocalModelManifest.read(dir);
                if (manifest != null && manifest.isRunnable()) {
                    models.add(manifest);
                }
            });
        } catch (IOException ex) {
            System.err.println("[local-runtime] cannot scan model root " + modelRoot + ": "
                    + ex.getMessage());
        }
        return models;
    }

    /** @return the manifest owning this virtual name, or null. */
    LocalModelManifest find(String virtualName) {
        for (LocalModelManifest manifest : runnableModels()) {
            if (manifest.virtualName().equals(virtualName)) {
                return manifest;
            }
        }
        return null;
    }

    long directorySizeBytes(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(f -> {
                try {
                    return Files.size(f);
                } catch (IOException ex) {
                    return 0;
                }
            }).sum();
        } catch (IOException ex) {
            return 0;
        }
    }

    /** Recursively delete the model directory (called AFTER the engine unloaded the model). */
    void delete(LocalModelManifest manifest) throws IOException {
        try (Stream<Path> files = Files.walk(manifest.modelDirectory())) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
