package com.aresstack.askai.research.backend;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Production {@link ResearchScheduler} backed by a single daemon {@link ScheduledExecutorService}. */
public final class RealResearchScheduler implements ResearchScheduler {

    private final ScheduledExecutorService executor;

    public RealResearchScheduler() {
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "research-fake-backend");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
        final ScheduledFuture<?> future = executor.schedule(task, Math.max(0L, delayMillis),
                TimeUnit.MILLISECONDS);
        return new Cancellable() {
            public void cancel() {
                future.cancel(false);
            }
        };
    }

    @Override
    public void shutdown() {
        executor.shutdownNow();
    }
}
