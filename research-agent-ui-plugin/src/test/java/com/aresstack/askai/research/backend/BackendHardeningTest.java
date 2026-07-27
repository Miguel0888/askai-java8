package com.aresstack.askai.research.backend;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Hardening of the memento backend: the state machine shares the backend's injected id/clock (so identical runs
 * are byte-for-byte deterministic), {@code canExecute} is a pure query that consumes no id, and an approval is
 * only marked processed after an accepted transition (a no-op reject never wedges the gate).
 */
public class BackendHardeningTest {

    private static ResearchIdGenerator sequentialIds() {
        final AtomicInteger counter = new AtomicInteger();
        return new ResearchIdGenerator() {
            public String newId() {
                return "id-" + counter.incrementAndGet();
            }
        };
    }

    private static ResearchClock fixedClock() {
        return new ResearchClock() {
            public long now() {
                return 1_000L;
            }
        };
    }

    private static final class Recorder implements ResearchSessionListener {
        final List<ResearchBackendEvent> events = new ArrayList<ResearchBackendEvent>();

        public void onEvent(ResearchBackendEvent event) {
            events.add(event);
        }

        ResearchBackendEvent last(ResearchBackendEventType type) {
            ResearchBackendEvent found = null;
            for (ResearchBackendEvent e : events) {
                if (e.getType() == type) {
                    found = e;
                }
            }
            return found;
        }
    }

    /** Drive a fresh backend to the first approval gate and return the approval id it produced. */
    private static String firstApprovalId(boolean probeCanExecuteBeforeRunning) {
        ManualResearchScheduler scheduler = new ManualResearchScheduler();
        FakeResearchSessionBackend backend =
                new FakeResearchSessionBackend(scheduler, fixedClock(), sequentialIds(), 10L);
        Recorder recorder = new Recorder();
        ResearchSessionHandle handle =
                backend.createSession(new ResearchProjectRequest("s", "p", "t"), recorder);
        if (probeCanExecuteBeforeRunning) {
            // Hammer the pure enablement query; it must not consume any id from the shared generator.
            for (int i = 0; i < 25; i++) {
                backend.canExecute(handle, ResearchCommandType.PAUSE);
                backend.canExecute(handle, ResearchCommandType.APPROVE_OUTLINE);
                backend.canExecute(handle, ResearchCommandType.CANCEL);
            }
        }
        scheduler.runUntilIdle();
        ResearchBackendEvent approval = recorder.last(ResearchBackendEventType.APPROVAL_REQUESTED);
        assertNotNull(approval);
        return approval.getApprovalId();
    }

    @Test
    public void identicalRunsAreDeterministic() {
        assertEquals("same command sequence + same generators must yield the same approval id",
                firstApprovalId(false), firstApprovalId(false));
    }

    @Test
    public void canExecuteConsumesNoIds() {
        assertEquals("probing canExecute must not perturb the id stream",
                firstApprovalId(false), firstApprovalId(true));
    }

    @Test
    public void noOpRejectDoesNotWedgeTheGate() {
        ManualResearchScheduler scheduler = new ManualResearchScheduler();
        FakeResearchSessionBackend backend =
                new FakeResearchSessionBackend(scheduler, fixedClock(), sequentialIds(), 10L);
        Recorder recorder = new Recorder();
        ResearchSessionHandle handle =
                backend.createSession(new ResearchProjectRequest("s", "p", "t"), recorder);

        // Advance all the way to the FINAL approval gate, where "request changes" has no valid transition.
        for (int i = 0; i < 6; i++) {
            scheduler.runUntilIdle();
            ResearchBackendEvent approval = recorder.last(ResearchBackendEventType.APPROVAL_REQUESTED);
            assertNotNull("expected an approval gate", approval);
            if (ResearchStateIds.FINALIZATION.equals(
                    recorder.last(ResearchBackendEventType.SESSION_STATE_CHANGED)
                            .getStateMemento().getPhaseId())) {
                break;
            }
            backend.approve(handle, approval.getApprovalId());
        }
        ResearchBackendEvent finalApproval = recorder.last(ResearchBackendEventType.APPROVAL_REQUESTED);
        assertEquals(ResearchStateIds.FINALIZATION,
                recorder.last(ResearchBackendEventType.SESSION_STATE_CHANGED).getStateMemento().getPhaseId());

        // A reject here has no valid "request changes" command -> it must be a no-op, NOT mark the id processed.
        backend.reject(handle, finalApproval.getApprovalId(), "nope");
        scheduler.runUntilIdle();

        // The same approval id is still pending and actionable: approving it completes the run.
        backend.approve(handle, finalApproval.getApprovalId());
        scheduler.runUntilIdle();
        assertTrue("the gate must still be actionable after a no-op reject",
                recorder.last(ResearchBackendEventType.COMPLETED) != null
                        || ResearchStateIds.COMPLETED.equals(
                        recorder.last(ResearchBackendEventType.SESSION_STATE_CHANGED)
                                .getStateMemento().getStateId()));
    }
}
