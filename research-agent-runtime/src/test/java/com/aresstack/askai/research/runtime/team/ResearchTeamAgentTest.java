package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The model-backed conversation engine, driven entirely by a scripted fake model (no HTTP, no real model). */
public class ResearchTeamAgentTest {

    private static TeamAgentStateView scoping() {
        return new TeamAgentStateView("scoping", "new", Arrays.asList("START", "SUBMIT_SCOPE"));
    }

    @Test
    public void greetingAsksTheModelAndRecordsExactlyOneAssistantTurn() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Hi! What would you like to find out?\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.greet(scoping());
        assertTrue(agent.hasGreeted());
        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        assertEquals("Hi! What would you like to find out?", result.getTurn().getAssistantMessage());
        // The model saw the system prompt + the greeting bootstrap, and nothing was invented.
        List<ChatMessage> firstCall = model.calls.get(0);
        assertEquals(ChatMessage.Role.SYSTEM, firstCall.get(0).getRole());
        assertTrue(firstCall.get(0).getContent().contains("research TeamAgent"));
        assertTrue(lastUser(firstCall).contains("just started"));
    }

    @Test
    public void aProposedCommandInTheAllowedSetIsValidatedThroughOthersAreDropped() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Let me summarize.\",\"proposedCommand\":\"SUBMIT_SCOPE\"}");
        model.enqueueOk("{\"assistantMessage\":\"Starting research now.\","
                + "\"proposedCommand\":\"START_RESEARCH\"}"); // NOT in the allowed set below
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult allowed = agent.respond("electric cars", scoping());
        assertEquals("SUBMIT_SCOPE", allowed.getValidatedCommand());

        TeamAgentResult illegal = agent.respond("go", scoping());
        assertEquals("the model proposed it, but the host does not allow it here",
                null, illegal.getValidatedCommand());
        // The assistantMessage still comes through — only the illegal command is withheld.
        assertEquals("Starting research now.", illegal.getTurn().getAssistantMessage());
    }

    @Test
    public void scopeUpdatesAreFoldedAndSentBackAsContextOnTheNextTurn() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Got it.\",\"scope\":{\"question\":\"How do EVs age?\","
                + "\"aspects\":[\"battery\",\"cost\"]}}");
        model.enqueueOk("{\"assistantMessage\":\"Anything else?\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        agent.respond("EV ageing", scoping());
        assertEquals("How do EVs age?", agent.getQuestion());
        assertEquals(Arrays.asList("battery", "cost"), agent.getAspects());

        agent.respond("no", scoping());
        // The SECOND call's state-context system message carries the confirmed scope back to the model.
        String context = model.calls.get(1).get(1).getContent();
        assertTrue(context, context.contains("How do EVs age?"));
        assertTrue(context, context.contains("battery"));
    }

    @Test
    public void aModelTransportFailureIsHonestlyUnavailableAndNeverFabricatesATurn() {
        FakeModel model = new FakeModel();
        model.enqueue(MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "down"));
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.greet(scoping());
        assertEquals(TeamAgentResult.Status.MODEL_UNAVAILABLE, result.getStatus());
        assertNull(result.getTurn());
        assertFalse("a failed greeting can be retried", agent.hasGreeted());
    }

    @Test
    public void anUnparseableAnswerGetsOneRepairThenAnHonestError() {
        FakeModel model = new FakeModel();
        model.enqueueOk("I cannot decide.");                 // not JSON
        model.enqueueOk("still no json object here");         // repair also fails
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("hello", scoping());
        assertEquals(TeamAgentResult.Status.UNUSABLE_ANSWER, result.getStatus());
        assertEquals("exactly one bootstrap call + one repair call", 2, model.calls.size());
        // The repair call included the previous raw answer + the nudge.
        assertTrue(lastUser(model.calls.get(1)).contains("valid JSON"));
    }

    @Test
    public void aRepairThatSucceedsYieldsTheParsedTurn() {
        FakeModel model = new FakeModel();
        model.enqueueOk("Sorry, here you go:");                                // unparseable
        model.enqueueOk("{\"assistantMessage\":\"Recovered answer.\"}");       // repair OK
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("hi", scoping());
        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        assertEquals("Recovered answer.", result.getTurn().getAssistantMessage());
    }

    // ------------------------------------------------------------------ fake model

    private static String lastUser(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == ChatMessage.Role.USER) {
                return messages.get(i).getContent();
            }
        }
        return "";
    }

    private static final class FakeModel implements MainModelChat {
        final Deque<MainModelChatResult> scripted = new ArrayDeque<MainModelChatResult>();
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();

        /** Enqueue an OK model call whose raw text is {@code rawAnswer} (what the parser then processes). */
        void enqueueOk(String rawAnswer) {
            scripted.add(MainModelChatResult.ok(rawAnswer));
        }

        void enqueue(MainModelChatResult result) {
            scripted.add(result);
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens) {
            calls.add(new ArrayList<ChatMessage>(messages));
            if (scripted.isEmpty()) {
                return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "no script");
            }
            return scripted.removeFirst();
        }

        public String modelName() {
            return "gemma4:e2b";
        }
    }
}
