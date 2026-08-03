package com.aresstack.askai.research.knowledge.processing;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * The scheduler stamps every job with the SESSION's injected embedding-world fingerprint (never a settings
 * placeholder), so the idempotency key names the vector world the capture was derived for (§4.3). Same world →
 * dedup; a new world → a new, distinct job.
 */
public class QueueBackedKnowledgeProcessingSchedulerTest {

    private static File tempDir() throws IOException {
        return java.nio.file.Files.createTempDirectory("askai-kproc-sched").toFile();
    }

    @Test
    public void stampsTheInjectedDescriptorFingerprintOntoTheRequest() throws IOException {
        FileSourceProcessingQueue queue = new FileSourceProcessingQueue(tempDir());
        new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "world-F1").enqueue("cap-1", "src-1");

        SourceProcessingJob job = queue.takeNext();
        assertNotNull(job);
        assertEquals("world-F1", job.getRequest().getEmbeddingModelFingerprint());
        assertEquals("seg-v1", job.getRequest().getSegmentationPipelineVersion());
        assertEquals("cap-1|seg-v1|world-F1", job.getRequest().idempotencyKey());
    }

    @Test
    public void sameCaptureSameWorldDeduplicates() throws IOException {
        FileSourceProcessingQueue queue = new FileSourceProcessingQueue(tempDir());
        QueueBackedKnowledgeProcessingScheduler scheduler =
                new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "world-F1");
        scheduler.enqueue("cap-1", "src-1");
        scheduler.enqueue("cap-1", "src-1");

        assertNotNull(queue.takeNext());
        assertNull("a duplicate acceptance in the same world produces no second job", queue.takeNext());
    }

    @Test
    public void sameCaptureNewWorldProducesADistinctJob() throws IOException {
        FileSourceProcessingQueue queue = new FileSourceProcessingQueue(tempDir());
        new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "world-F1").enqueue("cap-1", "src-1");
        new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "world-F2").enqueue("cap-1", "src-1");

        SourceProcessingJob first = queue.takeNext();
        SourceProcessingJob second = queue.takeNext();
        assertNotNull(first);
        assertNotNull("a new embedding world is a new derivation, not a dedup", second);
        assertEquals("world-F1", first.getRequest().getEmbeddingModelFingerprint());
        assertEquals("world-F2", second.getRequest().getEmbeddingModelFingerprint());
    }

    @Test
    public void rejectsAMissingEmbeddingWorldFingerprint() throws IOException {
        FileSourceProcessingQueue queue = new FileSourceProcessingQueue(tempDir());
        try {
            new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", "  ");
            fail("an empty embedding world is a wiring error — the capability is unavailable");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
