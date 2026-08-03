package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.PassageSegmentation;

/**
 * Orchestrates one accepted capture through the CANONICAL knowledge pipeline: read the capture as a domain
 * {@link SourceCapture}, run {@link PassageSegmentation} (sentence segmentation → semantic passages → each
 * passage's own embedding — all inside the pipeline), then persist the sentences and passages via {@link
 * PassageStore}. It owns NO algorithm and NO domain model, and knows nothing of Swing, the state machine or
 * {@code ResearchLoop}. Failures are attributed to a {@link SourceProcessingStage} (§24): a missing capture is
 * permanent; a transient pipeline error is retryable and the job is re-queued at the tail (bounded by
 * {@code maxAttempts}), so one failing job never blocks the FIFO for the others.
 *
 * <p>Variant B (deliberate): the productive daemon is NOT started until the semantic index (C4) exists — a
 * {@code COMPLETED} job then honestly means the whole pipeline ran. This class is exercised behind test doubles
 * meanwhile.</p>
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
    private final SourceCaptureReader captureReader;
    private final PassageSegmentation passageSegmentation;
    private final PassageStore passageStore;
    private final int maxAttempts;
    private final Listener listener;

    public SourceProcessingWorker(SourceProcessingQueue queue, SourceCaptureReader captureReader,
                                  PassageSegmentation passageSegmentation, PassageStore passageStore,
                                  int maxAttempts, Listener listener) {
        this.queue = queue;
        this.captureReader = captureReader;
        this.passageSegmentation = passageSegmentation;
        this.passageStore = passageStore;
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
        SourceCapture capture = readCapture(job.getRequest().getCaptureId());
        PassageSegmentation.Result result = segment(capture);
        storePassages(capture, result);
        return result.getPassages().size();
    }

    private SourceCapture readCapture(String captureId) {
        SourceCapture capture;
        try {
            capture = captureReader.read(captureId);
        } catch (RuntimeException ex) {
            throw new StageFailure(SourceProcessingStage.EXTRACTION, message(ex), true);
        }
        if (capture == null) {
            throw new StageFailure(SourceProcessingStage.EXTRACTION, "unknown capture: " + captureId, false);
        }
        return capture;
    }

    private PassageSegmentation.Result segment(SourceCapture capture) {
        try {
            // PassageSegmentation bundles sentence detection + per-passage embedding; the embedding call is the
            // dominant transient failure, so a thrown segmentation error is treated as a retryable EMBEDDING one.
            return passageSegmentation.segment(capture);
        } catch (RuntimeException ex) {
            throw new StageFailure(SourceProcessingStage.EMBEDDING, message(ex), true);
        }
    }

    private void storePassages(SourceCapture capture, PassageSegmentation.Result result) {
        try {
            passageStore.store(capture, result.getSentences(), result.getPassages());
        } catch (RuntimeException ex) {
            throw new StageFailure(SourceProcessingStage.PASSAGE_PERSISTENCE, message(ex), true);
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
