package com.aresstack.askai.research.agent.narration;

import com.aresstack.askai.agent.model.inference.AgentInferencePort;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The LLM narrator renders the payload as labelled blocks and maps port callbacks 1:1 to the seam. */
public class LlmNarratorTest {

    private static final class ScriptedPort implements AgentInferencePort {
        final List<InferenceRequest> requests = new ArrayList<InferenceRequest>();
        Listener listener;
        int cancels;

        public Cancellable generate(InferenceRequest request, Listener listener) {
            requests.add(request);
            this.listener = listener;
            return new Cancellable() {
                public void cancel() {
                    cancels++;
                }
            };
        }
    }

    private static final class Recorded implements AsyncNarrator.Callback {
        final List<String> narrations = new ArrayList<String>();
        final List<String> failures = new ArrayList<String>();
        final StringBuilder thinking = new StringBuilder();

        public void onThinking(String delta) {
            thinking.append(delta);
        }

        public void onNarration(String text) {
            narrations.add(text);
        }

        public void onFailure(String reason) {
            failures.add(reason);
        }
    }

    @After
    public void resetLanguage() {
        com.aresstack.askai.research.agent.ResearchPlaybook.setLanguage("en"); // no test bleed
    }

    @Test
    public void thePayloadIsRenderedAsLabelledBlocksNotAsAParaphraseOrder() {
        ScriptedPort port = new ScriptedPort();
        NarrationPayload payload = new NarrationPayload("outline ready",
                java.util.Collections.singletonList("After approval the research starts automatically."),
                java.util.Collections.singletonMap("sources", "7"),
                "approve the outline", 4,
                java.util.Collections.singletonList("Got it — you want to research…"));
        new LlmNarrator(port).narrate(
                new NarrationRequest("n1", "thinking …", "STATIC REFERENCE", payload), new Recorded());

        AgentInferencePort.InferenceRequest sent = port.requests.get(0);
        assertTrue("rules live in the system prompt",
                sent.getSystemPrompt().contains("Non-negotiable rules"));
        String user = sent.getUserPrompt();
        assertTrue(user.contains("SITUATION: outline ready"));
        assertTrue(user.contains("MUST CONVEY:"));
        assertTrue(user.contains("- sources: 7"));
        assertTrue(user.contains("DECISION: approve the outline"));
        assertTrue(user.contains("MAX SENTENCES: 4"));
        assertTrue(user.contains("RECENTLY SAID:"));
        assertTrue("the fallback is the content reference", user.contains("STATIC REFERENCE"));
    }

    @Test
    public void aRetryCarriesTheViolationToFix() {
        ScriptedPort port = new ScriptedPort();
        NarrationRequest request = new NarrationRequest("n1", "t", "REF",
                new NarrationPayload("s", null, null, null, 4, null));
        new LlmNarrator(port).narrate(request.withRetryHint("missing verbatim data: \"7\""),
                new Recorded());
        assertTrue(port.requests.get(0).getUserPrompt().contains("missing verbatim data"));
    }

    @Test
    public void portCallbacksMapToTheNarrationSeam() {
        ScriptedPort port = new ScriptedPort();
        Recorded recorded = new Recorded();
        NarrationHandle handle = new LlmNarrator(port)
                .narrate(new NarrationRequest("n1", "t", "REF"), recorded);
        port.listener.onThinkingDelta("hmm ");
        port.listener.onCompleted("  Warm text.  ");
        assertEquals("hmm ", recorded.thinking.toString());
        assertEquals(java.util.Collections.singletonList("Warm text."), recorded.narrations);
        handle.cancel();
        assertEquals("cancel reaches the port", 1, port.cancels);
    }

    @Test
    public void aGermanSessionGetsAGermanPrompt() {
        com.aresstack.askai.research.agent.ResearchPlaybook.setLanguage("de");
        ScriptedPort port = new ScriptedPort();
        new LlmNarrator(port).narrate(new NarrationRequest("n1", "t", "REF",
                new NarrationPayload("s", null, null, null, 4, null)), new Recorded());
        assertTrue(port.requests.get(0).getSystemPrompt().contains("Nicht verhandelbare Regeln"));
        assertTrue(port.requests.get(0).getUserPrompt().contains("MAXIMALE SÄTZE"));
    }
}
