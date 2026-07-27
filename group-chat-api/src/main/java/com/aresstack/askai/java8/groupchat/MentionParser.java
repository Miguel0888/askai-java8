package com.aresstack.askai.java8.groupchat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses {@code @Name} mentions from a Markdown message body.
 *
 * <p>A mention is a word that starts with {@code @} and is followed by one or more non-whitespace
 * characters.  Leading/trailing punctuation adjacent to the token is stripped when matching
 * against participant display names.</p>
 *
 * <p>The special token {@code @AskAI} (case-insensitive) refers to the room bot and must not be
 * resolved to a human participant.</p>
 */
public final class MentionParser {

    /** The canonical bot mention token (case-insensitive match applies). */
    public static final String BOT_MENTION = "@AskAI";

    private MentionParser() {
    }

    /**
     * Extract the participant IDs that are mentioned in {@code markdown} by matching their display
     * names.
     *
     * @param markdown    the raw message text
     * @param participants the participants currently in the room
     * @return an unmodifiable list of participant IDs that are mentioned; never {@code null}
     */
    public static List<String> extractMentionedIds(String markdown, List<Participant> participants) {
        if (markdown == null || markdown.isEmpty() || participants == null || participants.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> found = new ArrayList<String>();
        for (String token : mentionTokens(markdown)) {
            String name = token.substring(1); // strip leading '@'
            for (Participant p : participants) {
                if (p.getDisplayName().equalsIgnoreCase(name)
                        && !found.contains(p.getParticipantId())) {
                    found.add(p.getParticipantId());
                }
            }
        }
        return Collections.unmodifiableList(found);
    }

    /**
     * Returns {@code true} when the message contains an explicit {@code @AskAI} mention
     * (case-insensitive).
     */
    public static boolean mentionsBot(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return false;
        }
        for (String token : mentionTokens(markdown)) {
            if (BOT_MENTION.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns all raw {@code @Token} strings found in the text (including the leading {@code @}).
     */
    public static List<String> mentionTokens(String text) {
        List<String> tokens = new ArrayList<String>();
        String[] words = text.split("\\s+");
        for (String word : words) {
            if (word.startsWith("@") && word.length() > 1) {
                // Strip trailing punctuation so "@Maria," matches "@Maria".
                String stripped = stripTrailingPunctuation(word);
                if (stripped.length() > 1) {
                    tokens.add(stripped);
                }
            }
        }
        return Collections.unmodifiableList(tokens);
    }

    private static String stripTrailingPunctuation(String word) {
        int end = word.length();
        while (end > 1 && isPunctuation(word.charAt(end - 1))) {
            end--;
        }
        return word.substring(0, end);
    }

    private static boolean isPunctuation(char c) {
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ':' || c == ';';
    }
}
