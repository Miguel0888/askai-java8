package com.aresstack.askai.research.acp;

import java.util.HashMap;
import java.util.Map;

/**
 * Host-side parser of the research ACP extension: structured run events arrive as ACP MESSAGE lines with a
 * reserved machine envelope (see the runtime's encoder of the same name). A wire line is NEVER rendered as
 * chat text — it is decoded into typed backend events. The format is token-based ({@code key=value}, values
 * without spaces, an optional URL always last), deliberately not free text: the mapper never interprets
 * human-readable sentences.
 */
public final class ResearchRunWire {

    /** Envelope marker; identical to the runtime encoder's constant (pinned by a round-trip test). */
    public static final String MARKER = "#RSX1# ";

    public static final String TYPE_PROGRESS = "progress";
    public static final String TYPE_OUTCOME = "outcome";
    public static final String TYPE_LOG = "log";

    private ResearchRunWire() {
    }

    public static boolean isWireLine(String text) {
        return text != null && text.startsWith(MARKER);
    }

    /** @return the event type token of a wire line ({@code progress|outcome|log}), or {@code null}. */
    public static String typeOf(String text) {
        if (!isWireLine(text)) {
            return null;
        }
        String rest = text.substring(MARKER.length());
        int space = rest.indexOf(' ');
        return space < 0 ? rest : rest.substring(0, space);
    }

    /** The free-text payload of a {@code log} line (everything after the type token). */
    public static String logText(String text) {
        String rest = text.substring(MARKER.length());
        int space = rest.indexOf(' ');
        return space < 0 ? "" : rest.substring(space + 1);
    }

    /** All {@code key=value} fields of a progress/outcome line (values contain no spaces). */
    public static Map<String, String> fields(String text) {
        Map<String, String> fields = new HashMap<String, String>();
        for (String token : text.substring(MARKER.length()).split(" ")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                fields.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        return fields;
    }

    public static int intField(Map<String, String> fields, String key) {
        try {
            return Integer.parseInt(fields.get(key));
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
