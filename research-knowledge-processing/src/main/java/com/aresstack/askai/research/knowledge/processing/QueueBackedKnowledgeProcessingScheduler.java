package com.aresstack.askai.research.knowledge.processing;

/**
 * The productive {@link KnowledgeProcessingScheduler}: turns an accepted capture into a {@link
 * SourceProcessingRequest} (carrying the immutable capture id + the segmentation pipeline version + the
 * session's EMBEDDING-WORLD fingerprint for the idempotency key) and enqueues it on the persistent FIFO.
 * Enqueue is idempotent per key, so a duplicate acceptance never produces a duplicate job.
 *
 * <p>The embedding-world fingerprint is INJECTED at session build from the host's
 * {@code EmbeddingEndpointDescriptor.embeddingFingerprint()} — the scheduler never looks up a global model
 * selection. It is therefore stable from enqueue through processing: a job always names the vector world it was
 * created for, so a later global model switch can never store new-world vectors under an old-world key (§4.3).
 * A missing fingerprint is a wiring error (the capability is unavailable) and is rejected here rather than
 * silently tagging jobs with an empty world.</p>
 */
public final class QueueBackedKnowledgeProcessingScheduler implements KnowledgeProcessingScheduler {

    private final SourceProcessingQueue queue;
    private final String segmentationPipelineVersion;
    private final String embeddingWorldFingerprint;

    public QueueBackedKnowledgeProcessingScheduler(SourceProcessingQueue queue,
                                                   String segmentationPipelineVersion,
                                                   String embeddingWorldFingerprint) {
        if (embeddingWorldFingerprint == null || embeddingWorldFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("embeddingWorldFingerprint must be a resolved, non-empty "
                    + "session descriptor fingerprint — knowledge processing has no usable embedding world");
        }
        this.queue = queue;
        this.segmentationPipelineVersion = segmentationPipelineVersion == null
                ? "" : segmentationPipelineVersion;
        this.embeddingWorldFingerprint = embeddingWorldFingerprint.trim();
    }

    /** Convenience: take the segmentation version from settings, the world fingerprint from the descriptor. */
    public QueueBackedKnowledgeProcessingScheduler(SourceProcessingQueue queue,
                                                   KnowledgeProcessingSettings settings,
                                                   String embeddingWorldFingerprint) {
        this(queue, settings == null ? "" : settings.segmentationPipelineVersion, embeddingWorldFingerprint);
    }

    @Override
    public void enqueue(String captureId, String sourceId) {
        enqueue(captureId, sourceId, "");
    }

    @Override
    public void enqueue(String captureId, String sourceId, String languageCode) {
        // The request normalizes the language ("de" else "en"); an empty snapshot becomes the default "en".
        queue.enqueue(new SourceProcessingRequest(captureId, sourceId,
                segmentationPipelineVersion, embeddingWorldFingerprint, languageCode));
    }
}
