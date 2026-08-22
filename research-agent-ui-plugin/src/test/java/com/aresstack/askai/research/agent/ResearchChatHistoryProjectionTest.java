package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.service.ChatMessageSnapshot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code chat_history} projects the host's PERSISTED conversation (one truth for the text) through the
 * research phase journal (one truth for the attribution). The previous in-memory transcript started empty at
 * every session start, so a restored chat reported "no messages recorded" while the UI showed a full
 * conversation — pinned here so that cannot come back.
 */
public class ResearchChatHistoryProjectionTest {

    private static ChatMessageSnapshot message(String id, String role, String text) {
        return new ChatMessageSnapshot(id, role, text, 0L, "");
    }

    private static final List<ChatMessageSnapshot> CONVERSATION = Arrays.asList(
            message("legacy-1", "user", "was kostet ein rinderfilet"),
            message("legacy-2", "assistant", "das haengt von der herkunft ab"),
            message("m-3", "user", "bitte recherchieren"),
            message("m-4", "info", "Websuche: rinderfilet preis"),
            message("m-5", "assistant", "ich habe 12 quellen gefunden"));

    private static ResearchPhaseJournal journalWithPhases() {
        ResearchPhaseJournal journal = new ResearchPhaseJournal();
        journal.attribute("m-3", "scoping");
        journal.attribute("m-4", "research");
        journal.attribute("m-5", "research");
        journal.recordOutcome("research", "12 sources accepted");
        return journal;
    }

    @Test
    public void theWholePersistedConversationIsReturnedIncludingMessagesFromBeforeThisSession() {
        String rendered = ResearchChatHistoryProjection.render(
                CONVERSATION, journalWithPhases(), "research", true);

        for (ChatMessageSnapshot message : CONVERSATION) {
            assertTrue("every persisted message must appear: " + message.getText(),
                    rendered.contains(message.getText()));
        }
        assertFalse(rendered, rendered.contains("no messages recorded"));
    }

    @Test
    public void messagesWithoutAnAttributionStayUnknownInsteadOfBeingGuessedOrDropped() {
        String rendered = ResearchChatHistoryProjection.render(
                CONVERSATION, journalWithPhases(), "research", true);

        assertTrue(rendered, rendered.contains("== phase unknown"));
        assertTrue("legacy content is still rendered in full",
                rendered.contains("das haengt von der herkunft ab"));
        assertTrue(rendered, rendered.contains("== phase scoping"));
        assertTrue(rendered, rendered.contains("== phase research (current)"));
    }

    @Test
    public void infoBreadcrumbsArePartOfTheVisibleHistoryAndAreKept() {
        String rendered = ResearchChatHistoryProjection.render(
                CONVERSATION, journalWithPhases(), "research", true);
        assertTrue(rendered, rendered.contains("[info] Websuche: rinderfilet preis"));
    }

    @Test
    public void withoutRawFinishedPhasesCollapseToTheirOutcomeAndTheCurrentPhaseStaysDetailed() {
        String rendered = ResearchChatHistoryProjection.render(
                CONVERSATION, journalWithPhases(), "research", false);

        assertTrue(rendered, rendered.contains("summary: 2 messages (raw=true for details)")); // unknown
        assertTrue(rendered, rendered.contains("summary: 1 messages")); // finished scoping, no outcome
        assertFalse("a finished phase's messages are collapsed",
                rendered.contains("bitte recherchieren"));
        // The CURRENT phase keeps its detail, including its outcome line.
        assertTrue(rendered, rendered.contains("outcome: 12 sources accepted"));
        assertTrue(rendered, rendered.contains("ich habe 12 quellen gefunden"));
    }

    @Test
    public void aChatWithoutPersistedMessagesSaysSoInsteadOfPretendingToBeEmptyResearch() {
        assertEquals("(this chat has no persisted messages yet)",
                ResearchChatHistoryProjection.render(Collections.<ChatMessageSnapshot>emptyList(),
                        new ResearchPhaseJournal(), "scoping", true));
    }

    @Test
    public void aReturningPhaseIsRenderedWhereItHappenedRatherThanRegrouped() {
        List<ChatMessageSnapshot> messages = new ArrayList<ChatMessageSnapshot>(Arrays.asList(
                message("a", "user", "erste frage"),
                message("b", "assistant", "recherche laeuft"),
                message("c", "user", "zurueck zum scope")));
        ResearchPhaseJournal journal = new ResearchPhaseJournal();
        journal.attribute("a", "scoping");
        journal.attribute("b", "research");
        journal.attribute("c", "scoping");

        String rendered = ResearchChatHistoryProjection.render(messages, journal, "scoping", true);
        assertEquals("scoping appears twice, in conversation order",
                2, rendered.split("== phase scoping", -1).length - 1);
        assertTrue(rendered.indexOf("erste frage") < rendered.indexOf("recherche laeuft"));
        assertTrue(rendered.indexOf("recherche laeuft") < rendered.indexOf("zurueck zum scope"));
    }

    @Test
    public void theJournalSurvivesARestartAndCarriesNoMessageText() {
        ResearchPhaseJournal journal = journalWithPhases();
        String json = journal.toJson();

        assertFalse("the journal must never copy conversation text",
                json.contains("ich habe 12 quellen gefunden"));
        ResearchPhaseJournal restored = ResearchPhaseJournal.fromJson(json);
        assertEquals("research", restored.phaseOf("m-5"));
        assertEquals("12 sources accepted", restored.outcomeOf("research"));
        assertEquals("", restored.phaseOf("legacy-1"));
        assertEquals("", restored.phaseOf(null));
        // Corrupt content costs annotation, never conversation.
        assertTrue(ResearchPhaseJournal.fromJson("{not json").isEmpty());
    }
}
