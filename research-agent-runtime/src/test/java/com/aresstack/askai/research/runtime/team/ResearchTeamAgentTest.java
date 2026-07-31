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
    public void greetingIsIdempotentAndNeverReAsksTheModelOrDoubleGreets() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Hi! What would you like to find out?\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult first = agent.greet(scoping());
        TeamAgentResult second = agent.greet(scoping());

        assertEquals("a second greet returns the cached greeting", first, second);
        assertEquals("the model is asked to greet exactly once", 1, model.calls.size());
    }

    @Test
    public void aProposedCommandInTheAllowedSetIsValidatedAndSurfaced() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Let me summarize.\",\"proposedCommand\":\"SUBMIT_SCOPE\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult allowed = agent.respond("electric cars", scoping());
        assertEquals(TeamAgentResult.Status.OK, allowed.getStatus());
        assertEquals("SUBMIT_SCOPE", allowed.getValidatedCommand());
        assertEquals("Let me summarize.", allowed.getTurn().getAssistantMessage());
    }

    @Test
    public void anIllegalCommandGetsOneRepairThenIsRejectedWithoutSurfacingItsMisleadingMessage() {
        FakeModel model = new FakeModel();
        // START_RESEARCH is NOT in the allowed set {START, SUBMIT_SCOPE}; the message would be a lie if shown.
        model.enqueueOk("{\"assistantMessage\":\"Starting research now.\","
                + "\"proposedCommand\":\"START_RESEARCH\"}");
        // The bounded repair still insists on the illegal command.
        model.enqueueOk("{\"assistantMessage\":\"Kicking it off anyway.\","
                + "\"proposedCommand\":\"START_RESEARCH\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("go", scoping());

        assertEquals(TeamAgentResult.Status.COMMAND_REJECTED, result.getStatus());
        assertEquals("START_RESEARCH", result.getDetail());
        assertNull("no command may leak through", result.getValidatedCommand());
        assertNull("the misleading assistant message must be withheld", result.getTurn());
        assertEquals("original call + exactly one bounded repair", 2, model.calls.size());
        // The repair nudge named the illegal command and the legal set.
        String nudge = lastUser(model.calls.get(1));
        assertTrue(nudge, nudge.contains("START_RESEARCH"));
        assertTrue(nudge, nudge.contains("SUBMIT_SCOPE"));
    }

    @Test
    public void anIllegalCommandThatTheRepairCorrectsYieldsAnOkTurn() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Starting research now.\","
                + "\"proposedCommand\":\"START_RESEARCH\"}");
        // The repair drops the illegal command and answers cleanly.
        model.enqueueOk("{\"assistantMessage\":\"Let me first confirm the scope.\","
                + "\"proposedCommand\":\"SUBMIT_SCOPE\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("go", scoping());

        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        assertEquals("SUBMIT_SCOPE", result.getValidatedCommand());
        assertEquals("Let me first confirm the scope.", result.getTurn().getAssistantMessage());
    }

    @Test
    public void modelProposedScopeIsNeverTreatedAsConfirmedButIsEchoedAsProposed() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Got it.\",\"scope\":{\"question\":\"How do EVs age?\","
                + "\"aspects\":[\"battery\",\"cost\"]}}");
        model.enqueueOk("{\"assistantMessage\":\"Anything else?\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        agent.respond("EV ageing", scoping());
        // The model's scope lands in the PROPOSED slot, and stays out of the CONFIRMED one.
        assertEquals("How do EVs age?", agent.getProposedQuestion());
        assertEquals(Arrays.asList("battery", "cost"), agent.getProposedAspects());
        assertEquals("the model may not confirm its own scope", "", agent.getConfirmedQuestion());
        assertTrue(agent.getConfirmedAspects().isEmpty());

        agent.respond("no", scoping());
        // The SECOND call's state-context carries the proposal back, explicitly labelled as NOT yet confirmed.
        String context = model.calls.get(1).get(1).getContent();
        assertTrue(context, context.contains("How do EVs age?"));
        assertTrue(context, context.contains("battery"));
        assertTrue(context, context.contains("awaiting"));
        assertFalse("the proposal must not be reflected back as host-approved",
                context.contains("host-approved"));
    }

    @Test
    public void onlyTheHostCanPromoteScopeToConfirmed() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Understood.\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        agent.applyConfirmedScope("How do EVs age?", Arrays.asList("battery", "cost"));
        agent.respond("continue", scoping());

        assertEquals("How do EVs age?", agent.getConfirmedQuestion());
        assertEquals(Arrays.asList("battery", "cost"), agent.getConfirmedAspects());
        String context = model.calls.get(0).get(1).getContent();
        assertTrue(context, context.contains("host-approved"));
        assertTrue(context, context.contains("How do EVs age?"));
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
    public void retryingAPendingTurnDoesNotDuplicateTheUserMessage() {
        FakeModel model = new FakeModel();
        model.enqueue(MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "down"));
        model.enqueueOk("{\"assistantMessage\":\"Recovered.\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult failed = agent.respond("electric cars", scoping());
        assertEquals(TeamAgentResult.Status.MODEL_UNAVAILABLE, failed.getStatus());
        assertTrue("the user turn stays pending after a failure", agent.hasPendingTurn());

        TeamAgentResult recovered = agent.retryPendingTurn(scoping());
        assertEquals(TeamAgentResult.Status.OK, recovered.getStatus());
        assertFalse("an OK turn clears the pending turn", agent.hasPendingTurn());

        // The retry re-sent the SAME single user message — the history never doubled it.
        List<ChatMessage> retryCall = model.calls.get(1);
        assertEquals("exactly one 'electric cars' user turn reached the model", 1,
                countUserContent(retryCall, "electric cars"));
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

    private static int countUserContent(List<ChatMessage> messages, String needle) {
        int count = 0;
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.USER && message.getContent().contains(needle)) {
                count++;
            }
        }
        return count;
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
