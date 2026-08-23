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
        final ResearchStateView[] viewHolder = new ResearchStateView[1];
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                viewHolder[0] = new ResearchStateView();
                viewHolder[0].setSnapshot(s);
            }
        });
        assertTrue(viewHolder[0].renderedText().contains("approval: a9"));
    }

    // ------------------------------------------------------------------ clickable phases

    @Test
    public void theNextPhaseIsClickableWithTheDomainAdvanceCommand() throws Exception {
        // SCOPING/running: SUBMIT_SCOPE leads into RESEARCH → the RESEARCH plate is the control.
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.SCOPING, ResearchStateIds.RUNNING, null, null, 2L, "");
        assertTrue(s.advanceCommandFor(ResearchStateIds.RESEARCH) == ResearchCommandType.SUBMIT_SCOPE);
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = acceptingView();
                view.setSnapshot(s);
                assertTrue("the research plate carries the submit-scope advance",
                        view.clickablePhasesForTest().get(ResearchStateIds.RESEARCH)
                                == ResearchCommandType.SUBMIT_SCOPE);
            }
        });
    }

    @Test
    public void approvalGatesOfferForwardAndBackwardPhaseClicks() throws Exception {
        // EVIDENCE approval: DRAFT = approve (forward), RESEARCH = request revision (backward).
        final ResearchStateSnapshot s = snapshot(ResearchStateIds.EVIDENCE,
                ResearchStateIds.WAITING_APPROVAL, null, "a1", 9L, "");
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = acceptingView();
                view.setSnapshot(s);
                java.util.Map<String, ResearchCommandType> clickable = view.clickablePhasesForTest();
                assertTrue(clickable.get(ResearchStateIds.DRAFT)
                        == ResearchCommandType.APPROVE_EVIDENCE);
                assertTrue("clicking back on research means request revision",
                        clickable.get(ResearchStateIds.RESEARCH)
                                == ResearchCommandType.REQUEST_REVISION);
            }
        });
    }

    @Test
    public void agentInternalAdvancesAreNotClickable() throws Exception {
        // RESEARCH/running advances via REQUEST_EVIDENCE_REVIEW — an AGENT decision, not user
        // vocabulary → no plate becomes a control (the user pauses/cancels, the agent advances).
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null, 5L, "");
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = acceptingView();
                view.setSnapshot(s);
                assertTrue(view.clickablePhasesForTest().isEmpty());
            }
        });
    }

    @Test
    public void phaseClickDispatchesAndRejectionFeedbackIsShown() throws Exception {
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.SCOPING, ResearchStateIds.RUNNING, null, null, 2L, "");
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                final java.util.List<ResearchCommandType> clicked =
                        new java.util.ArrayList<ResearchCommandType>();
                ResearchStateView view = new ResearchStateView();
                view.setCommandListener(new ResearchStateView.CommandListener() {
                    public com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            commandClicked(ResearchCommandType command) {
                        clicked.add(command);
                        return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                                com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                                        .Status.DISPATCH_FAILED, "The brief still needs a question.");
                    }
                });
                view.setSnapshot(s);
                view.clickPhaseForTest(ResearchStateIds.RESEARCH);
                assertTrue(clicked.contains(ResearchCommandType.SUBMIT_SCOPE));
                assertTrue("the honest domain reason is shown",
                        view.feedbackTextForTest().contains("The brief still needs a question."));

                view.setSnapshot(s); // the next state change clears stale feedback
                assertFalse(view.feedbackTextForTest().contains("needs a question"));
            }
        });
    }

    // ------------------------------------------------------------------ run controls

    @Test
    public void runControlsShowOnlyUserCommandsNeverFailOrBlock() throws Exception {
        // RESEARCH/running allows REQUEST_EVIDENCE_REVIEW, PAUSE, BLOCK, FAIL, CANCEL — but the
        // bar keeps only the USER run controls: Pause and Cancel.
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null, 5L, "");
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = acceptingView();
                view.setSnapshot(s);
                java.util.Set<String> labels = new java.util.HashSet<String>();
                for (com.aresstack.comiccontrols.control.ComicButton b : view.commandButtonsForTest()) {
                    labels.add(b.getText());
                }
                assertTrue(labels.contains(ResearchStateView.label(ResearchCommandType.PAUSE)));
                assertTrue(labels.contains(ResearchStateView.label(ResearchCommandType.CANCEL)));
                assertFalse("FAIL is an agent signal, never a user button",
                        labels.contains(ResearchStateView.label(ResearchCommandType.FAIL)));
                assertFalse("BLOCK is an agent signal, never a user button",
                        labels.contains(ResearchStateView.label(ResearchCommandType.BLOCK)));
                assertFalse("phase advances live on the plates, not in the bar",
                        labels.contains(ResearchStateView.label(
                                ResearchCommandType.REQUEST_EVIDENCE_REVIEW)));
            }
        });
    }

    @Test
    public void cancelIsRedTheOtherRunControlsYellow() throws Exception {
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null, 5L, "");
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = acceptingView();
                view.setSnapshot(s);
                for (com.aresstack.comiccontrols.control.ComicButton b : view.commandButtonsForTest()) {
                    boolean cancel = b.getText().equals(
                            ResearchStateView.label(ResearchCommandType.CANCEL));
                    assertTrue(b.getText(), b.getAccent()
                            == (cancel ? com.aresstack.comiccontrols.control.ComicButton.Accent.CRITICAL
                                       : com.aresstack.comiccontrols.control.ComicButton.Accent.ACTION));
                }
            }
        });
    }

    @Test
    public void withoutAListenerTheTabStaysReadOnly() throws Exception {
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.SCOPING, ResearchStateIds.RUNNING, null, null, 2L, "");
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = new ResearchStateView();
                view.setSnapshot(s);
                assertTrue("no command port, no buttons", view.commandButtonsForTest().isEmpty());
                assertTrue("no command port, no clickable plates",
                        view.clickablePhasesForTest().isEmpty());
            }
        });
    }

    private static ResearchStateView acceptingView() {
        ResearchStateView view = new ResearchStateView();
        view.setCommandListener(new ResearchStateView.CommandListener() {
            public com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                    commandClicked(ResearchCommandType command) {
                return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.accepted();
            }
        });
        return view;
    }
}
