package com.aresstack.askai.java8.batch.service;

import com.aresstack.askai.java8.client.AskAiOllamaClient;
import com.aresstack.askai.java8.client.OllamaModelInfo;
import com.aresstack.askai.java8.stt.AudioCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Resolves the models a batch run may target. Mirrors {@code OllamaAudioModelResolver}: it scans the
 * installed models via {@code /api/show} and keeps only those reporting the exact {@code audio}
 * capability. The scan runs off the EDT and the result is published as a
 * {@link BatchSelectionCatalogLoadedEvent}; the UI forwards it to the EDT itself.
 */
public final class BatchSelectionCatalogService {

    private final Supplier<String> baseUrlSupplier;
    private final ExecutorService executor;

    public BatchSelectionCatalogService(Supplier<String> baseUrlSupplier) {
        this.baseUrlSupplier = baseUrlSupplier;
        this.executor = Executors.newSingleThreadExecutor(new CatalogThreadFactory());
    }

    /** Load the audio-capable model catalog asynchronously and hand the result to {@code observer}. */
    public void loadAsync(final Consumer<BatchSelectionCatalogLoadedEvent> observer) {
        executor.execute(new Runnable() {
            public void run() {
                observer.accept(resolveCatalog());
            }
        });
    }

    private BatchSelectionCatalogLoadedEvent resolveCatalog() {
        try {
            AskAiOllamaClient client = new AskAiOllamaClient(baseUrlSupplier.get());
            List<String> audioModels = new ArrayList<String>();
            for (OllamaModelInfo model : client.getInstalledModels()) {
                String name = model.getDisplayName();
                try {
                    if (AudioCapability.isAudioCapable(client.getModelInfo(name).getCapabilities())) {
                        audioModels.add(name);
                    }
                } catch (Exception ignored) {
                    // Skip a model we cannot query; UNKNOWN capabilities never count as audio.
                }
            }
            return BatchSelectionCatalogLoadedEvent.loaded(audioModels);
        } catch (Exception ex) {
            return BatchSelectionCatalogLoadedEvent.failed(
                    ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private static final class CatalogThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-batch-catalog-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
