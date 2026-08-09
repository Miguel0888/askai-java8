package com.aresstack.askai.research.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * chat_history's data model: messages are phase-attributed; the default rendering compresses finished
 * phases to their outcome summary + message count and prints the current phase in full; raw=true prints
 * everything.
 */
public class ResearchTranscriptTest {

    @Test
    public void defaultRenderingSummarizesFinishedPhasesAndDetailsTheCurrentOne() {
        ResearchTranscript transcript = new ResearchTranscript();
        transcript.record("scoping", "user", "Ich möchte zu Wearables recherchieren.");
        transcript.record("scoping", "assistant", "Verstanden — ich fasse den Umfang zusammen.");
        transcript.recordOutcome("scoping", "Scope confirmed: wearables in healthcare");
        transcript.record("research", "info", "Websuche: wearables sensors");
        transcript.record("research", "assistant", "8 Quellen übernommen.");

        String rendered = transcript.describe("research", false);
        assertTrue("finished phase appears as its summary",
                rendered.contains("summary: Scope confirmed: wearables in healthcare [2 messages]"));
        assertFalse("finished-phase details are compressed away",
                rendered.contains("ich fasse den Umfang zusammen"));
        assertTrue("the current phase is marked", rendered.contains("== phase research (current)"));
        assertTrue("the current phase is detailed", rendered.contains("[assistant] 8 Quellen übernommen."));
    }

    @Test
    public void rawRenderingShowsEveryEntryOfEveryPhase() {
        ResearchTranscript transcript = new ResearchTranscript();
        transcript.record("scoping", "user", "Frage A");
        transcript.recordOutcome("scoping", "done");
        transcript.record("research", "assistant", "Antwort B");

        String raw = transcript.describe("research", true);
        assertTrue(raw.contains("[user] Frage A"));
        assertTrue("the outcome still shows in raw mode", raw.contains("outcome: done"));
        assertTrue(raw.contains("[assistant] Antwort B"));
    }

    @Test
    public void anEmptyRecordSaysSoInsteadOfReturningNothing() {
        assertTrue(new ResearchTranscript().describe("scoping", false).contains("no messages recorded"));
    }
}
