package com.aresstack.askai.research.state;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Table-driven coverage of the research lifecycle: gates, revisions, pause/resume, block/fail, cancel. */
public class ResearchStateMachineTest {

    private final AtomicLong ids = new AtomicLong();

    private ResearchStateMachine sm() {
        return new DefaultResearchStateMachine("s1",
                () -> "e" + ids.incrementAndGet(),
                () -> 1000L);
    }

    private static ResearchCommand cmd(ResearchCommandType type) {
        return ResearchCommand.of(type, "c-" + type);
    }

    private ResearchSessionState accept(ResearchStateMachine sm, ResearchSessionState state,
                                        ResearchCommandType type, ResearchPhase phase, ResearchRunState run) {
        ResearchTransitionResult result = sm.dispatch(state, cmd(type));
        assertTrue(type + " should be accepted from " + state, result.isAccepted());
        assertEquals(phase, result.getState().getPhase());
        assertEquals(run, result.getState().getRunState());
        assertEquals(state.getRevision() + 1, result.getState().getRevision());
        assertFalse(result.getEvents().isEmpty());
        return result.getState();
    }

    private void reject(ResearchStateMachine sm, ResearchSessionState state, ResearchCommandType type) {
        ResearchTransitionResult result = sm.dispatch(state, cmd(type));
        assertFalse(type + " should be rejected from " + state, result.isAccepted());
        assertSame("rejected transition must not change state", state, result.getState());
        assertEquals(state.getRevision(), result.getState().getRevision());
    }

    @Test
    public void fullHappyPathWalksEveryPhaseAndIncrementsRevision() {
        ResearchStateMachine sm = sm();
        ResearchSessionState s = ResearchSessionState.initial();
        assertEquals(0L, s.getRevision());
        s = accept(sm, s, ResearchCommandType.START, ResearchPhase.SCOPING, ResearchRunState.RUNNING);
        // C5: a confirmed scope goes STRAIGHT to research (the OUTLINE phase stays for old sessions
        // and returns later as the post-evidence freeze step).
        s = accept(sm, s, ResearchCommandType.SUBMIT_SCOPE, ResearchPhase.RESEARCH, ResearchRunState.WAITING_FOR_USER);
        s = accept(sm, s, ResearchCommandType.START_RESEARCH, ResearchPhase.RESEARCH, ResearchRunState.RUNNING);
        s = accept(sm, s, ResearchCommandType.REQUEST_EVIDENCE_REVIEW, ResearchPhase.EVIDENCE, ResearchRunState.WAITING_FOR_USER);
        s = accept(sm, s, ResearchCommandType.APPROVE_EVIDENCE, ResearchPhase.DRAFT, ResearchRunState.WAITING_FOR_USER);
        s = accept(sm, s, ResearchCommandType.START_DRAFTING, ResearchPhase.DRAFT, ResearchRunState.RUNNING);
        s = accept(sm, s, ResearchCommandType.REQUEST_DRAFT_REVIEW, ResearchPhase.REVIEW, ResearchRunState.WAITING_FOR_USER);
        s = accept(sm, s, ResearchCommandType.APPROVE_DRAFT, ResearchPhase.FINALIZATION, ResearchRunState.RUNNING);
        s = accept(sm, s, ResearchCommandType.REQUEST_FINAL_REVIEW, ResearchPhase.FINALIZATION, ResearchRunState.WAITING_FOR_USER);
        s = accept(sm, s, ResearchCommandType.APPROVE_FINAL, ResearchPhase.FINALIZATION, ResearchRunState.COMPLETED);
        assertTrue(s.isTerminal());
    }

    @Test
    public void approvalGatesCannotBeSkipped() {
        ResearchStateMachine sm = sm();
        // Cannot start research before the outline is approved.
        ResearchSessionState outlineRunning = new ResearchSessionState(
                ResearchPhase.OUTLINE, ResearchRunState.RUNNING, 5L);
        reject(sm, outlineRunning, ResearchCommandType.START_RESEARCH);
        reject(sm, outlineRunning, ResearchCommandType.APPROVE_OUTLINE); // approve only from WAITING

        // Cannot approve final outside FINALIZATION/WAITING.
        reject(sm, new ResearchSessionState(ResearchPhase.REVIEW, ResearchRunState.WAITING_FOR_USER, 9L),
                ResearchCommandType.APPROVE_FINAL);
    }

    @Test
    public void requestChangesReturnsToTheCorrectWorkingStep() {
        ResearchStateMachine sm = sm();
        assertEquals(ResearchPhase.OUTLINE, sm.dispatch(
                new ResearchSessionState(ResearchPhase.OUTLINE, ResearchRunState.WAITING_FOR_USER, 3L),
                cmd(ResearchCommandType.REQUEST_OUTLINE_CHANGES)).getState().getPhase());
        assertEquals(ResearchPhase.RESEARCH, sm.dispatch(
                new ResearchSessionState(ResearchPhase.EVIDENCE, ResearchRunState.WAITING_FOR_USER, 3L),
                cmd(ResearchCommandType.REQUEST_REVISION)).getState().getPhase());
        assertEquals(ResearchPhase.DRAFT, sm.dispatch(
                new ResearchSessionState(ResearchPhase.REVIEW, ResearchRunState.WAITING_FOR_USER, 3L),
                cmd(ResearchCommandType.REQUEST_REVISION)).getState().getPhase());
    }

    @Test
    public void pauseResumePreservePhaseAndPauseIsRunningOnly() {
        ResearchStateMachine sm = sm();
        ResearchSessionState running = new ResearchSessionState(
                ResearchPhase.RESEARCH, ResearchRunState.RUNNING, 4L);
        ResearchSessionState paused = accept(sm, running, ResearchCommandType.PAUSE,
                ResearchPhase.RESEARCH, ResearchRunState.PAUSED);
        accept(sm, paused, ResearchCommandType.RESUME, ResearchPhase.RESEARCH, ResearchRunState.RUNNING);
        // Pause is not allowed from WAITING_FOR_USER.
        reject(sm, new ResearchSessionState(ResearchPhase.OUTLINE, ResearchRunState.WAITING_FOR_USER, 4L),
                ResearchCommandType.PAUSE);
    }

    @Test
    public void blockUnblockAndFailRetryPreservePhase() {
        ResearchStateMachine sm = sm();
        ResearchSessionState running = new ResearchSessionState(
                ResearchPhase.RESEARCH, ResearchRunState.RUNNING, 2L);
        ResearchSessionState blocked = accept(sm, running, ResearchCommandType.BLOCK,
                ResearchPhase.RESEARCH, ResearchRunState.BLOCKED);
        accept(sm, blocked, ResearchCommandType.UNBLOCK, ResearchPhase.RESEARCH, ResearchRunState.RUNNING);

        ResearchSessionState failed = accept(sm, running, ResearchCommandType.FAIL,
                ResearchPhase.RESEARCH, ResearchRunState.FAILED);
        accept(sm, failed, ResearchCommandType.RETRY, ResearchPhase.RESEARCH, ResearchRunState.RUNNING);
    }

    @Test
    public void cancelFromAnyNonTerminalStateAndTerminalIsImmutable() {
        ResearchStateMachine sm = sm();
        ResearchSessionState[] nonTerminal = {
            new ResearchSessionState(ResearchPhase.SCOPING, ResearchRunState.RUNNING, 1L),
            new ResearchSessionState(ResearchPhase.OUTLINE, ResearchRunState.WAITING_FOR_USER, 1L),
            new ResearchSessionState(ResearchPhase.RESEARCH, ResearchRunState.PAUSED, 1L),
            new ResearchSessionState(ResearchPhase.DRAFT, ResearchRunState.BLOCKED, 1L),
            new ResearchSessionState(ResearchPhase.REVIEW, ResearchRunState.FAILED, 1L)
        };
        for (ResearchSessionState state : nonTerminal) {
            assertEquals(ResearchRunState.CANCELLED,
                    sm.dispatch(state, cmd(ResearchCommandType.CANCEL)).getState().getRunState());
        }
        ResearchSessionState completed = new ResearchSessionState(
                ResearchPhase.FINALIZATION, ResearchRunState.COMPLETED, 12L);
        reject(sm, completed, ResearchCommandType.CANCEL);
        reject(sm, completed, ResearchCommandType.START);
        reject(sm, new ResearchSessionState(ResearchPhase.SCOPING, ResearchRunState.CANCELLED, 3L),
                ResearchCommandType.RETRY);
    }

    @Test
    public void eventsCarrySessionIdRevisionAndTimestamp() {
        ResearchStateMachine sm = sm();
        ResearchTransitionResult result = sm.dispatch(ResearchSessionState.initial(),
                cmd(ResearchCommandType.START));
        ResearchEvent event = result.getEvents().get(0);
        assertEquals("s1", event.getSessionId());
        assertEquals(1L, event.getRevision());
        assertEquals(1000L, event.getTimestamp());
        assertEquals(ResearchEventType.SessionStateChanged, event.getType());
    }
}
