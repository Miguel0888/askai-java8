package com.aresstack.askai.research.agent.narration;

import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.backend.ManualResearchScheduler;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The narration lifecycle is proven DETERMINISTICALLY, without any model: bubble now, exactly one final
 * text later, identical sequence for answer/timeout/failure, silent close for stale results. These are the
 * races that would be flaky against a real LLM — here they are scripted.
 */
public class NarrationCoordinatorTest {

    // ------------------------------------------------------------------ deterministic doubles

    private static final class InlineUi implements UiExecutor {
        public boolean isUiThread() {
            return true;
        }

        public void execute(Runnable task) {
            task.run();
        }

        public void assertUiThread() {
        }
    }

    private static final class RecordingSink implements AgentConversationSink {
        final List<String> thinkingStarted = new ArrayList<String>();
        final List<String> thinkingFinished = new ArrayList<String>();
        final List<String> assistant = new ArrayList<String>();

        public void appendUserMessage(String messageId, String markdown) {
        }

        public void appendAssistantMessage(String messageId, String markdown) {
            assistant.add(markdown);
        }

        public void startThinking(String activityId, String title) {
            thinkingStarted.add(activityId);
        }

        public void updateThinking(String activityId, String text) {
        }

        public void finishThinking(String activityId, String summary) {
            thinkingFinished.add(activityId);
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

    /** Scripted async narrator: the test decides WHEN and HOW each request terminates. */
    private static final class ScriptedNarrator implements AsyncNarrator {
        final List<Callback> callbacks = new ArrayList<Callback>();
        int cancels;

        public NarrationHandle narrate(NarrationRequest request, Callback callback) {
            callbacks.add(callback);
            return new NarrationHandle() {
                public void cancel() {
                    cancels++;
                }
            };
        }
    }

    private static final class Presented implements NarrationCoordinator.Presenter {
        final List<String> texts = new ArrayList<String>();

        public void present(String text) {
            texts.add(text);
        }
    }

    private static final class Fx {
        final RecordingSink sink = new RecordingSink();
        final ScriptedNarrator narrator = new ScriptedNarrator();
        final ManualResearchScheduler scheduler = new ManualResearchScheduler();
        final Presented presented = new Presented();
        final NarrationCoordinator coordinator =
                new NarrationCoordinator(narrator, sink, new InlineUi(), scheduler, 5000L);

        void narrate(String id) {
            coordinator.narrate(new NarrationRequest(id, "thinking …", "FALLBACK"), presented);
        }
    }

    // ------------------------------------------------------------------ the races

    @Test
    public void anAnswerClosesTheBubbleAndPresentsExactlyOnce() {
        Fx fx = new Fx();
        fx.narrate("n1");
        assertEquals("bubble opens in the same tick", 1, fx.sink.thinkingStarted.size());
        fx.narrator.callbacks.get(0).onNarration("warm text");
        assertEquals("bubble closed", 1, fx.sink.thinkingFinished.size());
        assertEquals(java.util.Collections.singletonList("warm text"), fx.presented.texts);
        assertFalse("the timeout was cancelled", fx.scheduler.hasPending());
        // A duplicate terminal (late timeout thread, double callback) changes nothing.
        fx.narrator.callbacks.get(0).onNarration("late duplicate");
        assertEquals(1, fx.presented.texts.size());
    }

    @Test
    public void aTimeoutPresentsTheFallbackAndDropsTheLateAnswer() {
        Fx fx = new Fx();
        fx.narrate("n1");
        assertTrue("the timeout is armed", fx.scheduler.hasPending());
        fx.scheduler.runUntilIdle(); // the timeout fires first
        assertEquals(java.util.Collections.singletonList("FALLBACK"), fx.presented.texts);
        assertEquals("bubble closed exactly once", 1, fx.sink.thinkingFinished.size());
        assertEquals("the in-flight generation was cancelled", 1, fx.narrator.cancels);
        fx.narrator.callbacks.get(0).onNarration("too late");
        assertEquals("the late answer is dropped", 1, fx.presented.texts.size());
        assertEquals(1, fx.sink.thinkingFinished.size());
    }

    @Test
    public void invalidateClosesTheBubbleSilentlyAndFreesTheNarrator() {
        Fx fx = new Fx();
        fx.narrate("n1");
        fx.coordinator.invalidate(); // e.g. the user consumed the decision card first
        assertEquals("bubble closed silently", 1, fx.sink.thinkingFinished.size());
        assertTrue("nothing is presented", fx.presented.texts.isEmpty());
        assertEquals("the generator was cancelled (local model freed)", 1, fx.narrator.cancels);
        fx.narrator.callbacks.get(0).onNarration("answer after cancel");
        assertTrue("the answer after invalidate never reaches the chat", fx.presented.texts.isEmpty());
    }

    @Test
    public void aFailureFallsBackWithTheIdenticalLifecycle() {
        Fx fx = new Fx();
        fx.narrate("n1");
        fx.narrator.callbacks.get(0).onFailure("model unreachable");
        assertEquals(java.util.Collections.singletonList("FALLBACK"), fx.presented.texts);
        assertEquals(1, fx.sink.thinkingFinished.size());
    }

    @Test
    public void anEmptyAnswerIsTreatedAsFailure() {
        Fx fx = new Fx();
        fx.narrate("n1");
        fx.narrator.callbacks.get(0).onNarration("   ");
        assertEquals(java.util.Collections.singletonList("FALLBACK"), fx.presented.texts);
    }

    @Test
    public void twoNarrationsNeverCrossTheirTerminals() {
        Fx fx = new Fx();
        fx.narrate("n1");
        fx.narrate("n2");
        fx.narrator.callbacks.get(1).onNarration("second");
        fx.narrator.callbacks.get(0).onNarration("first");
        assertEquals(java.util.Arrays.asList("second", "first"), fx.presented.texts);
        assertEquals(2, fx.sink.thinkingFinished.size());
    }

    @Test
    public void anInvalidNarrationGetsOneRetryThenTheFallback() {
        RecordingSink sink = new RecordingSink();
        ScriptedNarrator narrator = new ScriptedNarrator();
        Presented presented = new Presented();
        NarrationCoordinator coordinator = new NarrationCoordinator(narrator, new NarrationValidator(),
                sink, new InlineUi(), new ManualResearchScheduler(), 5000L);
        NarrationPayload payload = new NarrationPayload("outline ready",
                java.util.Collections.singletonList("fact"),
                java.util.Collections.singletonMap("sources", "7"), "approve", 4, null);
        coordinator.narrate(new NarrationRequest("n1", "thinking …", "FALLBACK", payload), presented);

        narrator.callbacks.get(0).onNarration("Great progress, no numbers here!"); // invalid: 7 missing
        assertEquals("an invalid answer triggers a retry, not a message", 2, narrator.callbacks.size());
        assertTrue(presented.texts.isEmpty());

        narrator.callbacks.get(1).onNarration("Still no numbers, sorry."); // invalid again
        assertEquals("after the single retry the fallback is presented",
                java.util.Collections.singletonList("FALLBACK"), presented.texts);
        assertEquals("one bubble, closed once", 1, sink.thinkingFinished.size());
    }

    @Test
    public void aValidRetryAnswerIsPresented() {
        ScriptedNarrator narrator = new ScriptedNarrator();
        Presented presented = new Presented();
        NarrationCoordinator coordinator = new NarrationCoordinator(narrator, new NarrationValidator(),
                new RecordingSink(), new InlineUi(), new ManualResearchScheduler(), 5000L);
        NarrationPayload payload = new NarrationPayload("outline ready", null,
                java.util.Collections.singletonMap("sources", "7"), "approve", 4, null);
        coordinator.narrate(new NarrationRequest("n1", "thinking …", "FALLBACK", payload), presented);

        narrator.callbacks.get(0).onNarration("No facts, just vibes!");
        narrator.callbacks.get(1).onNarration("All 7 sources are in. Shall we review them?");
        assertEquals(java.util.Collections.singletonList("All 7 sources are in. Shall we review them?"),
                presented.texts);
    }

    @Test
    public void withoutAnAsyncNarratorTheFallbackIsPresentedDirectlyWithoutABubble() {
        RecordingSink sink = new RecordingSink();
        Presented presented = new Presented();
        NarrationCoordinator off = new NarrationCoordinator(null, sink, new InlineUi(),
                new ManualResearchScheduler(), 5000L);
        off.narrate(new NarrationRequest("n1", "thinking …", "FALLBACK"), presented);
        assertEquals(java.util.Collections.singletonList("FALLBACK"), presented.texts);
        assertTrue("no thought bubble when narration is off", sink.thinkingStarted.isEmpty());
    }
}
