package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifactStore;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.mcp.ResearchControlContext;
import com.aresstack.askai.research.mcp.ResearchControlEndpoint;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.store.FileResearchBriefStore;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The explicit, user-owned SCOPING → OUTLINE transition (RA-P6): one click on "Fragestellung freigeben &
 * weiter" approves the research brief working copy into an immutable revision and then dispatches exactly ONE
 * {@code SUBMIT_SCOPE}. Persist/approve happens strictly BEFORE the transition; a failed or empty approval
 * moves nothing; an identical already-approved brief creates no duplicate revision yet still advances; and the
 * action stays disabled outside scoping, without a brief, or while a foreground agent turn is in flight. No
 * model output, natural-language "weiter" or visualizer result may trigger it — only the button does. The
 * resulting phase is read back from the state machine's persisted memento, never assigned by the UI.
 */
public class ScopingBriefApprovalTest {

    @Test
    public void explicitApprovalApprovesTheBriefThenAdvancesExactlyOnceToOutline() {
        Fx fx = new Fx();
        toScopingRunningWithBrief(fx, "# Brief\nHow does pf4j isolate plugins?");

        assertTrue(fx.session.canApproveScopingBriefAndContinue());
        assertTrue(fx.session.approveScopingBriefAndContinue());

        // The brief was approved into exactly one immutable revision.
        assertEquals(1, fx.session.researchBriefStore().load().getApprovedRevisions().size());
        // Exactly ONE transition: SCOPING → OUTLINE, still RUNNING — NOT chained on to WAITING_APPROVAL.
        assertEquals(ResearchStateIds.OUTLINE, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
        assertEquals("OUTLINE", fx.session.getState().getPhaseLabel());
    }

    @Test
    public void aBriefApprovalFailureLeavesThePhaseUntouched() {
        Fx fx = new Fx();
        toScopingRunningWithBrief(fx, "# Brief\nsomething worth researching");

        // Sabotage the store so approval throws: put a FILE where the revisions directory must be created.
        File briefDir = new File(fx.resources.getProjectContext().getProjectDirectory(), "brief");
        File revisions = new File(briefDir, "revisions");
        try {
            assertTrue("test setup: block the revisions directory", revisions.createNewFile());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }

        assertFalse(fx.session.approveScopingBriefAndContinue());
        // Approve-before-transition: the failed artifact approval dispatched no SUBMIT_SCOPE.
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
    }

    @Test
    public void anAlreadyApprovedIdenticalBriefCreatesNoDuplicateRevisionButStillAdvances() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        FileResearchBriefStore store = fx.session.researchBriefStore();
        store.updateWorkingCopy("# Brief\nstable question", 1000L);
        store.approveCurrent(1000L); // pre-approve: revision 0001, working copy consumed
        assertEquals(1, store.load().getApprovedRevisions().size());

        // The effective brief is now the approved revision (no working copy). The explicit action approves
        // again (ALREADY_CURRENT → no new revision) and STILL performs the single transition.
        assertTrue(fx.session.canApproveScopingBriefAndContinue());
        assertTrue(fx.session.approveScopingBriefAndContinue());

        assertEquals("no duplicate revision", 1, store.load().getApprovedRevisions().size());
        assertEquals(ResearchStateIds.OUTLINE, fx.resources.currentState().getPhaseId());
    }

    @Test
    public void aBlankOrMissingBriefDisablesTheActionAndRejectsIt() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);

        // No brief at all: unavailable and a no-op.
        assertFalse(fx.session.canApproveScopingBriefAndContinue());
        assertFalse(fx.session.approveScopingBriefAndContinue());
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());

        // A whitespace-only brief is likewise not enough (it normalizes away, nothing is stored).
        fx.session.researchBriefStore().updateWorkingCopy("   \n  \t", 1000L);
        assertFalse(fx.session.canApproveScopingBriefAndContinue());
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());
    }

    @Test
    public void outsideScopingTheActionIsUnavailable() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null);        // SCOPING/RUNNING
        completeTurn(fx, 1L);
        fx.session.researchBriefStore().updateWorkingCopy("# Brief\nx", 1000L);
        fx.session.dispatch(ResearchCommandType.SUBMIT_SCOPE, null); // → OUTLINE
        assertEquals(ResearchStateIds.OUTLINE, fx.resources.currentState().getPhaseId());

        assertFalse(fx.session.canApproveScopingBriefAndContinue());
        assertFalse(fx.session.approveScopingBriefAndContinue());
        assertEquals(ResearchStateIds.OUTLINE, fx.resources.currentState().getPhaseId());
    }

    @Test
    public void aForegroundAgentTurnInFlightDisablesTheAction() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null); // SCOPING/RUNNING
        // Do NOT complete the turn: the greeting bootstrap's agentTurnInFlight is still set.
        fx.session.researchBriefStore().updateWorkingCopy("# Brief\nx", 1000L);
        assertFalse("a foreground agent turn blocks the action",
                fx.session.canApproveScopingBriefAndContinue());
        assertFalse(fx.session.approveScopingBriefAndContinue());
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());

        // Once the turn completes, the same brief makes the action legal.
        completeTurn(fx, 1L);
        assertTrue(fx.session.canApproveScopingBriefAndContinue());
    }

    @Test
    public void onlyTheExplicitActionTransitions_modelOutputHasZeroEffect() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null); // SCOPING/RUNNING
        completeTurn(fx, 1L);
        fx.session.researchBriefStore().updateWorkingCopy("# Brief\nx", 1000L);

        // Model output / advice — even a literal "weiter" — reaches the chat but must NOT move the phase.
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.ASSISTANT_MESSAGE)
                .envelope("evt-advice", "s1", "p1", 2L, 0L, 2L, null).text("weiter").build());
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());

        // The visualizer never ran here (no inference port) — the transition is independent of it: only the
        // explicit click advances the phase, exactly once.
        assertTrue(fx.session.approveScopingBriefAndContinue());
        assertEquals(ResearchStateIds.OUTLINE, fx.resources.currentState().getPhaseId());
    }

    @Test
    public void theResultingPhaseComesFromTheStateMachineNotTheUi() {
        Fx fx = new Fx();
        toScopingRunningWithBrief(fx, "# Brief\nx");

        assertTrue(fx.session.approveScopingBriefAndContinue());

        // The authoritative, persisted memento IS OUTLINE; the session view-model only mirrors that truth.
        assertEquals(ResearchStateIds.OUTLINE, fx.resources.currentState().getPhaseId());
        assertEquals("OUTLINE", fx.session.getState().getPhaseLabel());
    }

    // ------------------------------------------------------------------ helpers

    /** SCOPING/NEW → SCOPING/RUNNING, clear the greeting bootstrap's busy flag, then store a real brief. */
    private static void toScopingRunningWithBrief(Fx fx, String brief) {
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        fx.session.researchBriefStore().updateWorkingCopy(brief, 1000L);
    }

    /** Deliver a terminal COMPLETED event so {@code agentTurnInFlight} (set by activate()) is cleared. */
    private static void completeTurn(Fx fx, long seq) {
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED)
                .envelope("evt-complete-" + seq, "s1", "p1", seq, 0L, seq, null).build());
    }

    // ------------------------------------------------------------------ fixture (productive resources)

    private static final class Fx {
        final InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        final RecordingBackend backend = new RecordingBackend();
        final ProductiveResearchSessionResources resources;
        final ResearchAgentSession session;

        Fx() {
            final ProductiveResearchSessionResources[] holder = new ProductiveResearchSessionResources[1];
            ResearchControlEndpoint control = new ResearchControlEndpoint(registry, "s1", 7L,
                    new ResearchControlContext() {
                        public String currentPhaseId() {
                            return holder[0] == null ? ResearchStateIds.SCOPING
                                    : holder[0].currentState().getPhaseId();
                        }

                        public String currentStateId() {
                            return holder[0] == null ? ResearchStateIds.NEW
                                    : holder[0].currentState().getStateId();
                        }

                        public String statusLine() {
                            return currentPhaseId() + "/" + currentStateId();
                        }

                        public com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore artifactStore() {
                            return new ResearchArtifactStore();
                        }

                        public ResearchSourceRepository sourceRepository() {
                            return new com.aresstack.askai.research.sources.InMemoryResearchSourceRepository();
                        }

                        public String acceptCapture(String captureId) {
                            return null;
                        }
                    });
            control.open();
            resources = new ProductiveResearchSessionResources("s1", new OoResearchStateMachine("s1"),
                    null, null, null, tempProjectContext(), control, null, null, null);
            holder[0] = resources;
            session = new ResearchAgentSession(backend, null, new PlainHost(), "s1", "p1", resources);
            session.activate();
        }
    }

    /** Recording backend standing in for the ACP adapter (prompts + cancels only, like the real one). */
    private static final class RecordingBackend implements ResearchSessionBackend {
        public ResearchSessionHandle createSession(ResearchProjectRequest request,
                                                   ResearchSessionListener listener) {
            final String sessionId = request.getSessionId();
            final String projectId = request.getProjectId();
            return new ResearchSessionHandle() {
                public String getSessionId() {
                    return sessionId;
                }

                public String getProjectId() {
                    return projectId;
                }
            };
        }

        public boolean canExecute(ResearchSessionHandle handle, ResearchCommandType command) {
            return false;
        }

        public void executeCommand(ResearchSessionHandle handle, ResearchCommandType command) {
            throw new AssertionError("the productive bridge must never route through the backend");
        }

        public void submitPrompt(ResearchSessionHandle handle, ResearchPrompt prompt) {
        }

        public void approve(ResearchSessionHandle handle, String approvalId) {
        }

        public void reject(ResearchSessionHandle handle, String approvalId, String reason) {
        }

        public void pause(ResearchSessionHandle handle) {
        }

        public void resume(ResearchSessionHandle handle) {
        }

        public void cancel(ResearchSessionHandle handle) {
        }

        public void close(ResearchSessionHandle handle) {
        }
    }

    /** A file-backed project context in a fresh temp directory (the productive contract). */
    private static com.aresstack.askai.research.store.ResearchProjectContext tempProjectContext() {
        try {
            File dir = java.nio.file.Files.createTempDirectory("askai-research-test").toFile();
            return com.aresstack.askai.research.store.ResearchProjectContext.open("s1", dir);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------ minimal host with a no-op sink

    private static final class PlainHost implements AgentHostContext {
        private final AgentConversationSink sink = new NoopSink();

        public UiExecutor getUiExecutor() {
            return new UiExecutor() {
                public void execute(Runnable runnable) {
                    runnable.run();
                }

                public void assertUiThread() {
                }

                public boolean isUiThread() {
                    return true;
                }
            };
        }

        public ThemeService getThemeService() {
            return null;
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return null;
        }

        public NotificationService getNotificationService() {
            return null;
        }

        public WorkspaceStateStore getStateStore() {
            return null;
        }

        public PluginPathService getPluginPathService() {
            return null;
        }

        public AgentConversationSink getConversationSink() {
            return sink;
        }
    }

    /** A silent sink: this slice asserts on state, not chat surface. */
    private static final class NoopSink implements AgentConversationSink {
        public void appendUserMessage(String messageId, String markdown) {
        }

        public void appendAssistantMessage(String messageId, String markdown) {
        }

        public void startThinking(String activityId, String title) {
        }

        public void updateThinking(String activityId, String text) {
        }

        public void finishThinking(String activityId, String summary) {
        }

        public void startToolActivity(String activityId, String title, String explanation) {
        }

        public void updateToolActivity(String activityId, String title, String explanation) {
        }

        public void completeToolActivity(String activityId, String summary) {
        }

        public void failToolActivity(String activityId, String summary) {
        }

        public void requestApproval(String approvalId, String prompt) {
        }

        public void showProblem(String problemId, String publicMessage) {
        }
    }
}
