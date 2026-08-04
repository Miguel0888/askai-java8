package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Drives the hierarchical OO state model directly (no legacy pair) to pin phases, gates and interruptions. */
public class HierarchicalResearchStateTest {

    private final ResearchStateFactory factory = ResearchStateFactory.getInstance();
    private final AtomicLong ids = new AtomicLong();
    private final ResearchStateContext ctx = new ResearchStateContext("s1", factory,
            new ResearchStateContext.IdGenerator() {
                public String newId() {
                    return "a" + ids.incrementAndGet();
                }
            });

    private static ResearchCommand cmd(ResearchCommandType type) {
        return ResearchCommand.of(type, "c-" + type);
    }

    private ResearchPhaseState accept(ResearchPhaseState phase, ResearchCommandType type,
                                      String expectedPhaseId, String expectedStateId) {
        OoTransition t = phase.handle(ctx, cmd(type));
        assertTrue(type + " should be accepted from " + phase.getPhaseId() + "/"
                + phase.getCurrentState().getStateId(), t.isAccepted());
        assertEquals(expectedPhaseId, t.getNext().getPhaseId());
        assertEquals(expectedStateId, t.getNext().getCurrentState().getStateId());
        return t.getNext();
    }

    private void reject(ResearchPhaseState phase, ResearchCommandType type) {
        assertFalse(type + " should be rejected", phase.handle(ctx, cmd(type)).isAccepted());
    }

    @Test
    public void happyPathWalksEveryPhase() {
        ResearchPhaseState s = factory.initialPhase();
        assertEquals(ResearchStateIds.SCOPING, s.getPhaseId());
        assertEquals(ResearchStateIds.NEW, s.getCurrentState().getStateId());
        s = accept(s, ResearchCommandType.START, ResearchStateIds.SCOPING, ResearchStateIds.RUNNING);
        // C5: a confirmed scope goes STRAIGHT to research; the first approval gate is the evidence gate.
        s = accept(s, ResearchCommandType.SUBMIT_SCOPE, ResearchStateIds.RESEARCH, ResearchStateIds.WAITING);
        s = accept(s, ResearchCommandType.START_RESEARCH, ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING);
        s = accept(s, ResearchCommandType.REQUEST_EVIDENCE_REVIEW, ResearchStateIds.EVIDENCE, ResearchStateIds.WAITING_APPROVAL);
        assertTrue(s.getCurrentState().requiresApproval());
        assertNotNull(s.getCurrentState().getPendingApprovalId());
        s = accept(s, ResearchCommandType.APPROVE_EVIDENCE, ResearchStateIds.DRAFT, ResearchStateIds.WAITING);
        s = accept(s, ResearchCommandType.START_DRAFTING, ResearchStateIds.DRAFT, ResearchStateIds.RUNNING);
        s = accept(s, ResearchCommandType.REQUEST_DRAFT_REVIEW, ResearchStateIds.REVIEW, ResearchStateIds.WAITING_APPROVAL);
        s = accept(s, ResearchCommandType.APPROVE_DRAFT, ResearchStateIds.FINALIZATION, ResearchStateIds.RUNNING);
        s = accept(s, ResearchCommandType.REQUEST_FINAL_REVIEW, ResearchStateIds.FINALIZATION, ResearchStateIds.WAITING_APPROVAL);
        s = accept(s, ResearchCommandType.APPROVE_FINAL, ResearchStateIds.FINALIZATION, ResearchStateIds.COMPLETED);
        assertTrue(s.getCurrentState().isTerminal());
    }

    @Test
    public void approvalGatesCannotBeSkipped() {
        ResearchPhaseState outlineRunning = factory.phase(ResearchStateIds.OUTLINE,
                factory.state(ResearchStateIds.OUTLINE, ResearchStateIds.RUNNING, null, null));
        reject(outlineRunning, ResearchCommandType.APPROVE_OUTLINE); // must propose first
        reject(outlineRunning, ResearchCommandType.START_RESEARCH);
    }

    @Test
    public void requestChangesReturnsToTheCorrectStep() {
        ResearchPhaseState outlineGate = factory.phase(ResearchStateIds.OUTLINE,
                factory.state(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL, null, "a"));
        assertEquals(ResearchStateIds.OUTLINE,
                accept(outlineGate, ResearchCommandType.REQUEST_OUTLINE_CHANGES,
                        ResearchStateIds.OUTLINE, ResearchStateIds.RUNNING).getPhaseId());

        ResearchPhaseState evidenceGate = factory.phase(ResearchStateIds.EVIDENCE,
                factory.state(ResearchStateIds.EVIDENCE, ResearchStateIds.WAITING_APPROVAL, null, "a"));
        accept(evidenceGate, ResearchCommandType.REQUEST_REVISION, ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING);

        ResearchPhaseState reviewGate = factory.phase(ResearchStateIds.REVIEW,
                factory.state(ResearchStateIds.REVIEW, ResearchStateIds.WAITING_APPROVAL, null, "a"));
        accept(reviewGate, ResearchCommandType.REQUEST_REVISION, ResearchStateIds.DRAFT, ResearchStateIds.RUNNING);
    }

    @Test
    public void pauseResumePreservesExactContinuation() {
        ResearchPhaseState running = factory.phase(ResearchStateIds.RESEARCH,
                factory.state(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null));
        ResearchPhaseState paused = accept(running, ResearchCommandType.PAUSE,
                ResearchStateIds.RESEARCH, ResearchStateIds.PAUSED);
        assertEquals(ResearchStateIds.RUNNING, paused.getCurrentState().getContinuationStateId());
        accept(paused, ResearchCommandType.RESUME, ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING);
        // Pause is only allowed from running.
        reject(paused, ResearchCommandType.PAUSE);
    }

    @Test
    public void blockUnblockPreservesExactContinuationEvenFromApprovalGate() {
        ResearchPhaseState gate = factory.phase(ResearchStateIds.OUTLINE,
                factory.state(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL, null, "a"));
        ResearchPhaseState blocked = accept(gate, ResearchCommandType.BLOCK,
                ResearchStateIds.OUTLINE, ResearchStateIds.BLOCKED);
        assertEquals(ResearchStateIds.WAITING_APPROVAL, blocked.getCurrentState().getContinuationStateId());
        accept(blocked, ResearchCommandType.UNBLOCK, ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL);
    }

    @Test
    public void failRetryReturnsToContinuation() {
        ResearchPhaseState running = factory.phase(ResearchStateIds.RESEARCH,
                factory.state(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null));
        ResearchPhaseState failed = accept(running, ResearchCommandType.FAIL,
                ResearchStateIds.RESEARCH, ResearchStateIds.FAILED);
        accept(failed, ResearchCommandType.RETRY, ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING);
    }

    @Test
    public void cancelFromEveryNonTerminalState() {
        String[][] states = {
                {ResearchStateIds.SCOPING, ResearchStateIds.RUNNING},
                {ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL},
                {ResearchStateIds.RESEARCH, ResearchStateIds.WAITING},
        };
        for (String[] pair : states) {
            String approval = ResearchStateIds.WAITING_APPROVAL.equals(pair[1]) ? "a" : null;
            ResearchPhaseState s = factory.phase(pair[0], factory.state(pair[0], pair[1], null, approval));
            accept(s, ResearchCommandType.CANCEL, pair[0], ResearchStateIds.CANCELLED);
        }
        // Interruptions are non-terminal too.
        ResearchPhaseState paused = factory.phase(ResearchStateIds.RESEARCH,
                factory.state(ResearchStateIds.RESEARCH, ResearchStateIds.PAUSED, ResearchStateIds.RUNNING, null));
        accept(paused, ResearchCommandType.CANCEL, ResearchStateIds.RESEARCH, ResearchStateIds.CANCELLED);
    }

    @Test
    public void terminalStatesRejectEverything() {
        ResearchPhaseState completed = factory.phase(ResearchStateIds.FINALIZATION,
                factory.state(ResearchStateIds.FINALIZATION, ResearchStateIds.COMPLETED, null, null));
        reject(completed, ResearchCommandType.CANCEL);
        reject(completed, ResearchCommandType.RETRY);
        ResearchPhaseState cancelled = factory.phase(ResearchStateIds.SCOPING,
                factory.state(ResearchStateIds.SCOPING, ResearchStateIds.CANCELLED, null, null));
        reject(cancelled, ResearchCommandType.START);
        assertTrue(cancelled.getCurrentState().isTerminal());
    }

    @Test
    public void illegalPhaseStateCombinationIsNotConstructible() {
        try {
            factory.state(ResearchStateIds.SCOPING, ResearchStateIds.WAITING_APPROVAL, null, "a");
            fail("SCOPING has no approval gate");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            factory.state(ResearchStateIds.EVIDENCE, ResearchStateIds.RUNNING, null, null);
            fail("EVIDENCE has no running state");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void mementoRoundTripAndInvalidRejected() {
        ResearchPhaseState paused = factory.phase(ResearchStateIds.RESEARCH,
                factory.state(ResearchStateIds.RESEARCH, ResearchStateIds.PAUSED, ResearchStateIds.RUNNING, null));
        ResearchStateMemento memento = factory.snapshot(paused, 17L);
        assertEquals(ResearchStateIds.RESEARCH, memento.getPhaseId());
        assertEquals(ResearchStateIds.PAUSED, memento.getStateId());
        assertEquals(ResearchStateIds.RUNNING, memento.getContinuationStateId());
        assertEquals(17L, memento.getRevision());

        ResearchPhaseState restored = factory.restore(memento);
        assertEquals(ResearchStateIds.RESEARCH, restored.getPhaseId());
        assertEquals(ResearchStateIds.PAUSED, restored.getCurrentState().getStateId());
        assertEquals(ResearchStateIds.RUNNING, restored.getCurrentState().getContinuationStateId());

        try {
            factory.restore(new ResearchStateMemento(ResearchStateIds.SCOPING,
                    ResearchStateIds.COMPLETED, null, 1L, null));
            fail("SCOPING/completed is not a legal combination");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void allowedCommandsReflectTheState() {
        ResearchPhaseState running = factory.phase(ResearchStateIds.RESEARCH,
                factory.state(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null));
        assertTrue(running.getAllowedCommands().contains(ResearchCommandType.REQUEST_EVIDENCE_REVIEW));
        assertTrue(running.getAllowedCommands().contains(ResearchCommandType.PAUSE));
        assertFalse(running.getAllowedCommands().contains(ResearchCommandType.APPROVE_OUTLINE));

        ResearchPhaseState gate = factory.phase(ResearchStateIds.OUTLINE,
                factory.state(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL, null, "a"));
        assertTrue(gate.getAllowedCommands().contains(ResearchCommandType.APPROVE_OUTLINE));
        assertTrue(gate.getAllowedCommands().contains(ResearchCommandType.REQUEST_OUTLINE_CHANGES));
        assertFalse(gate.getAllowedCommands().contains(ResearchCommandType.PAUSE));
    }
}
