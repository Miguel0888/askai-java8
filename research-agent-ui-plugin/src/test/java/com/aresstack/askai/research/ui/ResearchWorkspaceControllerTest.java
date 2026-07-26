package com.aresstack.askai.research.ui;

import com.aresstack.askai.plugin.api.service.ConversationSurface;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.backend.FakeResearchSessionBackend;
import com.aresstack.askai.research.backend.ManualResearchScheduler;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchClock;
import com.aresstack.askai.research.backend.ResearchIdGenerator;
import com.aresstack.askai.research.state.ResearchPhase;
import com.aresstack.askai.research.state.ResearchRunState;

import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The controller owns no state machine: it delegates to the backend and applies backend events to a small
 * view-model via the {@link UiExecutor}, dropping late/duplicate/foreign events. Section filtering and gated
 * outline edits stay local to the clickdummy.
 */
public class ResearchWorkspaceControllerTest {

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

    private static final class Harness {
        final ManualResearchScheduler scheduler = new ManualResearchScheduler();
        final FakeResearchSessionBackend backend =
                new FakeResearchSessionBackend(scheduler, fixedClock(), sequentialIds(), 10L);
        final CountingUiExecutor uiExecutor = new CountingUiExecutor();
        final RecordingConversation conversation = new RecordingConversation();
        final ResearchWorkspaceController controller =
                new ResearchWorkspaceController(backend, uiExecutor, conversation, "s1", "p1");
    }

    @Test
    public void activeSectionFiltersSourcesAndFindings() {
        Harness h = new Harness();
        assertEquals(3, h.controller.sourcesForActiveSection().size());
        assertEquals(3, h.controller.findingsForActiveSection().size());

        h.controller.setActiveSection("s3");
        assertEquals(2, h.controller.sourcesForActiveSection().size());
        assertEquals(2, h.controller.findingsForActiveSection().size());

        h.controller.setActiveSection("s2");
        assertEquals(1, h.controller.sourcesForActiveSection().size());
        assertEquals(1, h.controller.findingsForActiveSection().size());
    }

    @Test
    public void outlineEditsAreGatedAndValidated() {
        Harness h = new Harness();
        assertTrue(h.controller.canEditOutline());
        long before = h.controller.getOutline().getRevision();
        assertTrue(h.controller.addSection("", "sX", "Extra"));
        assertTrue(h.controller.getOutline().getRevision() > before);
        assertFalse(h.controller.addSection("missing", "sY", "Bad"));
    }

    @Test
    public void startDrivesTheViewModelAndConversationViaTheExecutor() {
        Harness h = new Harness();
        h.controller.start();
        h.scheduler.runUntilIdle();
        // The run reached the first approval gate and every application went through the UiExecutor.
        assertEquals(ResearchPhase.OUTLINE, h.controller.phase());
        assertEquals(ResearchRunState.WAITING_FOR_USER, h.controller.runState());
        assertTrue(h.controller.hasPendingApproval());
        assertTrue(h.uiExecutor.executions > 0);
        assertTrue(h.conversation.thinkingStarts > 0);
    }

    @Test
    public void approveCurrentAdvancesPastTheGate() {
        Harness h = new Harness();
        h.controller.start();
        h.scheduler.runUntilIdle();
        assertTrue(h.controller.hasPendingApproval());
        h.controller.approveCurrent();
        h.scheduler.runUntilIdle();
        assertEquals(ResearchPhase.EVIDENCE, h.controller.phase());
        assertTrue(h.controller.hasPendingApproval());
    }

    @Test
    public void submitPromptIsTiedToTheActiveSection() {
        Harness h = new Harness();
        h.controller.start();
        h.controller.setActiveSection("s2");
        h.conversation.assistantMessages.clear();
        h.controller.submitPrompt("focus here");
        assertFalse(h.conversation.assistantMessages.isEmpty());
        String last = h.conversation.assistantMessages.get(h.conversation.assistantMessages.size() - 1);
        assertTrue(last.contains("s2"));
    }

    @Test
    public void staleAndDuplicateEventsAreIgnored() {
        Harness h = new Harness();
        h.controller.start();
        h.scheduler.runUntilIdle();
        ResearchPhase phaseNow = h.controller.phase();
        // A same-session event with a sequence number below the high-water mark must be dropped.
        ResearchBackendEvent stale = ResearchBackendEvent.builder(ResearchBackendEventType.SESSION_STATE_CHANGED)
                .state(ResearchPhase.FINALIZATION, ResearchRunState.COMPLETED)
                .envelope("stale", "s1", "p1", 0, 0, 0, null)
                .build();
        h.controller.onEvent(stale);
        h.controller.onEvent(stale); // duplicate too
        assertEquals(phaseNow, h.controller.phase());
    }

    @Test
    public void foreignSessionEventsAreIgnored() {
        Harness h = new Harness();
        h.controller.start();
        h.scheduler.runUntilIdle();
        ResearchPhase phaseNow = h.controller.phase();
        ResearchBackendEvent foreign = ResearchBackendEvent.builder(ResearchBackendEventType.SESSION_STATE_CHANGED)
                .state(ResearchPhase.FINALIZATION, ResearchRunState.COMPLETED)
                .envelope("x", "other-session", "p1", 0, 0, 999, null)
                .build();
        h.controller.onEvent(foreign);
        assertEquals(phaseNow, h.controller.phase());
    }

    @Test
    public void disposeClosesTheSessionAndStopsApplyingEvents() {
        Harness h = new Harness();
        h.controller.start();
        h.scheduler.runUntilIdle();
        h.controller.dispose();
        int applied = h.uiExecutor.executions;
        // Any queued progression is cancelled; nothing new is applied after dispose.
        h.scheduler.runUntilIdle();
        assertEquals(applied, h.uiExecutor.executions);
        assertFalse(h.controller.hasPendingApproval() && h.controller.canDispatch(
                com.aresstack.askai.research.state.ResearchCommandType.PAUSE));
    }

    // ------------------------------------------------------------------ fakes

    private static final class CountingUiExecutor implements UiExecutor {
        int executions;

        public boolean isUiThread() {
            return true;
        }

        public void execute(Runnable runnable) {
            executions++;
            runnable.run();
        }

        public void assertUiThread() {
        }
    }

    private static final class RecordingConversation implements ConversationSurface {
        int thinkingStarts;
        final List<String> userMessages = new ArrayList<String>();
        final List<String> assistantMessages = new ArrayList<String>();
        private final JPanel component = new JPanel();

        public JComponent getComponent() {
            return component;
        }

        public void addUserMessage(String messageId, String markdown) {
            userMessages.add(markdown);
        }

        public void addAssistantMessage(String messageId, String markdown) {
            assistantMessages.add(markdown);
        }

        public void startAssistantStreaming(String messageId) {
        }

        public void appendAssistantDelta(String messageId, String delta) {
        }

        public void finishAssistantStreaming(String messageId) {
        }

        public void startThinking(String activityId, String title) {
            thinkingStarts++;
        }

        public void updateThinking(String activityId, String text) {
        }

        public void finishThinking(String activityId, String summary) {
        }

        public void startToolActivity(String activityId, String title, String explanation) {
        }

        public void updateToolActivity(String activityId, String title, String explanation) {
        }

        public void markApprovalRequired(String activityId, String explanation) {
        }

        public void completeToolActivity(String activityId, String summary) {
        }

        public void failToolActivity(String activityId, String summary) {
        }

        public void cancelActivity(String activityId, String summary) {
        }

        public void clear() {
        }

        public void dispose() {
        }
    }
}
