package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.UiExecutor;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Commit-21c cancel-safety: an EDT operation that times out must never mutate later. {@code runOnEdtAndWait}
 * atomically cancels a still-pending runnable, so when the queued runnable finally runs it finds the CAS failed
 * and performs no mutation — no "ghost" detach/publish can change state after a refresh was already aborted.
 */
public class EdtCancelSafetyTest {

    private WorkspacePluginService service(QueuingUi ui) {
        return new WorkspacePluginService(Paths.get("."), "0.1.0", 1, ui, null);
    }

    @Test
    public void aTimedOutRunnableNeverMutatesWhenItRunsLater() throws Exception {
        QueuingUi ui = new QueuingUi();
        WorkspacePluginService service = service(ui);
        service.setEdtWaitTimeoutMillisForTest(50);
        final int[] mutations = {0};

        // The runnable is queued but never pumped before the timeout: the wait cancels it.
        WorkspacePluginService.UiCallResult result = service.runOnEdtAndWait(new Runnable() {
            public void run() {
                mutations[0]++;
            }
        });
        assertFalse("a timed-out call is not ok", result.ok);
        assertEquals("the task has not run yet", 0, mutations[0]);

        // Now the EDT finally drains its queue; the cancelled runnable must be inert.
        ui.pump();
        assertEquals("a cancelled runnable must never mutate", 0, mutations[0]);
    }

    @Test
    public void aRunnablePumpedInTimeRunsAndReportsSuccess() throws Exception {
        final QueuingUi ui = new QueuingUi();
        WorkspacePluginService service = service(ui);
        service.setEdtWaitTimeoutMillisForTest(5_000);
        final int[] mutations = {0};

        Thread pumper = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                ui.pump();
            }
        });
        pumper.start();

        WorkspacePluginService.UiCallResult result = service.runOnEdtAndWait(new Runnable() {
            public void run() {
                mutations[0]++;
            }
        });
        pumper.join();
        assertTrue("a runnable that ran in time is ok", result.ok);
        assertEquals(1, mutations[0]);
    }

    /** A UiExecutor that never runs runnables itself; the test drains the queue explicitly via {@link #pump()}. */
    private static final class QueuingUi implements UiExecutor {
        private final Deque<Runnable> queue = new ArrayDeque<Runnable>();

        public boolean isUiThread() {
            return false; // force runOnEdtAndWait onto the post-and-wait path
        }

        public void execute(Runnable runnable) {
            synchronized (queue) {
                queue.addLast(runnable);
            }
        }

        public void assertUiThread() {
        }

        void pump() {
            List<Runnable> toRun;
            synchronized (queue) {
                toRun = new ArrayList<Runnable>(queue);
                queue.clear();
            }
            for (Runnable r : toRun) {
                r.run();
            }
        }
    }
}
