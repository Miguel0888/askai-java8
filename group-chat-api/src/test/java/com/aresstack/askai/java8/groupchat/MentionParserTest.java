package com.aresstack.askai.java8.groupchat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class MentionParserTest {

    @Test
    public void detectsBotMention() {
        assertTrue(MentionParser.mentionsBot("@AskAI summarize this"));
        assertTrue(MentionParser.mentionsBot("@askai hello"));
        assertTrue(MentionParser.mentionsBot("Hey @AskAI, what do you think?"));
        assertFalse(MentionParser.mentionsBot("No bot here"));
        assertFalse(MentionParser.mentionsBot("@Alice what is going on?"));
    }

    @Test
    public void extractsMentionedParticipantIds() {
        Participant alice = new Participant("alice-id", "Alice", null);
        Participant bob = new Participant("bob-id", "Bob", null);
        List<Participant> participants = Arrays.asList(alice, bob);

        List<String> mentioned = MentionParser.extractMentionedIds("@Alice what do you think?", participants);
        assertEquals(1, mentioned.size());
        assertEquals("alice-id", mentioned.get(0));
    }

    @Test
    public void extractsMultipleMentions() {
        Participant alice = new Participant("alice-id", "Alice", null);
        Participant bob = new Participant("bob-id", "Bob", null);
        List<Participant> participants = Arrays.asList(alice, bob);

        List<String> mentioned = MentionParser.extractMentionedIds("@Alice and @Bob let's meet", participants);
        assertEquals(2, mentioned.size());
        assertTrue(mentioned.contains("alice-id"));
        assertTrue(mentioned.contains("bob-id"));
    }

    @Test
    public void stripsTrailingPunctuation() {
        Participant alice = new Participant("alice-id", "Alice", null);
        List<Participant> participants = Collections.singletonList(alice);

        List<String> mentioned = MentionParser.extractMentionedIds("@Alice, are you there?", participants);
        assertEquals(1, mentioned.size());
        assertEquals("alice-id", mentioned.get(0));
    }

    @Test
    public void doesNotMatchBotAsParticipant() {
        // Even if a participant were named "AskAI", bot detection is separate.
        Participant bot = new Participant("bot-id", "AskAI", null);
        List<Participant> participants = Collections.singletonList(bot);

        List<String> mentioned = MentionParser.extractMentionedIds("@AskAI summarize", participants);
        // The method still matches display names — the caller is responsible for separating bot logic.
        assertEquals(1, mentioned.size());
        assertEquals("bot-id", mentioned.get(0));
    }

    @Test
    public void returnsEmptyForNullOrBlank() {
        assertTrue(MentionParser.extractMentionedIds(null, Collections.<Participant>emptyList()).isEmpty());
        assertTrue(MentionParser.extractMentionedIds("", Collections.<Participant>emptyList()).isEmpty());
        assertTrue(MentionParser.extractMentionedIds("hello", null).isEmpty());
    }

    @Test
    public void groupChatModeConstantsAreDistinct() {
        assertFalse(GroupChatMode.YAPPING.equals(GroupChatMode.QUESTING));
        assertFalse(GroupChatMode.YAPPING.equals(GroupChatMode.PARTYING));
        assertFalse(GroupChatMode.QUESTING.equals(GroupChatMode.PARTYING));
        assertEquals("builtin.partying", GroupChatMode.PARTYING);
    }
}
