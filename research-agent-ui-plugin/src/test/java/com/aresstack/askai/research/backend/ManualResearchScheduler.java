package com.aresstack.askai.research.backend;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A deterministic {@link ResearchScheduler} for tests: scheduled tasks are queued, not run on a timer, and
 * executed only when the test calls {@link #runUntilIdle()}. No {@code Thread.sleep}, no background threads —
 * the whole simulated run happens synchronously on the test thread, so timing is never a source of flakiness.
 */
public final class ManualResearchScheduler implements ResearchScheduler {

    private final Deque<Task> queue = new ArrayDeque<Task>();
    private boolean shutdown;

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
        final Task t = new Task(task);
        if (!shutdown) {
            queue.addLast(t);
        }
        return new Cancellable() {
            public void cancel() {
                t.cancelled = true;
            }
        };
    }

    @Override
    public void shutdown() {
        shutdown = true;
        queue.clear();
    }

    /** Runs queued tasks — including ones they schedule in turn — until the queue drains. */
    public int runUntilIdle() {
        int ran = 0;
        int guard = 0;
        while (!queue.isEmpty()) {
            if (++guard > 100_000) {
                throw new IllegalStateException("scheduler did not settle (possible progression loop)");
            }
            Task t = queue.pollFirst();
            if (!t.cancelled) {
                t.runnable.run();
                ran++;
            }
        }
        return ran;
    }

    /** @return whether at least one non-cancelled task is still queued. */
    public boolean hasPending() {
        for (Task t : queue) {
            if (!t.cancelled) {
                return true;
            }
        }
        return false;
    }

    private static final class Task {
        private final Runnable runnable;
        private volatile boolean cancelled;

        private Task(Runnable runnable) {
            this.runnable = runnable;
        }
    }
}
