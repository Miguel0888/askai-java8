package com.aresstack.askai.research.knowledge.processing.live;

/**
 * The debounced projection worker: invalidations mark the projection STALE; one daemon thread coalesces a
 * burst of invalidations into ONE deterministic full rebuild once the corpus has been quiet for the debounce
 * window. A rebuild failure is logged and leaves the projection stale-cleared (the NEXT invalidation triggers
 * a fresh attempt) — it never crashes the session and never blocks a session start. {@link #stop()} is
 * idempotent and joins the thread.
 */
public final class LiveKnowledgeProjectionRunner implements KnowledgeProjectionInvalidator {

    /** One deterministic full rebuild of the projection (read corpus → cluster → persist). */
    public interface RebuildStep {
        void rebuild();
    }

    private final RebuildStep step;
    private final long debounceMillis;
    private final Thread thread;
    private final Object lock = new Object();
    private boolean dirty;
    private long lastInvalidateAtMillis;
    private volatile boolean stopped;

    public LiveKnowledgeProjectionRunner(RebuildStep step, String threadName, long debounceMillis) {
        this.step = step;
        this.debounceMillis = Math.max(0L, debounceMillis);
        this.thread = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, threadName);
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    /** Idempotent: signal the loop to end and wait briefly for it. */
    public void stop() {
        stopped = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            thread.join(2000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void knowledgeChanged() {
        invalidate();
    }

    @Override
    public void sourceRelevanceChanged(String sourceId) {
        invalidate();
    }

    private void invalidate() {
        synchronized (lock) {
            dirty = true;
            lastInvalidateAtMillis = System.currentTimeMillis();
            lock.notifyAll();
        }
    }

    private void loop() {
        while (!stopped) {
            try {
                synchronized (lock) {
                    while (!stopped && !dirty) {
                        lock.wait();
                    }
                    if (stopped) {
                        return;
                    }
                    // Debounce: wait until the corpus has been QUIET for the whole window, coalescing every
                    // invalidation that arrives meanwhile into this one rebuild.
                    while (!stopped) {
                        long quietFor = System.currentTimeMillis() - lastInvalidateAtMillis;
                        if (quietFor >= debounceMillis) {
                            break;
                        }
                        lock.wait(Math.max(1L, debounceMillis - quietFor));
                    }
                    if (stopped) {
                        return;
                    }
                    dirty = false;
                }
                step.rebuild();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException rebuildFailed) {
                // Stale-cleared: the NEXT invalidation retries; a projection failure never kills the session.
                System.err.println("[research-knowledge] live projection rebuild failed: "
                        + rebuildFailed.getMessage());
            }
        }
    }
}
