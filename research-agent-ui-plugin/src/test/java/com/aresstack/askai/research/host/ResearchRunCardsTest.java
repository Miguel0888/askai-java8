package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifactStore;
import com.aresstack.askai.research.agent.ResearchPlaybook;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchRunOutcomeInfo;
import com.aresstack.askai.research.backend.ResearchRunProgressInfo;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Commit 55: the guided result flow instead of the debug stream. One in-place progress card per run, a
 * localized result card with REAL typed actions after the terminal outcome, an invisible technical terminal,
 * and none of the internal identifiers (stop-reason enums, source ids, raw redirect URLs) in normal chat.
 */
public class ResearchRunCardsTest {

    /** Records every sink interaction; showActionCard keeps options + handler for pressing buttons. */
    private static final class RecordingSink implements AgentConversationSink {
        final List<String> assistantMessages = new ArrayList<String>();
        final List<String> startedActivities = new ArrayList<String>();
        final List<String> updatedActivities = new ArrayList<String>();
        final List<String> activityBodies = new ArrayList<String>();
        final List<String> completedActivities = new ArrayList<String>();
        final List<String> cardMarkdowns = new ArrayList<String>();
        final List<List<ActionOption>> cardOptions = new ArrayList<List<ActionOption>>();
        final List<ActionHandler> cardHandlers = new ArrayList<ActionHandler>();
        final List<String> technicalLog = new ArrayList<String>();

        @Override
        public void appendTechnicalLog(String line) {
            technicalLog.add(line);
        }

        public void appendUserMessage(String messageId, String markdown) {
        }

        public void appendAssistantMessage(String messageId, String markdown) {
            assistantMessages.add(markdown);
        }

        public void startThinking(String activityId, String title) {
        }

        public void updateThinking(String activityId, String text) {
        }

        public void finishThinking(String activityId, String summary) {
        }

        public void startToolActivity(String activityId, String title, String explanation) {
            startedActivities.add(activityId);
            activityBodies.add(explanation);
        }

        public void updateToolActivity(String activityId, String title, String explanation) {
            updatedActivities.add(activityId);
            activityBodies.add(explanation);
        }

        public void completeToolActivity(String activityId, String summary) {
            completedActivities.add(activityId);
        }

        public void failToolActivity(String activityId, String summary) {
        }

        public void requestApproval(String approvalId, String prompt) {
        }

        public void showProblem(String problemId, String publicMessage) {
        }

        @Override
        public void showActionCard(String cardId, String markdown, List<ActionOption> actions,
                                   ActionHandler handler) {
            cardMarkdowns.add(markdown);
            cardOptions.add(actions);
            cardHandlers.add(handler);
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

    private static final class Fx {
        final InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        final RecordingBackend backend = new RecordingBackend();
        final RecordingSink sink = new RecordingSink();
        final com.aresstack.askai.research.sources.InMemoryResearchSourceRepository productiveSources =
                com.aresstack.askai.research.sources.InMemoryResearchSourceRepository.empty();
        final ProductiveResearchSessionResources resources;
        final ResearchAgentSession session;
        long sequence;

        Fx() {
            ResearchPlaybook.setLanguage(ResearchPlaybook.Language.ENGLISH);
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
                    null, productiveSources, null, new ResearchArtifactStore(), control, null, null, null, null);
            holder[0] = resources;
            session = new ResearchAgentSession(backend, null, new SinkHost(sink), "s1", "p1", resources);
            session.activate();
        }

        /** Drive the consultative flow to a running research with the stored question. */
        void reachRunningResearch() {
            session.submitPrompt("investigate pf4j");
            session.submitPrompt("focus on isolation");
            session.submitPrompt("no");
            // The outline approval is now a card with real buttons — press "Approve".
            press(lastCardActionId("approve"));
            assertEquals(ResearchStateIds.RESEARCH, resources.currentState().getPhaseId());
            assertEquals(ResearchStateIds.RUNNING, resources.currentState().getStateId());
        }

        String lastCardActionId(String id) {
            List<AgentConversationSink.ActionOption> options =
                    sink.cardOptions.get(sink.cardOptions.size() - 1);
            for (AgentConversationSink.ActionOption option : options) {
                if (option.getId().equals(id)) {
                    return id;
                }
            }
            throw new AssertionError("no action '" + id + "' on the last card");
        }

        void press(String actionId) {
            sink.cardHandlers.get(sink.cardHandlers.size() - 1).onAction(actionId);
        }

        void event(ResearchBackendEvent.Builder builder) {
            session.onEvent(builder
                    .envelope("e-" + sequence, "s1", "p1", 0L, 0L, ++sequence, null).build());
        }
    }

    @Test
    public void oneInPlaceProgressCardPerRunAndLogsStayTechnical() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        int bubblesBefore = fx.sink.assistantMessages.size();

        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 1, 0, 1, 2, "READING_PAGE",
                        "https://example-a.org/x")));
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_LOG)
                .activity("research-run-p1", null, "", "")
                .text("accepted source-3"));
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 6, 4, 2, 9, "READING_PAGE", "")));

        assertEquals("exactly ONE progress card is started", 1, fx.sink.startedActivities.size());
        assertTrue("later progress updates the SAME card", fx.sink.updatedActivities.size() >= 1);
        assertEquals("no new chat bubbles for pages/sources/logs",
                bubblesBefore, fx.sink.assistantMessages.size());
        String lastBody = fx.sink.activityBodies.get(fx.sink.activityBodies.size() - 1);
        assertTrue("counters are readable", lastBody.contains("6 pages checked"));
        assertFalse("raw log lines never clutter the visible card", lastBody.contains("source-3"));
        assertTrue("technical log goes to the host's Technical details area",
                fx.sink.technicalLog.contains("accepted source-3"));
    }

    @Test
    public void progressCardShowsQueryFinalHostTitleAndABoundedHistory() {
        Fx fx = new Fx();
        fx.reachRunningResearch();

        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 0, 0, 0, 1, "SEARCHING",
                        "pf4j plugin isolation", "", "", "")));
        String body = fx.sink.activityBodies.get(fx.sink.activityBodies.size() - 1);
        assertTrue("the actually used search query is visible", body.contains("Searching the web for"));
        assertTrue(body.contains("pf4j plugin isolation"));

        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 1, 0, 1, 2, "READING_PAGE", "",
                        "https://pf4j.org/doc/getting-started.html", "pf4j.org",
                        "PF4J – Plugin Framework for Java")));
        body = fx.sink.activityBodies.get(fx.sink.activityBodies.size() - 1);
        assertTrue("currentUrl context is no longer ignored: the final host is shown",
                body.contains("Currently open"));
        assertTrue(body.contains("pf4j.org"));
        assertTrue("the page title is shown", body.contains("PF4J – Plugin Framework for Java"));
        assertTrue("the query stays visible while browsing", body.contains("pf4j plugin isolation"));
        assertFalse("raw URLs never appear in the visible card", body.contains("https://"));

        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 1, 1, 1, 3, "SOURCE_ACCEPTED", "",
                        "https://pf4j.org/doc/getting-started.html", "pf4j.org",
                        "PF4J – Plugin Framework for Java")));
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 2, 1, 1, 5, "PAGE_SKIPPED", "",
                        "https://baeldung.com/x", "baeldung.com", "Some unrelated tutorial")));
        body = fx.sink.activityBodies.get(fx.sink.activityBodies.size() - 1);
        assertTrue("an accepted source is visibly recorded", body.contains("✓ pf4j.org"));
        assertTrue("a skipped page is visibly not relevant", body.contains("– baeldung.com"));
        assertTrue(body.contains("not relevant"));

        // The visible history stays bounded: after many accepted pages only the last 5 entries remain.
        for (int i = 1; i <= 7; i++) {
            fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                    .activity("research-run-p1", null, "", "")
                    .runProgress(new ResearchRunProgressInfo("p1", 2 + i, 1 + i, 1 + i, 5 + i,
                            "SOURCE_ACCEPTED", "", "https://host" + i + ".example/a",
                            "host" + i + ".example", "Page " + i)));
        }
        body = fx.sink.activityBodies.get(fx.sink.activityBodies.size() - 1);
        int entries = 0;
        for (String line : body.split("\n")) {
            if (line.startsWith("✓ ") || line.startsWith("– ")) {
                entries++;
            }
        }
        assertEquals("at most five history entries stay visible", 5, entries);
        assertTrue("the newest entry is present", body.contains("✓ host7.example — Page 7"));
        assertFalse("the oldest entries dropped out", body.contains("host1.example"));
    }

    @Test
    public void outcomeRendersOneLocalizedResultCardWithTypedActionsAndFreesTheComposer() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        assertEquals(SubmissionAvailability.BUSY, fx.session.getChatTarget().getAvailability());

        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_PROGRESS)
                .activity("research-run-p1", null, "", "")
                .runProgress(new ResearchRunProgressInfo("p1", 10, 7, 1, 30, "OPENING_PAGE", "")));
        int cardsBefore = fx.sink.cardMarkdowns.size();
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                .activity("research-run-p1", null, "", "")
                .runOutcome(new ResearchRunOutcomeInfo("p1", "TOOL_BUDGET_EXHAUSTED", 10, 7, 1, 3, 2,
                        true, "INSUFFICIENT_HOST_DIVERSITY", "CONTINUE_RESEARCH")));
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED).text(""));

        assertEquals("exactly one result card", cardsBefore + 1, fx.sink.cardMarkdowns.size());
        String card = fx.sink.cardMarkdowns.get(fx.sink.cardMarkdowns.size() - 1);
        assertTrue("plain-language summary", card.contains("7 relevant sources"));
        assertFalse("no stop-reason enum names", card.contains("TOOL_BUDGET_EXHAUSTED"));
        assertFalse("no internal source ids", card.contains("source-"));
        assertFalse("no run-stopped debug lines", card.contains("RESEARCH_RUN_STOPPED"));
        assertEquals("the progress card was closed", 1, fx.sink.completedActivities.size()
                - countOf(fx.sink.completedActivities, "research-turn"));
        assertEquals("the composer is free again",
                SubmissionAvailability.AVAILABLE, fx.session.getChatTarget().getAvailability());
        assertFalse("no 'Agent turn completed.' bubble",
                fx.sink.assistantMessages.toString().contains("Agent turn completed"));

        // The budget-exhausted-with-open-requirements card offers the full decision set.
        List<AgentConversationSink.ActionOption> options =
                fx.sink.cardOptions.get(fx.sink.cardOptions.size() - 1);
        List<String> ids = new ArrayList<String>();
        for (AgentConversationSink.ActionOption option : options) {
            ids.add(option.getId());
        }
        assertTrue(ids.contains("continue"));
        assertTrue(ids.contains("sources"));
        assertTrue(ids.contains("refine"));
        assertTrue(ids.contains("limit"));
        assertTrue(ids.contains("end"));
    }

    @Test
    public void continueActionResubmitsTheStoredQuestionWithoutRetyping() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        int promptsBefore = fx.backend.prompts.size();
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                .activity("research-run-p1", null, "", "")
                .runOutcome(new ResearchRunOutcomeInfo("p1", "TOOL_BUDGET_EXHAUSTED", 10, 7, 1, 3, 2,
                        true, "INSUFFICIENT_HOST_DIVERSITY", "CONTINUE_RESEARCH")));

        fx.press("continue");
        assertEquals("the stored question is submitted again", promptsBefore + 1,
                fx.backend.prompts.size());
        assertEquals("investigate pf4j", fx.backend.prompts.get(fx.backend.prompts.size() - 1));
        assertEquals("a new turn is in flight", SubmissionAvailability.BUSY,
                fx.session.getChatTarget().getAvailability());
    }

    @Test
    public void limitationActionRecordsTheUnmetRequirementVisibly() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.RUN_OUTCOME)
                .activity("research-run-p1", null, "", "")
                .runOutcome(new ResearchRunOutcomeInfo("p1", "TOOL_BUDGET_EXHAUSTED", 10, 7, 1, 3, 2,
                        true, "INSUFFICIENT_HOST_DIVERSITY", "CONTINUE_RESEARCH")));

        fx.press("limit");
        String lastMessage = fx.sink.assistantMessages.get(fx.sink.assistantMessages.size() - 1);
        assertTrue("the limitation is stated visibly", lastMessage.contains("Limitation recorded"));
        String notes = fx.resources.getArtifactStore().read("research-notes").getMarkdown();
        assertTrue("the limitation is persisted in the notes artifact",
                notes.contains("Limitation recorded"));
        assertEquals("the state moved on towards the evidence review",
                ResearchStateIds.EVIDENCE, fx.resources.currentState().getPhaseId());
    }

    @Test
    public void productiveSessionExposesTheProductiveSourcesAndArtifactsNotTheDemoSeeds() {
        // The user-reported bug: the Sources tab kept showing the clickdummy seed records although the
        // productive run had accepted real sources — the session handed out its session-local demo
        // repository/store instead of the resources' ones (where source_accept and the notes land).
        Fx fx = new Fx();
        org.junit.Assert.assertSame("the sources view must read where source_accept writes",
                fx.resources.getRepository(), fx.session.getSourceRepository());
        org.junit.Assert.assertSame("the artifact tabs must read where the agent writes",
                fx.resources.getArtifactStore(), fx.session.getArtifactStore());
        assertTrue("no demo seed records in a fresh productive session",
                fx.session.getSourceRepository()
                        .find(com.aresstack.askai.research.sources.SourceQuery.all()).isEmpty());
    }

    @Test
    public void invisibleTerminalWithoutOutcomeStillFreesTheComposer() {
        Fx fx = new Fx();
        fx.reachRunningResearch();
        int bubblesBefore = fx.sink.assistantMessages.size();
        fx.event(ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED).text(""));
        assertEquals("no bubble for the technical terminal", bubblesBefore,
                fx.sink.assistantMessages.size());
        assertEquals(SubmissionAvailability.AVAILABLE, fx.session.getChatTarget().getAvailability());
    }

    private static int countOf(List<String> list, String value) {
        int count = 0;
        for (String item : list) {
            if (value.equals(item)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ minimal host with a sink

    private static final class SinkHost implements AgentHostContext {
        private final AgentConversationSink sink;

        SinkHost(AgentConversationSink sink) {
            this.sink = sink;
        }

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

    private static AgentConversationSink.ActionOption option(String id, String label) {
        return new AgentConversationSink.ActionOption(id, label);
    }
}
