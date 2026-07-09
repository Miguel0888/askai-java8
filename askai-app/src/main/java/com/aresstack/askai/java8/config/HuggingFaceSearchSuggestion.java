package com.aresstack.askai.java8.config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One entry of the Install panel's search dropdown: a HuggingFace search term plus the input
 * modalities the suggested model accepts. Persisted one per line as
 * {@code <term> | <modality>,<modality>} (e.g. {@code voxtral-mini-3b | audio}); a line without
 * tags means a plain text model. Unknown tags are ignored so the format stays forgiving.
 */
public final class HuggingFaceSearchSuggestion {

    /** Input modalities a suggested model accepts; rendered as icons in the dropdown. */
    public enum Modality {
        TEXT, AUDIO, VISION
    }

    private final String term;
    private final Set<Modality> modalities;

    public HuggingFaceSearchSuggestion(String term, Set<Modality> modalities) {
        this.term = term == null ? "" : term.trim();
        this.modalities = modalities == null || modalities.isEmpty()
                ? EnumSet.of(Modality.TEXT) : EnumSet.copyOf(modalities);
    }

    public String getTerm() {
        return term;
    }

    public Set<Modality> getModalities() {
        return modalities;
    }

    /** The editable combo editor shows the plain search term. */
    public String toString() {
        return term;
    }

    /** Parses the persisted newline-separated list; blank lines and duplicates are dropped. */
    public static List<HuggingFaceSearchSuggestion> parseList(String raw) {
        List<HuggingFaceSearchSuggestion> suggestions = new ArrayList<HuggingFaceSearchSuggestion>();
        List<String> seenTerms = new ArrayList<String>();
        String[] lines = (raw == null ? "" : raw).split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            HuggingFaceSearchSuggestion suggestion = parseLine(lines[i]);
            if (suggestion != null && !seenTerms.contains(suggestion.getTerm())) {
                seenTerms.add(suggestion.getTerm());
                suggestions.add(suggestion);
            }
        }
        return suggestions;
    }

    /** @return the parsed suggestion, or {@code null} for a blank line. */
    public static HuggingFaceSearchSuggestion parseLine(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.length() == 0) {
            return null;
        }
        int pipe = trimmed.indexOf('|');
        if (pipe < 0) {
            return new HuggingFaceSearchSuggestion(trimmed, EnumSet.of(Modality.TEXT));
        }
        String term = trimmed.substring(0, pipe).trim();
        if (term.length() == 0) {
            return null;
        }
        EnumSet<Modality> modalities = EnumSet.noneOf(Modality.class);
        String[] tags = trimmed.substring(pipe + 1).split(",");
        for (int i = 0; i < tags.length; i++) {
            String tag = tags[i].trim().toUpperCase();
            if (tag.length() == 0) {
                continue;
            }
            try {
                modalities.add(Modality.valueOf(tag));
            } catch (IllegalArgumentException ignored) {
                // Unknown tag: skip, keep the suggestion usable.
            }
        }
        return new HuggingFaceSearchSuggestion(term, modalities);
    }
}
