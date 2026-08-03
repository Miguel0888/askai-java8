package com.aresstack.askai.research.knowledge.processing;

/**
 * The productive {@link KnowledgeProcessingScheduler}: turns an accepted capture into a {@link
 * SourceProcessingRequest} (carrying the immutable capture id + the configured segmentation/embedding
 * provenance for the idempotency key) and enqueues it on the persistent FIFO. Enqueue is idempotent per key,
 * so a duplicate acceptance never produces a duplicate job.
 */
public final class QueueBackedKnowledgeProcessingScheduler implements KnowledgeProcessingScheduler {

    private final SourceProcessingQueue queue;
    private final KnowledgeProcessingSettings settings;

    public QueueBackedKnowledgeProcessingScheduler(SourceProcessingQueue queue,
                                                   KnowledgeProcessingSettings settings) {
        this.queue = queue;
        this.settings = settings;
    }

    @Override
    public void enqueue(String captureId, String sourceId) {
        queue.enqueue(new SourceProcessingRequest(captureId, sourceId,
                settings.segmentationPipelineVersion, settings.embeddingModelFingerprint));
    }
}
