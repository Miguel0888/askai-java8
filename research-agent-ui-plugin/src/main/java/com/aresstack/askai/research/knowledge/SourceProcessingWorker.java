package com.aresstack.askai.research.knowledge;

import java.util.Collections;
import java.util.List;

/**
 * Orchestrates one capture through the processing ports (§5): read → detect sentences → embed sentences →
 * segment into passages → embed + persist + index each passage. It OWNS no algorithm — every step is a port,
 * so in C2 it runs entirely behind test doubles (no real OpenNLP/embedding/Lucene yet). It knows nothing of
 * Swing, the state machine or {@code ResearchLoop}. Failures are attributed to a {@link SourceProcessingStage}
 * (§24): a missing capture is permanent; a transient port error is retryable and the job is re-queued at the
 * tail (bounded by {@code maxAttempts}), so one failing job never blocks the FIFO for the others.
 */
public final class SourceProcessingWorker {

    /** Observation seam (diagnostics §29 / UI events §28 come later); a no-op default keeps the worker pure. */
    public interface Listener {
        void onStarted(SourceProcessingJob job);

        void onCompleted(SourceProcessingJob job, int passageCount);

        void onFailed(SourceProcessingJob job, SourceProcessingFailure failure);

        Listener NONE = new Listener() {
            public void onStarted(SourceProcessingJob job) {
            }

            public void onCompleted(SourceProcessingJob job, int passageCount) {
            }

            public void onFailed(SourceProcessingJob job, SourceProcessingFailure failure) {
            }
        };
    }

    private final SourceProcessingQueue queue;
    private final SourceContentReader reader;
    private final SentenceDetectionService sentenceDetection;
    private final SentenceEmbeddingService sentenceEmbedding;
    private final SemanticPassageSegmenter segmenter;
    private final PassageEmbeddingService passageEmbedding;
    private final PassageRepository passageRepository;
    private final SemanticKnowledgeIndex knowledgeIndex;
    private final int maxAttempts;
    private final Listener listener;

    public SourceProcessingWorker(SourceProcessingQueue queue, SourceContentReader reader,
                                  SentenceDetectionService sentenceDetection,
                                  SentenceEmbeddingService sentenceEmbedding,
                                  SemanticPassageSegmenter segmenter,
                                  PassageEmbeddingService passageEmbedding,
                                  PassageRepository passageRepository,
                                  SemanticKnowledgeIndex knowledgeIndex,
                                  int maxAttempts, Listener listener) {
        this.queue = queue;
        this.reader = reader;
        this.sentenceDetection = sentenceDetection;
        this.sentenceEmbedding = sentenceEmbedding;
        this.segmenter = segmenter;
        this.passageEmbedding = passageEmbedding;
        this.passageRepository = passageRepository;
        this.knowledgeIndex = knowledgeIndex;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.listener = listener == null ? Listener.NONE : listener;
    }

    /** Process the next QUEUED job. @return true if one was handled (completed/failed), false if none. */
    public boolean processOne() {
        SourceProcessingJob job = queue.takeNext();
        if (job == null) {
            return false;
        }
        // Idempotency short-circuit (§4.3): this exact processing already completed → done, no recomputation.
        if (queue.isAlreadyCompleted(job.getRequest().idempotencyKey())) {
            queue.markCompleted(job);
            return true;
        }
        listener.onStarted(job);
        try {
            int passageCount = runPipeline(job);
            queue.markCompleted(job);
            listener.onCompleted(job, passageCount);
        } catch (StageFailure sf) {
            handleFailure(job, sf);
        } catch (RuntimeException unexpected) {
            handleFailure(job, new StageFailure(SourceProcessingStage.EXTRACTION,
                    String.valueOf(unexpected.getMessage()), true));
        }
        return true;
    }

    /** Drain the queue until empty; @return the number of jobs handled. Serial — no parallelism (§23). */
    public int drain() {
        int handled = 0;
        while (processOne()) {
            handled++;
        }
        return handled;
    }

    private int runPipeline(SourceProcessingJob job) {
        String captureId = job.getRequest().getCaptureId();
        ExtractedContent content = readContent(captureId);
        List<DetectedSentence> sentences = detectSentences(content);
        List<EmbeddedSentence> embedded = embedSentences(sentences);
        List<Passage> passages = segment(content, embedded);
        for (Passage passage : passages) {
            PassageVector vector = embedPassage(passage);
            persistPassage(passage, vector);
            indexPassage(passage, vector);
        }
        return passages.size();
    }

    // --- individual stage calls, each attributing a thrown failure to the right stage ---------------------

    private ExtractedContent readContent(String captureId) {
        ExtractedContent content;
        try {
            content = reader.read(captureId);
        } catch (RuntimeException ex) {
            throw new StageFailure(SourceProcessingStage.EXTRACTION, message(ex), true);
        }
        if (content == null) {
            // A capture that cannot be read is a permanent problem — retrying will not conjure it.
            throw new StageFailure(SourceProcessingStage.EXTRACTION, "unknown capture: " + captureId, false);
        }
        return content;
    }

    private List<DetectedSentence> detectSentences(ExtractedContent content) {
        try {
            return require(sentenceDetection.detectSentences(content),
                    SourceProcessingStage.SENTENCE_DETECTION);
        } catch (RuntimeException ex) {
            throw asStageFailure(ex, SourceProcessingStage.SENTENCE_DETECTION);
        }
    }

    private List<EmbeddedSentence> embedSentences(List<DetectedSentence> sentences) {
        try {
            return require(sentenceEmbedding.embed(sentences), SourceProcessingStage.EMBEDDING);
        } catch (RuntimeException ex) {
            throw asStageFailure(ex, SourceProcessingStage.EMBEDDING);
        }
    }

    private List<Passage> segment(ExtractedContent content, List<EmbeddedSentence> embedded) {
        try {
            return require(segmenter.segment(content, embedded), SourceProcessingStage.SENTENCE_DETECTION);
        } catch (RuntimeException ex) {
            throw asStageFailure(ex, SourceProcessingStage.SENTENCE_DETECTION);
        }
    }

    private static <T> T require(T value, SourceProcessingStage stage) {
        if (value == null) {
            throw new StageFailure(stage, "null result", true);
        }
        return value;
    }

    private static StageFailure asStageFailure(RuntimeException ex, SourceProcessingStage stage) {
        if (ex instanceof StageFailure) {
            return (StageFailure) ex;
        }
        return new StageFailure(stage, message(ex), true);
    }

    private PassageVector embedPassage(Passage passage) {
        try {
            return passageEmbedding.embedPassage(passage.getText());
        } catch (RuntimeException ex) {
            throw new StageFailure(SourceProcessingStage.EMBEDDING, message(ex), true);
        }
    }

    private void persistPassage(Passage passage, PassageVector vector) {
        try {
            passageRepository.save(passage, vector);
        } catch (RuntimeException ex) {
            throw new StageFailure(SourceProcessingStage.PASSAGE_PERSISTENCE, message(ex), true);
        }
    }

    private void indexPassage(Passage passage, PassageVector vector) {
        try {
            knowledgeIndex.indexPassages(Collections.singletonList(
                    new SemanticKnowledgeIndex.PassageDocument(passage.getPassageId(), passage.getSourceId(),
                            passage.getCaptureId(), passage.getText(), vector)));
        } catch (RuntimeException ex) {
            throw new StageFailure(SourceProcessingStage.INDEXING, message(ex), true);
        }
    }

    private void handleFailure(SourceProcessingJob job, StageFailure sf) {
        if (!sf.retryable || job.getAttempts() >= maxAttempts) {
            SourceProcessingFailure failure = SourceProcessingFailure.permanent(sf.stage, sf.reason);
            queue.markFailed(job, failure);
            listener.onFailed(job, failure);
            return;
        }
        SourceProcessingFailure failure = SourceProcessingFailure.retryable(sf.stage, sf.reason);
        queue.requeue(job.failed(failure)); // back to the tail for another attempt — never blocks the FIFO
        listener.onFailed(job, failure);
    }

    private static String message(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /** Internal control-flow carrier: a stage failed with a stage id, a short reason and a retryable flag. */
    private static final class StageFailure extends RuntimeException {
        final SourceProcessingStage stage;
        final String reason;
        final boolean retryable;

        StageFailure(SourceProcessingStage stage, String reason, boolean retryable) {
            super(reason);
            this.stage = stage;
            this.reason = reason == null ? "" : reason;
            this.retryable = retryable;
        }
    }
}
