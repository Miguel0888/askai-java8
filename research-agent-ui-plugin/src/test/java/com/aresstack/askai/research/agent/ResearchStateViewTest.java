package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchPhaseState;
import com.aresstack.askai.research.state.oo.ResearchStateFactory;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The State visualization renders a domain-derived snapshot (no transition table of its own). */
public class ResearchStateViewTest {

    private final ResearchStateFactory factory = ResearchStateFactory.getInstance();

    private ResearchStateSnapshot snapshot(String phaseId, String stateId, String continuation,
                                           String approvalId, long rev, String problem) {
        ResearchPhaseState phase = factory.phase(phaseId,
                factory.state(phaseId, stateId, continuation, approvalId));
        return ResearchStateSnapshot.of(phase, rev, problem);
    }

    @Test
    public void showsCurrentPhaseAndSubstate() {
        String text = ResearchStateView.render(
                snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null, 5L, ""));
        assertTrue(text.contains("RESEARCH"));
        assertTrue(text.contains("active"));
        assertTrue(text.contains("RUNNING"));
        assertTrue(text.contains("revision: 5"));
    }

    @Test
    public void showsPausedWithContinuation() {
        String text = ResearchStateView.render(
                snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.PAUSED, ResearchStateIds.RUNNING, null, 6L, ""));
        assertTrue(text.contains("PAUSED"));
        assertTrue(text.contains("continuation: RUNNING"));
    }

    @Test
    public void showsBlockedWithReason() {
        String text = ResearchStateView.render(
                snapshot(ResearchStateIds.OUTLINE, ResearchStateIds.BLOCKED, ResearchStateIds.RUNNING, null, 6L,
                        "waiting for credentials"));
        assertTrue(text.contains("BLOCKED"));
        assertTrue(text.contains("reason: waiting for credentials"));
    }

    @Test
    public void showsFailedWithReason() {
        String text = ResearchStateView.render(
                snapshot(ResearchStateIds.OUTLINE, ResearchStateIds.FAILED, ResearchStateIds.RUNNING, null, 6L,
                        "provider down"));
        assertTrue(text.contains("FAILED"));
        assertTrue(text.contains("reason: provider down"));
    }

    @Test
    public void showsApprovalWithId() {
        String text = ResearchStateView.render(
                snapshot(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL, null, "appr-1", 7L, ""));
        assertTrue(text.contains("WAITING_APPROVAL"));
        assertTrue(text.contains("approval: appr-1"));
    }

    @Test
    public void showsCompletedAndCancelled() {
        String completed = ResearchStateView.render(
                snapshot(ResearchStateIds.FINALIZATION, ResearchStateIds.COMPLETED, null, null, 20L, ""));
        assertTrue(completed.contains("FINALIZATION"));
        assertTrue(completed.contains("COMPLETED"));

        String cancelled = ResearchStateView.render(
                snapshot(ResearchStateIds.SCOPING, ResearchStateIds.CANCELLED, null, null, 3L, ""));
        assertTrue(cancelled.contains("CANCELLED"));
    }

    @Test
    public void earlierPhasesAreMarkedCompleted() {
        ResearchStateSnapshot s = snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null, 5L, "");
        assertTrue(s.getCompletedPhaseIds().contains(ResearchStateIds.SCOPING));
        assertTrue(s.getCompletedPhaseIds().contains(ResearchStateIds.OUTLINE));
        assertFalse(s.getCompletedPhaseIds().contains(ResearchStateIds.EVIDENCE));
    }

    @Test
    public void allowedCommandsComeFromTheDomain() {
        ResearchStateSnapshot s = snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null, 5L, "");
        assertTrue(s.getAllowedCommands().contains(ResearchCommandType.REQUEST_EVIDENCE_REVIEW));
        assertTrue(s.getAllowedCommands().contains(ResearchCommandType.PAUSE));
        assertFalse(s.getAllowedCommands().contains(ResearchCommandType.APPROVE_OUTLINE));
    }

    @Test
    public void viewRendersSnapshotIntoText() throws Exception {
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL, null, "a9", 8L, "");
        final javax.swing.JTextArea[] holder = new javax.swing.JTextArea[1];
        final ResearchStateView[] viewHolder = new ResearchStateView[1];
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                viewHolder[0] = new ResearchStateView();
                viewHolder[0].setSnapshot(s);
            }
        });
        assertTrue(viewHolder[0].renderedText().contains("approval: a9"));
    }
}
