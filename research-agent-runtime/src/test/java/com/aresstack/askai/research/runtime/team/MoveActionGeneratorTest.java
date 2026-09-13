package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The dedicated move generation strategy (move-gate ruling): the request ACTUALLY handed to
 * the model provider is inspected — a tiny schema with exactly two REQUIRED fields and no
 * competing action kinds — and its reply flows through the EXISTING action parser/validator
 * into the ordinary move funnel. No guessing: the unclear marker yields no action at all.
 */
public class MoveActionGeneratorTest {

    /** A fake provider capturing the exact structured request. */
    private static final class CapturingChat implements MainModelChat {
        List<ChatMessage> messages;
        String schemaJson;
        String reply = "{\"source\":[\"Scheduling\"],\"parent\":[\"FreeRTOS\"]}";

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens) {
            throw new AssertionError("the move generator must use the STRUCTURED call");
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature,
                                                int maxOutputTokens, String schemaJson) {
            this.messages = messages;
            this.schemaJson = schemaJson;
            return MainModelChatResult.ok(reply);
        }

        public String modelName() {
            return "fake";
        }
    }

    @Test
    public void theDeliveredRequestCarriesTheTwoFieldRequiredSchemaAndTheUsersOrder() {
        CapturingChat chat = new CapturingChat();
        ConceptAction action = MoveActionGenerator.generate(chat,
                "Verschiebe Scheduling unter FreeRTOS.",
                "revision=4\n{\"concept\":[{\"Betriebssysteme\":[]}]}");

        Object parsed = com.aresstack.askai.agent.model.reranker.MiniJson.parse(chat.schemaJson);
        Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) parsed).get("properties");
        assertEquals("EXACTLY two fields — no competing action kinds", 2, properties.size());
        assertTrue(properties.containsKey("source"));
        assertTrue(properties.containsKey("parent"));
        assertEquals("both fields REQUIRED", Arrays.asList("source", "parent"),
                ((Map<?, ?>) parsed).get("required"));
        assertTrue("the user's exact German order travels verbatim",
                chat.messages.get(1).getContent()
                        .contains("Verschiebe Scheduling unter FreeRTOS."));
        assertTrue("the current tree grounds the name copying",
                chat.messages.get(1).getContent().contains("Betriebssysteme"));

        // The reply became the EXISTING internal operation — same parser, same validator.
        assertEquals(ConceptAction.Type.MOVE, action.getType());
        assertEquals(Collections.singletonList("Scheduling"), action.getPath());
        assertEquals(Collections.singletonList("FreeRTOS"), action.getParent());
    }

    @Test
    public void unclearMarkerBrokenRepliesAndTransportFailuresYieldNoActionEver() {
        assertNull("the honest no-guess escape",
                MoveActionGenerator.parseReply("{\"source\":[\"?\"],\"parent\":[]}"));
        assertNull(MoveActionGenerator.parseReply("not json at all"));
        assertNull("an empty source fails the EXISTING validator",
                MoveActionGenerator.parseReply("{\"source\":[],\"parent\":[\"X\"]}"));

        CapturingChat chat = new CapturingChat();
        chat.reply = "{\"source\":[\"?\"],\"parent\":[]}";
        assertNull(MoveActionGenerator.generate(chat, "verschiebe irgendwas", ""));
    }

    @Test
    public void aRootTargetParsesAsTheEmptyParent() {
        ConceptAction action = MoveActionGenerator.parseReply(
                "{\"source\":[\"Scheduling\"],\"parent\":[]}");
        assertEquals(ConceptAction.Type.MOVE, action.getType());
        assertTrue(action.getParent().isEmpty());
    }
}
