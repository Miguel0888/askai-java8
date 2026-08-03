package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.PassageSegmentation;
import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The consolidated worker orchestrates the CANONICAL PassageSegmentation over test-double ports: completion,
 * retry-at-tail, permanent failure, non-blocking FIFO, idempotency — no NLP/embedding/Lucene of its own.
 */
public class SourceProcessingWorkerTest {

    private static File tempDir() throws IOException {
        return java.nio.file.Files.createTempDirectory("askai-kproc-worker").toFile();
    }

    private static SourceProcessingRequest req(String captureId) {
        return new SourceProcessingRequest(captureId, "src-1", "seg-v1", "fake-fp");
    }

    private static SourceCapture paragraphCapture(String captureId) {
        SourceCapture.StructuralBlock block = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, "Root", "Alpha one. Beta two. Gamma three.");
        return new SourceCapture(captureId, "src-1", "https://x/y", 0L, "h", "T", "",
                Collections.singletonList(block));
    }

    // --- canonical-pipeline test doubles -----------------------------------------------------------------

    /** Splits on ". " — deterministic sentence detection stand-in for the OpenNLP port. */
    private static final class DotSegmenter implements SentenceSegmentationPort {
        public List<String> segment(String text) {
            List<String> out = new ArrayList<String>();
            if (text == null) {
                return out;
            }
            for (String part : text.split("\\. ")) {
                String s = part.trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    /** A fixed-space embedder: same fingerprint/dimension for all texts (segmentation stays deterministic). */
    private static final class FixedEmbedder implements EmbeddingPort {
        public List<EmbeddingVector> embed(List<String> texts) {
            List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(new EmbeddingVector("fake", "fake-fp", new float[]{1f, 0f, 0f}));
            }
            return out;
        }
    }

    private static PassageSegmentation segmentation() {
        return new PassageSegmentation(new DotSegmenter(), new FixedEmbedder(), "seg-v1");
    }

    private static final class Reader implements SourceCaptureReader {
        final java.util.Set<String> unknown = new java.util.HashSet<String>();
        final java.util.Map<String, Integer> failFirstNCalls = new java.util.HashMap<String, Integer>();

        public SourceCapture read(String captureId) {
            Integer left = failFirstNCalls.get(captureId);
            if (left != null && left > 0) {
                failFirstNCalls.put(captureId, left - 1);
                throw new RuntimeException("transient read error for " + captureId);
            }
            return unknown.contains(captureId) ? null : paragraphCapture(captureId);
        }
    }

    private static final class Store implements PassageStore {
        final List<Passage> passages = new ArrayList<Passage>();
        final List<Sentence> sentences = new ArrayList<Sentence>();
        final java.util.Map<String, EmbeddingPort.EmbeddingVector> vectors =
                new java.util.LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();

        public void store(SourceCapture capture, List<Sentence> s, List<Passage> p,
                          java.util.Map<String, EmbeddingPort.EmbeddingVector> v) {
            sentences.addAll(s);
            passages.addAll(p);
            vectors.putAll(v);
        }
    }

    private static final class RecordingListener implements SourceProcessingWorker.Listener {
        final List<String> completed = new ArrayList<String>();
        final List<SourceProcessingFailure> failures = new ArrayList<SourceProcessingFailure>();

        public void onStarted(SourceProcessingJob job) {
        }

        public void onCompleted(SourceProcessingJob job, int passageCount) {
            completed.add(job.getRequest().getCaptureId());
        }

        public void onFailed(SourceProcessingJob job, SourceProcessingFailure failure) {
            failures.add(failure);
        }
    }

    private static final class Fx {
        final Reader reader = new Reader();
        final Store store = new Store();
        final RecordingListener listener = new RecordingListener();
        final FileSourceProcessingQueue queue;
        final SourceProcessingWorker worker;

        Fx(File dir, int maxAttempts) {
            this.queue = new FileSourceProcessingQueue(dir);
            // The worker's pipeline embeds in the "fake-fp" world; jobs are enqueued with the same world.
            this.worker = new SourceProcessingWorker(queue, reader, segmentation(), store, maxAttempts,
                    "fake-fp", listener);
        }
    }

    @Test
    public void aHealthyCaptureIsSegmentedAndItsPassagesStored() throws IOException {
        Fx fx = new Fx(tempDir(), 3);
        fx.queue.enqueue(req("cap-ok"));
        assertTrue(fx.worker.processOne());
        assertFalse("at least one passage stored", fx.store.passages.isEmpty());
        assertFalse("sentences stored", fx.store.sentences.isEmpty());
        assertEquals(Arrays.asList("cap-ok"), fx.listener.completed);
        assertTrue(fx.queue.isAlreadyCompleted(req("cap-ok").idempotencyKey()));
        assertNull(fx.queue.takeNext());
    }

    @Test
    public void aRetryableFailureIsRetriedAtTheTailThenCompletes() throws IOException {
        Fx fx = new Fx(tempDir(), 3);
        fx.reader.failFirstNCalls.put("cap-flaky", 2);
        fx.queue.enqueue(req("cap-flaky"));
        assertTrue(fx.worker.processOne());
        assertTrue(fx.worker.processOne());
        assertTrue(fx.worker.processOne());
        assertEquals(2, fx.listener.failures.size());
        assertTrue(fx.listener.failures.get(0).isRetryable());
        assertEquals(Arrays.asList("cap-flaky"), fx.listener.completed);
        assertNull(fx.queue.takeNext());
    }

    @Test
    public void anUnknownCaptureFailsPermanentlyAndIsNotRetried() throws IOException {
        Fx fx = new Fx(tempDir(), 3);
        fx.reader.unknown.add("cap-gone");
        fx.queue.enqueue(req("cap-gone"));
        assertTrue(fx.worker.processOne());
        assertEquals(1, fx.listener.failures.size());
        assertFalse(fx.listener.failures.get(0).isRetryable());
        assertEquals(SourceProcessingStage.EXTRACTION, fx.listener.failures.get(0).getStage());
        assertTrue(fx.listener.completed.isEmpty());
        assertNull(fx.queue.takeNext());
    }

    @Test
    public void aFailingJobDoesNotBlockTheNextFifoJob() throws IOException {
        Fx fx = new Fx(tempDir(), 3);
        fx.reader.unknown.add("cap-a");
        fx.queue.enqueue(req("cap-a"));
        fx.queue.enqueue(req("cap-b"));
        assertEquals(2, fx.worker.drain());
        assertEquals(Arrays.asList("cap-b"), fx.listener.completed);
        assertEquals(1, fx.listener.failures.size());
    }

    @Test
    public void anAlreadyCompletedKeyIsShortCircuited() throws IOException {
        Fx fx = new Fx(tempDir(), 3);
        fx.queue.enqueue(req("cap-ok"));
        fx.worker.processOne();
        int stored = fx.store.passages.size();
        fx.queue.enqueue(req("cap-ok")); // idempotent → returns the completed job, nothing new queued
        assertNull(fx.queue.takeNext());
        assertEquals("no reprocessing", stored, fx.store.passages.size());
    }

    /**
     * §4.3 temporal consistency: a job queued under embedding world F1 must NEVER be run with the session's F2
     * pipeline and stored under the F1 key. The worker supersedes the stale job and re-enqueues the capture for
     * the active world — the F1 job is not processed, and a fresh F2 job for the same capture appears.
     */
    @Test
    public void aJobFromAnOtherEmbeddingWorldIsSupersededAndReEnqueuedForTheActiveWorld() throws IOException {
        File dir = tempDir();
        FileSourceProcessingQueue queue = new FileSourceProcessingQueue(dir);
        // A stale job in the "old-world-fp" world (e.g. the previous session used a different embedding model).
        queue.enqueue(new SourceProcessingRequest("cap-x", "src-1", "seg-v1", "old-world-fp"));
        RecordingListener listener = new RecordingListener();
        Store store = new Store();
        // This worker embeds in the "fake-fp" world (its FixedEmbedder), which differs from the stale job.
        SourceProcessingWorker worker = new SourceProcessingWorker(queue, new Reader(), segmentation(),
                store, 3, "fake-fp", listener);

        // First pass: the stale F1 job is taken, superseded (NOT processed) and a fresh F2 job re-enqueued.
        assertTrue(worker.processOne());
        assertTrue("the stale-world job is not processed", store.passages.isEmpty());
        assertTrue(listener.completed.isEmpty());
        assertFalse("the F1 key is NOT falsely completed",
                queue.isAlreadyCompleted(
                        new SourceProcessingRequest("cap-x", "src-1", "seg-v1", "old-world-fp")
                                .idempotencyKey()));

        // Second pass: the re-enqueued active-world job runs to completion under the F2 key.
        assertTrue(worker.processOne());
        assertFalse("the capture is derived for the active world", store.passages.isEmpty());
        assertEquals(Arrays.asList("cap-x"), listener.completed);
        assertTrue(queue.isAlreadyCompleted(
                new SourceProcessingRequest("cap-x", "src-1", "seg-v1", "fake-fp").idempotencyKey()));
        assertNull(queue.takeNext());
    }

    /** A returned-to earlier world re-activates the retired job rather than leaving the capture stranded. */
    @Test
    public void enqueuingASupersededWorldAgainReactivatesItsJob() throws IOException {
        File dir = tempDir();
        FileSourceProcessingQueue queue = new FileSourceProcessingQueue(dir);
        SourceProcessingJob original =
                queue.enqueue(new SourceProcessingRequest("cap-y", "src-1", "seg-v1", "world-1"));
        queue.markSuperseded(queue.takeNext());
        assertNull("a superseded job is not takeable", queue.takeNext());

        SourceProcessingJob reactivated =
                queue.enqueue(new SourceProcessingRequest("cap-y", "src-1", "seg-v1", "world-1"));
        assertEquals(original.getJobId(), reactivated.getJobId());
        assertEquals(SourceProcessingJob.State.QUEUED, reactivated.getState());
        assertEquals("cap-y", queue.takeNext().getRequest().getCaptureId());
    }
}
