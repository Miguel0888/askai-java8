package com.aresstack.askai.research.knowledge.processing.live;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** C5c: invalidations coalesce into ONE debounced rebuild; a failing rebuild never kills the runner. */
public class LiveKnowledgeProjectionRunnerTest {

    private static void awaitCount(AtomicInteger counter, int expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (counter.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(expected, counter.get());
    }

    @Test
    public void aBurstOfInvalidationsCoalescesIntoOneRebuild() throws Exception {
        final AtomicInteger rebuilds = new AtomicInteger();
        LiveKnowledgeProjectionRunner runner = new LiveKnowledgeProjectionRunner(
                new LiveKnowledgeProjectionRunner.RebuildStep() {
                    public void rebuild() {
                        rebuilds.incrementAndGet();
                    }
                }, "test-projection", 80L);
        runner.start();
        try {
            runner.knowledgeChanged();
            runner.sourceRelevanceChanged("source-1");
            runner.knowledgeChanged(); // burst within the debounce window
            awaitCount(rebuilds, 1, 2000L);
            Thread.sleep(150L);
            assertEquals("the burst coalesced into exactly one rebuild", 1, rebuilds.get());

            runner.knowledgeChanged(); // a later, separate change → one more rebuild
            awaitCount(rebuilds, 2, 2000L);
        } finally {
            runner.stop();
        }
    }

    @Test
    public void aFailingRebuildDoesNotKillTheRunner() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        LiveKnowledgeProjectionRunner runner = new LiveKnowledgeProjectionRunner(
                new LiveKnowledgeProjectionRunner.RebuildStep() {
                    public void rebuild() {
                        if (attempts.incrementAndGet() == 1) {
                            throw new IllegalStateException("transient projection failure");
                        }
                    }
                }, "test-projection-fail", 20L);
        runner.start();
        try {
            runner.knowledgeChanged();
            awaitCount(attempts, 1, 2000L);
            runner.knowledgeChanged(); // the next invalidation retries after the failure
            awaitCount(attempts, 2, 2000L);
            assertTrue(attempts.get() >= 2);
        } finally {
            runner.stop();
        }
    }
}
