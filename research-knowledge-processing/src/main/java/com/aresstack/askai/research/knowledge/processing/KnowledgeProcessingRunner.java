package com.aresstack.askai.research.knowledge.processing;

/**
 * Owns the SINGLE serial background thread that drains the persistent FIFO for one project (§5, §12, §23) — one
 * FIFO, one worker, no parallelism and NO Swing/EDT. It processes {@link ProcessingStep#processOne()} repeatedly
 * and, when the queue is momentarily empty, waits until {@link #wake()} is signalled (or a bounded idle poll
 * elapses) rather than busy-spinning. The queue is persistent, so stopping never loses jobs: a job left mid-flight
 * is recovered to QUEUED on the next open.
 *
 * <p>Lifecycle: {@link #start()} on project/session open (after queue recovery), {@link #stop()} on close. Stop is
 * graceful — it lets the CURRENT step finish (a bounded unit; its persistence/index writes are the commit points)
 * and only interrupts as a last resort if the thread does not come back within the join timeout.</p>
 */
public final class KnowledgeProcessingRunner {

    /** One unit of work; returns true if a job was handled, false when the queue is empty. */
    public interface ProcessingStep {
        boolean processOne();
    }

    private final ProcessingStep step;
    private final String threadName;
    private final long idlePollMillis;
    private final long stopJoinMillis;
    private final Object wakeLock = new Object();

    private volatile boolean running;
    private Thread thread;

    public KnowledgeProcessingRunner(ProcessingStep step, String threadName, long idlePollMillis,
                                     long stopJoinMillis) {
        if (step == null) {
            throw new IllegalArgumentException("processing step is required");
        }
        this.step = step;
        this.threadName = threadName == null || threadName.trim().isEmpty()
                ? "knowledge-processing" : threadName;
        this.idlePollMillis = idlePollMillis <= 0 ? 1000L : idlePollMillis;
        this.stopJoinMillis = stopJoinMillis <= 0 ? 5000L : stopJoinMillis;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            public void run() {
                runLoop();
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isRunning() {
        return running;
    }

    /** Nudge the worker to look for new work immediately (e.g. right after an acceptance enqueue). */
    public void wake() {
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        wake(); // break out of an idle wait
        if (t != null) {
            try {
                t.join(stopJoinMillis);
                if (t.isAlive()) {
                    t.interrupt(); // last resort: a stuck step (e.g. a hung embedding call)
                    t.join(stopJoinMillis);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        while (running) {
            boolean handled;
            try {
                handled = step.processOne();
            } catch (RuntimeException unexpected) {
                // A step should classify its own failures; a leak here must not kill the loop.
                handled = false;
            }
            if (!handled && running) {
                synchronized (wakeLock) {
                    if (running) {
                        try {
                            wakeLock.wait(idlePollMillis);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }
    }
}
