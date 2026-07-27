package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Commit 22 invariants for the native, memento-based state machine: the {@link ResearchStateMemento} is the
 * single live truth, an interruption preserves the exact continuation <em>and</em> the pending approval id, and
 * an invalid memento is rejected (never guessed/repaired).
 */
public class OoResearchStateMachineTest {

    private final OoResearchStateMachine machine = new OoResearchStateMachine("s-1",
            new OoResearchStateMachine.IdGenerator() {
                private final AtomicInteger n = new AtomicInteger();
                public String newId() {
                    return "id-" + n.incrementAndGet();
                }
            },
            new OoResearchStateMachine.TimeSource() {
                public long now() {
                    return 0L;
                }
            });

    private ResearchStateMemento accept(ResearchStateMemento current, ResearchCommandType type) {
        ResearchStateTransitionResult r = machine.dispatch(current, ResearchCommand.of(type, type.name()));
        assertTrue(type + " should be accepted in " + current.getPhaseId() + "/" + current.getStateId(),
                r.isAccepted());
        return r.getNextMemento();
    }

    /** Drive to OUTLINE / WAITING_APPROVAL, the first human approval gate. */
    private ResearchStateMemento toOutlineApproval() {
        ResearchStateMemento m = machine.initialMemento();
        m = accept(m, ResearchCommandType.START);          // scoping/running
        m = accept(m, ResearchCommandType.SUBMIT_SCOPE);   // outline/running
        m = accept(m, ResearchCommandType.PROPOSE_OUTLINE); // outline/waiting_approval (+approval id)
        assertEquals(ResearchStateIds.OUTLINE, m.getPhaseId());
        assertEquals(ResearchStateIds.WAITING_APPROVAL, m.getStateId());
        assertNotNull("entering an approval gate assigns an approval id", m.getPendingApprovalId());
        return m;
    }

    @Test
    public void initialMementoIsScopingNew() {
        ResearchStateMemento m = machine.initialMemento();
        assertEquals(ResearchStateIds.SCOPING, m.getPhaseId());
        assertEquals(ResearchStateIds.NEW, m.getStateId());
        assertEquals(0L, m.getRevision());
        assertNull(m.getPendingApprovalId());
    }

    @Test
    public void blockFromWaitingApprovalPreservesTheApprovalIdAcrossUnblock() {
        ResearchStateMemento approval = toOutlineApproval();
        String approvalId = approval.getPendingApprovalId();

        ResearchStateMemento blocked = accept(approval, ResearchCommandType.BLOCK);
        assertEquals(ResearchStateIds.BLOCKED, blocked.getStateId());
        assertEquals("the interruption remembers the exact continuation",
                ResearchStateIds.WAITING_APPROVAL, blocked.getContinuationStateId());
        assertEquals("the approval id survives the interruption", approvalId, blocked.getPendingApprovalId());

        ResearchStateMemento unblocked = accept(blocked, ResearchCommandType.UNBLOCK);
        assertEquals(ResearchStateIds.OUTLINE, unblocked.getPhaseId());
        assertEquals(ResearchStateIds.WAITING_APPROVAL, unblocked.getStateId());
        assertEquals("unblock restores the SAME approval id, not a fresh one",
                approvalId, unblocked.getPendingApprovalId());

        // And the restored gate is fully functional.
        ResearchStateMemento approved = accept(unblocked, ResearchCommandType.APPROVE_OUTLINE);
        assertEquals(ResearchStateIds.RESEARCH, approved.getPhaseId());
        assertNull("leaving the gate clears the approval id", approved.getPendingApprovalId());
    }

    @Test
    public void failFromWaitingApprovalThenRetryRestoresTheApprovalGate() {
        ResearchStateMemento approval = toOutlineApproval();
        String approvalId = approval.getPendingApprovalId();

        ResearchStateMemento failed = accept(approval, ResearchCommandType.FAIL);
        assertEquals(ResearchStateIds.FAILED, failed.getStateId());
        assertEquals(ResearchStateIds.WAITING_APPROVAL, failed.getContinuationStateId());
        assertEquals(approvalId, failed.getPendingApprovalId());

        ResearchStateMemento retried = accept(failed, ResearchCommandType.RETRY);
        assertEquals(ResearchStateIds.WAITING_APPROVAL, retried.getStateId());
        assertEquals(approvalId, retried.getPendingApprovalId());
    }

    @Test
    public void pauseResumeReturnsToRunningWithNoApproval() {
        ResearchStateMemento m = accept(machine.initialMemento(), ResearchCommandType.START); // scoping/running
        ResearchStateMemento paused = accept(m, ResearchCommandType.PAUSE);
        assertEquals(ResearchStateIds.PAUSED, paused.getStateId());
        assertEquals(ResearchStateIds.RUNNING, paused.getContinuationStateId());
        assertNull(paused.getPendingApprovalId());

        ResearchStateMemento resumed = accept(paused, ResearchCommandType.RESUME);
        assertEquals(ResearchStateIds.SCOPING, resumed.getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, resumed.getStateId());
        assertNull(resumed.getPendingApprovalId());
    }

    @Test
    public void snapshotRoundTripThroughAnInterruptionIsStable() {
        ResearchStateMemento blocked = accept(toOutlineApproval(), ResearchCommandType.BLOCK);
        // restore -> snapshot must reproduce the same ids (no lost continuation / approval id).
        ResearchPhaseState restored = ResearchStateFactory.getInstance().restore(blocked);
        ResearchStateMemento again = ResearchStateFactory.getInstance().snapshot(restored, blocked.getRevision());
        assertEquals(blocked.getPhaseId(), again.getPhaseId());
        assertEquals(blocked.getStateId(), again.getStateId());
        assertEquals(blocked.getContinuationStateId(), again.getContinuationStateId());
        assertEquals(blocked.getPendingApprovalId(), again.getPendingApprovalId());
        assertEquals(blocked.getRevision(), again.getRevision());
    }

    @Test
    public void invalidMementoIsRejectedNotGuessed() {
        // BLOCKED requires a continuation state id; a null one is an illegal combination.
        ResearchStateMemento invalid =
                new ResearchStateMemento(ResearchStateIds.OUTLINE, ResearchStateIds.BLOCKED, null, 5L, null);
        ResearchStateTransitionResult r =
                machine.dispatch(invalid, ResearchCommand.of(ResearchCommandType.UNBLOCK, "x"));
        assertFalse(r.isAccepted());
        assertNotNull(r.getRejectionReason());
        assertEquals("a rejected dispatch leaves the memento unchanged", invalid, r.getNextMemento());
    }

    @Test
    public void illegalTransitionIsRejectedWithUnchangedMemento() {
        ResearchStateMemento running = accept(machine.initialMemento(), ResearchCommandType.START);
        ResearchStateTransitionResult r =
                machine.dispatch(running, ResearchCommand.of(ResearchCommandType.APPROVE_OUTLINE, "x"));
        assertFalse(r.isAccepted());
        assertEquals(running, r.getNextMemento());
    }

    @Test
    public void revisionAdvancesOnlyOnAcceptance() {
        ResearchStateMemento m0 = machine.initialMemento();
        ResearchStateMemento m1 = accept(m0, ResearchCommandType.START);
        assertEquals(m0.getRevision() + 1, m1.getRevision());
        ResearchStateTransitionResult rejected =
                machine.dispatch(m1, ResearchCommand.of(ResearchCommandType.APPROVE_FINAL, "x"));
        assertFalse(rejected.isAccepted());
        assertEquals(m1.getRevision(), rejected.getNextMemento().getRevision());
    }
}
