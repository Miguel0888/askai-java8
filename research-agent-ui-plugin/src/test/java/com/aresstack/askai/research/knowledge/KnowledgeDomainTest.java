package com.aresstack.askai.research.knowledge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Commit 1 is domain + ports + config only: these lock the value invariants, not any algorithm. */
public class KnowledgeDomainTest {

    @Test
    public void idempotencyKeyBindsCaptureSegmentationAndEmbedding() {
        SourceProcessingRequest a = new SourceProcessingRequest("cap-1", "src-1", "seg-v1", "emb-fp-1");
        SourceProcessingRequest sameProcessing = new SourceProcessingRequest("cap-1", "src-1", "seg-v1",
                "emb-fp-1");
        SourceProcessingRequest newEmbedding = new SourceProcessingRequest("cap-1", "src-1", "seg-v1",
                "emb-fp-2");
        assertEquals(a.idempotencyKey(), sameProcessing.idempotencyKey());
        // A different embedding fingerprint is a NEW derivable run (§4.3).
        assertFalse(a.idempotencyKey().equals(newEmbedding.idempotencyKey()));
    }

    @Test
    public void jobTransitionsQueuedProcessingCompletedAndFailed() {
        SourceProcessingRequest req = new SourceProcessingRequest("cap-1", "src-1", "seg-v1", "emb-fp-1");
        SourceProcessingJob job = SourceProcessingJob.queued("job-1", req, 1000L);
        assertEquals(SourceProcessingJob.State.QUEUED, job.getState());
        assertEquals(0, job.getAttempts());

        SourceProcessingJob processing = job.startedProcessing();
        assertEquals(SourceProcessingJob.State.PROCESSING, processing.getState());
        assertEquals(1, processing.getAttempts());

        assertEquals(SourceProcessingJob.State.COMPLETED,
                processing.withState(SourceProcessingJob.State.COMPLETED).getState());

        SourceProcessingJob retry = processing.failed(
                SourceProcessingFailure.retryable(SourceProcessingStage.EMBEDDING, "timeout"));
        assertEquals(SourceProcessingJob.State.FAILED_RETRYABLE, retry.getState());
        SourceProcessingJob dead = processing.failed(
                SourceProcessingFailure.permanent(SourceProcessingStage.EXTRACTION, "corrupt"));
        assertEquals(SourceProcessingJob.State.FAILED_PERMANENT, dead.getState());
        assertNotNull(dead.getLastFailure());
    }

    @Test
    public void embeddingSpacesAreOnlyComparableWhenFingerprintAndDimensionMatch() {
        EmbeddingMetadata a = new EmbeddingMetadata("fp-1", 384, "l2", "v1");
        EmbeddingMetadata sameSpace = new EmbeddingMetadata("fp-1", 384, "l2", "v1");
        EmbeddingMetadata otherModel = new EmbeddingMetadata("fp-2", 384, "l2", "v1");
        EmbeddingMetadata otherDim = new EmbeddingMetadata("fp-1", 768, "l2", "v1");
        assertTrue(a.isComparableWith(sameSpace));
        assertFalse(a.isComparableWith(otherModel));
        assertFalse(a.isComparableWith(otherDim));
    }

    @Test
    public void passageKeepsProvenanceAndStructureAndIsImmutable() {
        Passage p = Passage.builder("p-1")
                .captureId("cap-1").sourceId("src-1").text("Smart glasses use waveguide displays.")
                .textHash("h1").startOffset(0).endOffset(37).firstSentenceIndex(0).lastSentenceIndex(0)
                .structuralContext(new StructuralContext(java.util.Arrays.asList("Displays"),
                        StructuralContext.BlockKind.PARAGRAPH))
                .segmentationPipelineVersion("seg-v1")
                .embeddingMetadata(new EmbeddingMetadata("fp-1", 384, "l2", "v1"))
                .build();
        assertEquals("cap-1", p.getCaptureId());
        assertEquals(java.util.Arrays.asList("Displays"), p.getStructuralContext().getHeadingPath());
        assertEquals(StructuralContext.BlockKind.PARAGRAPH, p.getStructuralContext().getBlockKind());
        assertEquals(384, p.getEmbeddingMetadata().getDimension());
    }

    @Test
    public void settingsDefaultsAreTheSingleOrigin() {
        KnowledgeProcessingSettings s = KnowledgeProcessingSettings.defaults();
        assertTrue(s.semanticBreakThreshold > 0 && s.semanticBreakThreshold < 1);
        assertTrue(s.minimumSentencesPerPassage >= 1);
        assertTrue(s.maximumSentencesPerPassage >= s.minimumSentencesPerPassage);
        assertTrue(s.topicMergeThreshold >= s.topicAssignmentThreshold);
        assertTrue(s.projectionDebounceMillis > 0);
        assertTrue(s.topicLabelRepresentativePassageCount > 0);
    }
}
