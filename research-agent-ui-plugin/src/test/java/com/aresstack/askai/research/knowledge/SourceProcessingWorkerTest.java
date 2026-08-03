package com.aresstack.askai.research.knowledge;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The worker orchestrates the ports behind test doubles (no real NLP/embedding/Lucene): success, retry,
 *  permanent failure, non-blocking FIFO and idempotency (§31 / §5). */
public class SourceProcessingWorkerTest {

    private static final EmbeddingMetadata META = new EmbeddingMetadata("emb-fp-1", 3, "l2", "v1");

    private static File tempDir() throws IOException {
        return java.nio.file.Files.createTempDirectory("askai-proc-worker").toFile();
    }

    private static SourceProcessingRequest req(String captureId) {
        return new SourceProcessingRequest(captureId, "src-1", "seg-v1", "emb-fp-1");
    }

    // --- ports as configurable fakes ----------------------------------------------------------------------

    private static final class Reader implements SourceContentReader {
        final java.util.Set<String> unknown = new java.util.HashSet<String>();
        final java.util.Map<String, Integer> failFirstNCalls = new java.util.HashMap<String, Integer>();

        public ExtractedContent read(String captureId) {
            Integer left = failFirstNCalls.get(captureId);
            if (left != null && left > 0) {
                failFirstNCalls.put(captureId, left - 1);
                throw new RuntimeException("transient read error for " + captureId);
            }
            if (unknown.contains(captureId)) {
                return null; // unknown capture → permanent failure
            }
            return new ExtractedContent(captureId, "src-1", "Body of " + captureId + ".", "hash-" + captureId);
        }
    }

    private static final class Detection implements SentenceDetectionService {
        public List<DetectedSentence> detectSentences(ExtractedContent content) {
            return Collections.singletonList(
                    new DetectedSentence(0, content.getText(), 0, content.getText().length()));
        }
    }

    private static final class Embedding implements SentenceEmbeddingService {
        public List<EmbeddedSentence> embed(List<DetectedSentence> sentences) {
            List<EmbeddedSentence> out = new ArrayList<EmbeddedSentence>();
            for (DetectedSentence s : sentences) {
                out.add(new EmbeddedSentence(s, new PassageVector(new float[]{1, 0, 0}, META)));
            }
            return out;
        }

        public EmbeddingMetadata metadata() {
            return META;
        }
    }

    private static final class Segmenter implements SemanticPassageSegmenter {
        public List<Passage> segment(ExtractedContent content, List<EmbeddedSentence> sentences) {
            return Collections.singletonList(Passage.builder("p-" + content.getCaptureId())
                    .captureId(content.getCaptureId()).sourceId(content.getSourceId())
                    .text(content.getText()).textHash(content.getContentHash())
                    .segmentationPipelineVersion("seg-v1").embeddingMetadata(META).build());
        }
    }

    private static final class PassageEmbedding implements PassageEmbeddingService {
        public PassageVector embedPassage(String passageText) {
            return new PassageVector(new float[]{1, 0, 0}, META);
        }

        public EmbeddingMetadata metadata() {
            return META;
        }
    }

    private static final class Repo implements PassageRepository {
        final List<Passage> saved = new ArrayList<Passage>();

        public void save(Passage passage, PassageVector vector) {
            saved.add(passage);
        }

        public List<Passage> findByCaptureId(String captureId) {
            return Collections.emptyList();
        }

        public List<Passage> findBySourceId(String sourceId) {
            return Collections.emptyList();
        }

        public PassageVector loadVector(String passageId) {
            return null;
        }

        public List<Passage> findAll() {
            return saved;
        }
    }

    private static final class Index implements SemanticKnowledgeIndex {
        final List<String> indexed = new ArrayList<String>();

        public void indexPassages(Collection<PassageDocument> passages) {
            for (PassageDocument d : passages) {
                indexed.add(d.getPassageId());
            }
        }

        public List<PassageHit> keywordSearch(KnowledgeQuery query) {
            return Collections.emptyList();
        }

        public List<PassageHit> semanticSearch(SemanticQuery query) {
            return Collections.emptyList();
        }

        public List<PassageHit> findNeighbours(PassageVector vector, int maximumResults) {
            return Collections.emptyList();
        }

        public void rebuild() {
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
        final Repo repo = new Repo();
        final Index index = new Index();
        final RecordingListener listener = new RecordingListener();
        final FileSourceProcessingQueue queue;
        final SourceProcessingWorker worker;

        Fx(File dir, int maxAttempts) {
            this.queue = new FileSourceProcessingQueue(dir);
            this.worker = new SourceProcessingWorker(queue, reader, new Detection(), new Embedding(),
                    new Segmenter(), new PassageEmbedding(), repo, index, maxAttempts, listener);
        }
    }

    @Test
    public void aHealthyCaptureIsProcessedToCompletion() throws IOException {
        Fx fx = new Fx(tempDir(), 3);
        fx.queue.enqueue(req("cap-ok"));
        assertTrue(fx.worker.processOne());
        assertEquals(1, fx.repo.saved.size());
        assertEquals(1, fx.index.indexed.size());
        assertEquals(Collections.singletonList("cap-ok"), fx.listener.completed);
        assertTrue(fx.queue.isAlreadyCompleted(req("cap-ok").idempotencyKey()));
        assertNull("no work left", fx.queue.takeNext());
    }

    @Test
    public void aRetryableFailureIsRetriedAtTheTailThenCompletes() throws IOException {
        Fx fx = new Fx(tempDir(), 3);
        fx.reader.failFirstNCalls.put("cap-flaky", 2); // fails twice, then succeeds
        fx.queue.enqueue(req("cap-flaky"));
        assertTrue(fx.worker.processOne());  // attempt 1 → retryable
        assertTrue(fx.worker.processOne());  // attempt 2 → retryable
        assertTrue(fx.worker.processOne());  // attempt 3 → success
        assertEquals(2, fx.listener.failures.size());
        assertTrue(fx.listener.failures.get(0).isRetryable());
        assertEquals(Collections.singletonList("cap-flaky"), fx.listener.completed);
        assertNull(fx.queue.takeNext());
    }

    @Test
    public void anUnknownCaptureFailsPermanentlyAndIsNotRetried() throws IOException {
        Fx fx = new Fx(tempDir(), 3);
        fx.reader.unknown.add("cap-gone");
        fx.queue.enqueue(req("cap-gone"));
        assertTrue(fx.worker.processOne());
        assertEquals(1, fx.listener.failures.size());
        assertFalse("unknown capture is permanent", fx.listener.failures.get(0).isRetryable());
        assertEquals(SourceProcessingStage.EXTRACTION, fx.listener.failures.get(0).getStage());
        assertTrue(fx.listener.completed.isEmpty());
        assertNull("a permanent failure is not requeued", fx.queue.takeNext());
    }

    @Test
    public void aFailingJobDoesNotBlockTheNextFifoJob() throws IOException {
        Fx fx = new Fx(tempDir(), 3);
        fx.reader.unknown.add("cap-a"); // A fails permanently
        fx.queue.enqueue(req("cap-a"));
        fx.queue.enqueue(req("cap-b")); // B is healthy
        assertEquals("both jobs handled", 2, fx.worker.drain());
        assertEquals(Collections.singletonList("cap-b"), fx.listener.completed);
        assertEquals(1, fx.listener.failures.size());
    }

    @Test
    public void anAlreadyCompletedKeyIsShortCircuited() throws IOException {
        File dir = tempDir();
        Fx fx = new Fx(dir, 3);
        fx.queue.enqueue(req("cap-ok"));
        fx.worker.processOne(); // completes
        int savedAfterFirst = fx.repo.saved.size();
        // Re-enqueue by directly writing a NEW job for the same key would be blocked by the queue; simulate a
        // duplicate reaching a fresh worker over the same store — the idempotency guard avoids recomputation.
        fx.queue.enqueue(req("cap-ok")); // idempotent: returns the completed job, nothing new queued
        assertNull(fx.queue.takeNext());
        assertEquals("no reprocessing", savedAfterFirst, fx.repo.saved.size());
    }
}
