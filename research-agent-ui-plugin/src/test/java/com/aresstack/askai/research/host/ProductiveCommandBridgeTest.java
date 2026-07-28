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
import com.aresstack.askai.research.backend.ResearchCommandDispatchResult;
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

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The command bridge in isolation: structured actions from the UI port reach the PRODUCTIVE state machine
 * (with tool republication), text stays on submitPrompt, invalid/late commands are rejected with their
 * structured status, observers get the state update (and a broken observer breaks nothing), and the ACP
 * backend itself contains no phase logic (canExecute is always false).
 */
public class ProductiveCommandBridgeTest {

    /** Recording backend standing in for the ACP adapter (prompts + cancels only, like the real one). */
    private static final class RecordingBackend implements ResearchSessionBackend {
        final List<String> prompts = new ArrayList<String>();
        int cancels;

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
            return false; // like AcpResearchSessionBackend: NO phase logic in the transport adapter
        }

        public void executeCommand(ResearchSessionHandle handle, ResearchCommandType command) {
            throw new AssertionError("the productive bridge must never route through the backend");
        }

        public void submitPrompt(ResearchSessionHandle handle, ResearchPrompt prompt) {
            prompts.add(prompt.getText());
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
            cancels++;
        }

        public void close(ResearchSessionHandle handle) {
        }
    }

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
                    null, null, null, new ResearchArtifactStore(), control, null, null, null, null);
            holder[0] = resources;
            session = new ResearchAgentSession(backend, null, new PlainHost(), "s1", "p1", resources);
            session.activate();
        }
    }

    @Test
    public void aPlainQuestionAdvancesGateFreeTransitionsAndStopsAtTheApprovalGate() {
        Fx fx = new Fx();
        // The natural flow (Commit 42): a plain question auto-advances START → SUBMIT_SCOPE →
        // PROPOSE_OUTLINE and STOPS at the outline approval gate — the machine, not the UI, decides.
        fx.session.submitPrompt("just a question");
        assertEquals(1, fx.backend.prompts.size());
        assertEquals(ResearchStateIds.OUTLINE, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.WAITING_APPROVAL, fx.resources.currentState().getStateId());

        // The user's approve (phase-correct command resolved FROM the machine's allowed set).
        fx.session.approveCurrent();
        // The next question auto-advances START_RESEARCH → the research phase actually runs.
        fx.session.submitPrompt("just a question");
        assertEquals(ResearchStateIds.RESEARCH, fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
        // The session's view model mirrors the single truth (direct-run UI executor).
        assertEquals("RESEARCH", fx.session.getState().getPhaseLabel());
        // Text never became a command; no command became text.
        assertEquals(2, fx.backend.prompts.size());
    }

    @Test
    public void invalidAndLateCommandsAreRejectedStructurally() {
        Fx fx = new Fx();
        ResearchCommandDispatchResult wrongPhase =
                fx.session.dispatch(ResearchCommandType.APPROVE_DRAFT, null);
        assertEquals(ResearchCommandDispatchResult.Status.INVALID_PHASE, wrongPhase.getStatus());

        assertEquals(ResearchCommandDispatchResult.Status.COMMAND_NOT_AVAILABLE,
                fx.session.dispatch(null, null).getStatus());

        // Closed resources (generation retired / session closed) → SESSION_CLOSED, nothing is reached.
        fx.resources.close();
        assertEquals(ResearchCommandDispatchResult.Status.SESSION_CLOSED,
                fx.session.dispatch(ResearchCommandType.START, null).getStatus());
    }

    @Test
    public void pauseCancelAlsoStopTheAgentTurnAndObserversAreIsolated() {
        Fx fx = new Fx();
        assertTrue(fx.session.dispatch(ResearchCommandType.START, null).isAccepted());
        final AtomicInteger updates = new AtomicInteger();
        fx.session.addStateListener(new Runnable() {
            public void run() {
                updates.incrementAndGet();
                throw new IllegalStateException("broken observer");
            }
        });
        final AtomicInteger healthy = new AtomicInteger();
        fx.session.addStateListener(new Runnable() {
            public void run() {
                healthy.incrementAndGet();
            }
        });
        assertTrue(fx.session.dispatch(ResearchCommandType.PAUSE, null).isAccepted());
        assertEquals("PAUSE also cancels the running agent turn", 1, fx.backend.cancels);
        assertTrue("observers received the state update", updates.get() >= 1);
        assertTrue("a broken observer must not block later observers", healthy.get() >= 1);
        assertTrue(fx.session.dispatch(ResearchCommandType.RESUME, null).isAccepted());
    }

    @Test
    public void backendAdapterHasNoPhaseLogic() {
        RecordingBackend backend = new RecordingBackend();
        assertFalse(backend.canExecute(null, ResearchCommandType.START));
        // And the real ACP adapter behaves identically by contract:
        com.aresstack.askai.research.acp.AcpResearchSessionBackend acp =
                new com.aresstack.askai.research.acp.AcpResearchSessionBackend(null, null, null, null);
        assertFalse(acp.canExecute(null, ResearchCommandType.START));
    }

    // ------------------------------------------------------------------ minimal host

    private static final class PlainHost implements AgentHostContext {
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
            return null;
        }
    }
}
