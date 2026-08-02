package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContext;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifactStore;
import com.aresstack.askai.research.agent.ScopingComposerAccessoryContribution;
import com.aresstack.askai.research.agent.ScopingSupportView;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.backend.ScopingAssistantUpdate;
import com.aresstack.askai.research.mcp.ResearchControlContext;
import com.aresstack.askai.research.mcp.ResearchControlEndpoint;
import com.aresstack.askai.research.search.ManualWebSearchPort;
import com.aresstack.askai.research.search.ManualWebSearchRequest;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import javax.swing.AbstractButton;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Slice S1: a yellow scoping suggestion is a USER-SERVICE web search, NOT an agent chat turn. Through the REAL
 * accessory (built by its host contribution) a genuine tag {@code doClick()} must reach the
 * {@link ManualWebSearchPort} exactly once and must NOT submit a chat turn, start an agent turn, dispatch a
 * state-machine command or approve any artifact. The service is phase-independent — it works the same in
 * SCOPING, OUTLINE and RESEARCH.
 */
public class ManualSearchWiringTest {

    @Test
    public void aYellowSuggestionClickRunsAManualSearchNotAChatTurn() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null); // SCOPING/RUNNING
        completeTurn(fx, 1L);
        RecordingManualWebSearchPort port = new RecordingManualWebSearchPort();
        fx.session.setManualWebSearchPort(port);
        feedSuggestion(fx, "wearables audio video");

        int promptsBefore = fx.backend.prompts.size();
        ScopingSupportView view = buildAccessoryView(fx);
        List<AbstractButton> tags = view.getSuggestionButtons();
        assertEquals("the projection renders exactly one suggestion tag", 1, tags.size());

        tags.get(0).doClick(); // REAL yellow tag → accessory callback → live session → manual search port

        assertEquals("the manual search ran exactly once", 1, port.queries.size());
        assertEquals("with exactly the suggestion query", "wearables audio video", port.queries.get(0));
        assertEquals("no chat/agent turn was submitted", promptsBefore, fx.backend.prompts.size());
        assertEquals("the phase is unchanged", ResearchStateIds.SCOPING,
                fx.resources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.RUNNING, fx.resources.currentState().getStateId());
        assertEquals("no artifact approval happened", 0,
                fx.session.researchBriefStore().load().getApprovedRevisions().size());
    }

    @Test
    public void theManualSearchServiceIsPhaseIndependent() {
        Fx fx = new Fx();
        fx.session.dispatch(ResearchCommandType.START, null); // SCOPING/RUNNING
        completeTurn(fx, 1L);
        RecordingManualWebSearchPort port = new RecordingManualWebSearchPort();
        fx.session.setManualWebSearchPort(port);

        // SCOPING: accepted, phase unchanged.
        fx.session.requestManualWebSearch("query in scoping");
        assertEquals(1, port.queries.size());
        assertEquals(ResearchStateIds.SCOPING, fx.resources.currentState().getPhaseId());

        // OUTLINE: still accepted (the service never gates on the phase), phase unchanged by the search.
        fx.session.dispatch(ResearchCommandType.SUBMIT_SCOPE, null);
        assertEquals(ResearchStateIds.OUTLINE, fx.resources.currentState().getPhaseId());
        fx.session.requestManualWebSearch("query in outline");
        assertEquals(2, port.queries.size());
        assertEquals(ResearchStateIds.OUTLINE, fx.resources.currentState().getPhaseId());

        // A blank query is ignored (no spurious search).
        fx.session.requestManualWebSearch("   ");
        assertEquals(2, port.queries.size());
    }

    // ------------------------------------------------------------------ helpers

    private static ScopingSupportView buildAccessoryView(Fx fx) {
        ComposerAccessory accessory = new ScopingComposerAccessoryContribution()
                .create(new FakeComposerContext(fx.session));
        return (ScopingSupportView) accessory.getComponent();
    }

    /** Deliver a scoping projection so the accessory renders a clickable yellow suggestion tag. */
    private static void feedSuggestion(Fx fx, String query) {
        ScopingAssistantUpdate projection = new ScopingAssistantUpdate(
                ResearchStateIds.SCOPING,
                Collections.singletonList(new ScopingAssistantUpdate.Suggestion(query, "explore", 1)),
                "NEUTRAL", "");
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.SCOPING_PROJECTION)
                .envelope("evt-proj", "s1", "p1", 2L, 0L, 2L, null)
                .scopingProjection(projection).build());
    }

    private static void completeTurn(Fx fx, long seq) {
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED)
                .envelope("evt-complete-" + seq, "s1", "p1", seq, 0L, seq, null).build());
    }

    // ------------------------------------------------------------------ fixture

    private static final class RecordingManualWebSearchPort implements ManualWebSearchPort {
        final List<String> queries = new ArrayList<String>();

        public void search(ManualWebSearchRequest request) {
            queries.add(request.getQuery());
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
                    null, null, null, tempProjectContext(), control, null, null, null);
            holder[0] = resources;
            session = new ResearchAgentSession(backend, null, new PlainHost(), "s1", "p1", resources);
            session.activate();
        }
    }

    private static final class RecordingBackend implements ResearchSessionBackend {
        final List<String> prompts = new ArrayList<String>();

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
        }

        public void close(ResearchSessionHandle handle) {
        }
    }

    private static com.aresstack.askai.research.store.ResearchProjectContext tempProjectContext() {
        try {
            File dir = java.nio.file.Files.createTempDirectory("askai-research-test").toFile();
            return com.aresstack.askai.research.store.ResearchProjectContext.open("s1", dir);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class FakeComposerContext implements ComposerAccessoryContext {
        private final AgentSession session;

        FakeComposerContext(AgentSession session) {
            this.session = session;
        }

        public AgentSession getSession() {
            return session;
        }

        public UiExecutor getUiExecutor() {
            return inlineUi();
        }

        public ThemeService getThemeService() {
            return null;
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return null;
        }
    }

    private static final class PlainHost implements AgentHostContext {
        public UiExecutor getUiExecutor() {
            return inlineUi();
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
            return new NoopSink();
        }
    }

    private static UiExecutor inlineUi() {
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
