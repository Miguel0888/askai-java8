package com.aresstack.askai.research.knowledge.processing;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The serial runner drains available work in FIFO order, wakes on demand, and stops cleanly without losing jobs. */
public class KnowledgeProcessingRunnerTest {

    /** A tiny thread-safe FIFO of labels the step pops in order; records what it processed. */
    private static final class FifoStep implements KnowledgeProcessingRunner.ProcessingStep {
        final Deque<String> pending = new ArrayDeque<String>();
        final List<String> processed = new ArrayList<String>();

        synchronized void enqueue(String id) {
            pending.addLast(id);
        }

        public synchronized boolean processOne() {
            String id = pending.pollFirst();
            if (id == null) {
                return false;
            }
            processed.add(id);
            return true;
        }

        synchronized int remaining() {
            return pending.size();
        }
    }

    private static void waitUntil(AtomicBoolean flagUnused, FifoStep step, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (step) {
                if (step.processed.size() >= expected && step.remaining() == 0) {
                    return;
                }
            }
            Thread.sleep(5);
        }
    }

    @Test
    public void drainsQueuedWorkInFifoOrderThenIdles() throws Exception {
        FifoStep step = new FifoStep();
        step.enqueue("a");
        step.enqueue("b");
        step.enqueue("c");
        KnowledgeProcessingRunner runner = new KnowledgeProcessingRunner(step, "test-runner", 50L, 2000L);
        runner.start();
        try {
            waitUntil(new AtomicBoolean(), step, 3);
            synchronized (step) {
                assertEquals(java.util.Arrays.asList("a", "b", "c"), step.processed);
            }
            assertTrue(runner.isRunning());
        } finally {
            runner.stop();
        }
        assertFalse(runner.isRunning());
    }

    @Test
    public void processesWorkEnqueuedAfterStartAndWokenPromptly() throws Exception {
        FifoStep step = new FifoStep();
        KnowledgeProcessingRunner runner = new KnowledgeProcessingRunner(step, "test-runner", 5000L, 2000L);
        runner.start();
        try {
            step.enqueue("late");
            runner.wake(); // don't wait for the (long) idle poll
            waitUntil(new AtomicBoolean(), step, 1);
            synchronized (step) {
                assertEquals(java.util.Arrays.asList("late"), step.processed);
            }
        } finally {
            runner.stop();
        }
    }

    @Test
    public void stopIsCleanAndDoesNotProcessAfterwards() throws Exception {
        FifoStep step = new FifoStep();
        KnowledgeProcessingRunner runner = new KnowledgeProcessingRunner(step, "test-runner", 50L, 2000L);
        runner.start();
        runner.stop();
        assertFalse(runner.isRunning());
        int before;
        synchronized (step) {
            before = step.processed.size();
        }
        step.enqueue("after-stop");
        Thread.sleep(100);
        synchronized (step) {
            assertEquals("nothing is processed after stop", before, step.processed.size());
            assertEquals("the job is not lost (still queued)", 1, step.remaining());
        }
    }
}
