package com.aresstack.askai.research.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Gate 6 contract: while a concept conflict is pending, the host owns the next unambiguous
 * yes/no. Only SHORT, listed answers are decisions; everything else stays conversation for the
 * model — a wrong guess here would mutate the concept against the user's intent.
 */
public class ConflictAnswerInterpreterTest {

    @Test
    public void shortUnambiguousAnswersAreDecisions() {
        assertEquals(Boolean.TRUE, ConflictAnswerInterpreter.interpret("Ja."));
        assertEquals(Boolean.TRUE, ConflictAnswerInterpreter.interpret("  ja bitte!  "));
        assertEquals(Boolean.TRUE, ConflictAnswerInterpreter.interpret("Entfernen"));
        assertEquals(Boolean.TRUE, ConflictAnswerInterpreter.interpret("yes"));
        assertEquals(Boolean.FALSE, ConflictAnswerInterpreter.interpret("Nein, danke."));
        assertEquals(Boolean.FALSE, ConflictAnswerInterpreter.interpret("stehen lassen"));
        assertEquals(Boolean.FALSE, ConflictAnswerInterpreter.interpret("lasse es so"));
        assertEquals(Boolean.FALSE, ConflictAnswerInterpreter.interpret("keep it"));
    }

    @Test
    public void anythingElseStaysConversationForTheModel() {
        assertNull("a new instruction is not a decision",
                ConflictAnswerInterpreter.interpret("Ja, aber füge vorher noch Arduino hinzu"));
        assertNull("long answers are conversation", ConflictAnswerInterpreter.interpret(
                "Ich bin mir nicht sicher, was würdest du denn empfehlen an dieser Stelle?"));
        assertNull(ConflictAnswerInterpreter.interpret("Was passiert dann mit den Unterpunkten?"));
        assertNull(ConflictAnswerInterpreter.interpret(""));
        assertNull(ConflictAnswerInterpreter.interpret(null));
    }
}
