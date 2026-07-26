package com.aresstack.askai.research.backend;

/**
 * Abstraction over delayed task execution so the fake backend can use a real scheduler in production and a
 * manual, deterministic one in tests (no {@code Thread.sleep}). No {@code ScheduledFuture}/{@code
 * ExecutorService} leaks into the fachlich port — those stay inside the scheduler implementation.
 */
public interface ResearchScheduler {

    /** Schedule {@code task} after {@code delayMillis}; the returned handle can cancel it if not yet run. */
    Cancellable schedule(Runnable task, long delayMillis);

    /** Releases any underlying resources; idempotent. */
    void shutdown();

    interface Cancellable {
        void cancel();
    }
}
