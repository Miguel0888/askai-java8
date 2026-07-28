package com.aresstack.askai.localruntime;

import com.aresstack.windirectml.runtime.api.Backend;
import com.aresstack.windirectml.runtime.api.MlRuntime;
import com.aresstack.windirectml.runtime.api.RerankResult;
import com.aresstack.windirectml.runtime.api.RerankerConfig;
import com.aresstack.windirectml.runtime.api.RerankerModelHandle;
import com.aresstack.windirectml.runtime.api.RerankerModelId;

import java.nio.file.Path;
import java.util.List;

/**
 * Owns the win-directml-java runtime for this sidecar: models load LAZILY on the first rerank (or
 * the manual test) and stay WARM afterwards; {@code maximumLoadedModels=1} — switching to another
 * local model closes the previous handle cleanly. Backend defaults to CPU.
 */
final class LocalRerankerEngine implements AutoCloseable {

    /** One rerank invocation result: scores plus the durations the Ollama API reports. */
    record Reranked(List<RerankResult> results, long loadDurationNanos, long totalDurationNanos) {
    }

    private final Backend backend;
    private MlRuntime runtime;
    private RerankerModelHandle loaded;
    private String loadedVirtualName;

    LocalRerankerEngine(Backend backend) {
        this.backend = backend;
    }

    synchronized Reranked rerank(LocalModelManifest manifest, String query, List<String> documents,
                                 int topN) throws Exception {
        long start = System.nanoTime();
        long loadNanos = ensureLoaded(manifest);
        List<RerankResult> results = loaded.rerank(query, documents, topN);
        return new Reranked(results, loadNanos, System.nanoTime() - start);
    }

    /** Load + immediately close (installation smoke test) WITHOUT touching the warm model. */
    synchronized void smokeLoad(Path modelDirectory, RerankerModelId modelId) throws Exception {
        MlRuntime smokeRuntime = MlRuntime.builder().backend(backend).build();
        try (RerankerModelHandle handle = smokeRuntime.loadReranker(RerankerConfig.builder()
                .model(modelId)
                .modelDir(modelDirectory)
                .build())) {
            if (!handle.isReady()) {
                throw new IllegalStateException("model loaded but reports not ready");
            }
        }
    }

    /** The virtual name of the currently WARM model, or null (drives /api/ps). */
    synchronized String loadedVirtualName() {
        return loadedVirtualName;
    }

    synchronized void unload(String virtualName) {
        if (virtualName.equals(loadedVirtualName)) {
            closeLoaded();
        }
    }

    private long ensureLoaded(LocalModelManifest manifest) throws Exception {
        if (manifest.virtualName().equals(loadedVirtualName)) {
            return 0;
        }
        long start = System.nanoTime();
        closeLoaded(); // maximumLoadedModels=1: the previous model is closed cleanly first
        if (runtime == null) {
            runtime = MlRuntime.builder().backend(backend).build();
        }
        RerankerModelId modelId = RerankerModelId.valueOf(manifest.runtimeModelId());
        loaded = runtime.loadReranker(RerankerConfig.builder()
                .model(modelId)
                .modelDir(manifest.modelDirectory())
                .build());
        loadedVirtualName = manifest.virtualName();
        System.err.println("[local-runtime] loaded " + loadedVirtualName + " on backend " + backend);
        return System.nanoTime() - start;
    }

    private void closeLoaded() {
        if (loaded != null) {
            try {
                loaded.close();
            } catch (RuntimeException ex) {
                System.err.println("[local-runtime] close failed: " + ex.getMessage());
            }
            System.err.println("[local-runtime] unloaded " + loadedVirtualName);
            loaded = null;
            loadedVirtualName = null;
        }
    }

    @Override
    public synchronized void close() {
        closeLoaded();
    }
}
