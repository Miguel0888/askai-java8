package com.aresstack.askai.research.agent;

/**
 * The ONE business-level research language (agent utterances, host activity texts, search requests and the
 * TeamAgent's working-language instruction all derive from it). It belongs to a running research SESSION —
 * never to the process — and travels as a stable two-letter code ("en"/"de") over settings and the wire.
 */
public enum ResearchLanguage {

    ENGLISH("en"),
    GERMAN("de");

    private final String code;

    ResearchLanguage(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** Persisted/wire codes: "de" → German, anything else (including null) → the English default. */
    public static ResearchLanguage fromCode(String code) {
        return "de".equalsIgnoreCase(code) ? GERMAN : ENGLISH;
    }
}
