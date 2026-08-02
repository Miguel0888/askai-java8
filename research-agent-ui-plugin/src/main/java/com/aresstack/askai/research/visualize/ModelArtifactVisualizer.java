package com.aresstack.askai.research.visualize;

import com.aresstack.askai.agent.model.inference.AgentInferencePort;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The model-backed {@link ArtifactVisualizationService}: renders an artifact through the host's neutral
 * {@link AgentInferencePort} (no HTTP, no model names, no ACP). The streaming port is bridged to the
 * synchronous service contract with a latch — safe because the visualizer only ever runs on a background
 * worker (the lazy scheduler), never on the EDT. Any failure/timeout degrades to
 * {@link VisualizationResult#none} — a visualization is never critical.
 */
public final class ModelArtifactVisualizer implements ArtifactVisualizationService {

    // Generous on purpose: the visualizer is LOW-priority background work on a local model that also
    // serves the main agent — a cold gemma answer took 35s on an otherwise idle machine, far longer
    // under turn contention. Nothing blocks on this latch except the visualizer's own worker.
    private static final long TIMEOUT_MILLIS = 180000;

    private final AgentInferencePort port;

    public ModelArtifactVisualizer(AgentInferencePort port) {
        this.port = port;
    }

    public VisualizationResult visualize(ArtifactSnapshot snapshot) {
        if (port == null || snapshot == null || snapshot.getContent().trim().isEmpty()) {
            return VisualizationResult.failed("no inference port or empty artifact");
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> text = new AtomicReference<String>();
        final AtomicReference<String> failure = new AtomicReference<String>();
        AgentInferencePort.Cancellable cancellable = port.generate(
                new AgentInferencePort.InferenceRequest(VisualizationPrompt.system(),
                        VisualizationPrompt.user(snapshot)),
                new AgentInferencePort.Listener() {
                    public void onCompleted(String fullText) {
                        text.set(fullText);
                        done.countDown();
                    }

                    public void onFailed(String reason) {
                        failure.set(reason);
                        done.countDown();
                    }
                });
        try {
            if (!done.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                cancellable.cancel();
                return VisualizationResult.failed("visualizer timed out");
            }
        } catch (InterruptedException interrupted) {
            cancellable.cancel();
            Thread.currentThread().interrupt();
            return VisualizationResult.failed("visualizer interrupted");
        }
        if (text.get() == null) {
            return VisualizationResult.failed(failure.get() == null ? "visualizer failed" : failure.get());
        }
        return VisualizationResultParser.parse(text.get());
    }
}
