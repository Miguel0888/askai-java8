package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.inference.InferenceConfigurationDocument;
import com.aresstack.askai.research.runtime.inference.InferenceConfigurationLoader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The model-backed TeamAgent driven end to end over REAL HTTP against the mock {@code /api/chat}, through the
 * real inference-config loader and {@link HttpMainModelChatClient}. Proves the descriptor's model reaches the
 * endpoint, the greeting and a scope proposal come from the model, prior turns are carried in the history, and
 * a disallowed legacy command is silently ignored (the model no longer owns the workflow).
 */
public class MockMainModelPassthroughTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    // A non-scoping phase drives the GENERIC contract (proposedCommand + scope). The scoping phase now has its
    // own ScopingAssistantOutput contract, tested separately; this HTTP test pins the generic command path.
    private static TeamAgentStateView genericNew() {
        return new TeamAgentStateView("outline", "new", Arrays.asList("START"));
    }

    private static TeamAgentStateView genericRunning() {
        return new TeamAgentStateView("outline", "running", Arrays.asList("SUBMIT_SCOPE", "CANCEL"));
    }

    @Test
    public void teamAgentGreetsProposesScopeAndIgnoresDisallowedCommandsOverRealHttp() throws Exception {
        MockMainModelServer mock = new MockMainModelServer();
        try {
            mock.enqueueMessage("Hi! What would you like to research?");
            mock.enqueueScopeProposal("Great, let me confirm the scope.", "SUBMIT_SCOPE",
                    "How does pf4j isolate plugins?", Arrays.asList("class isolation", "versioning"));

            File cfg = mock.writeInferenceConfig(folder.newFile("inference-config.json"), "gemma4:e2b");
            InferenceConfigurationDocument document = InferenceConfigurationLoader.load(cfg.getAbsolutePath());
            assertEquals("gemma4:e2b", document.getModel());
            ResearchTeamAgent agent = new ResearchTeamAgent(new HttpMainModelChatClient(document.descriptor));

            TeamAgentResult greeting = agent.greet(genericNew());
            assertEquals(TeamAgentResult.Status.OK, greeting.getStatus());
            assertTrue(greeting.getTurn().getAssistantMessage().contains("What would you like to research"));

            TeamAgentResult reply = agent.respond("pf4j isolation", genericRunning());
            assertEquals(TeamAgentResult.Status.OK, reply.getStatus());
            assertEquals("SUBMIT_SCOPE", reply.getValidatedCommand());
            assertEquals("How does pf4j isolate plugins?", agent.getProposedQuestion());

            // The descriptor's model actually reached /api/chat, and the greeting was carried in the second
            // turn's message history (a real conversation, not a fresh one).
            assertTrue("mock received model=gemma4:e2b", mock.sawModel("gemma4:e2b"));
            assertTrue("the greeting is carried in the follow-up turn's history",
                    mock.requests().get(1).historyContains("What would you like to research"));

            // A legacy command the host does not allow is silently IGNORED now (the assistant no longer
            // owns the workflow): the friendly message is still shown, the command just does not surface,
            // and there is no policing repair round.
            mock.enqueueScopeProposal("Happy to keep helping.", "START_RESEARCH", "q", Arrays.asList("a"));
            int callsBefore = mock.requests().size();
            TeamAgentResult ignored = agent.respond("go", genericRunning());
            assertEquals(TeamAgentResult.Status.OK, ignored.getStatus());
            assertNull("a disallowed command does not surface", ignored.getValidatedCommand());
            assertEquals("Happy to keep helping.", ignored.getTurn().getAssistantMessage());
            assertEquals("no policing repair — exactly one more model call",
                    callsBefore + 1, mock.requests().size());
        } finally {
            mock.close();
        }
    }
}
