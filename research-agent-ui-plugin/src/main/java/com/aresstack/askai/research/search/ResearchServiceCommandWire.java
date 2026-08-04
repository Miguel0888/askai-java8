package com.aresstack.askai.research.search;

/**
 * Host-side ENCODER of the host→runtime service-command envelope ({@code #RSC1#}) — the sibling of the
 * runtime→host run wire ({@code #RSX1#}). A service command is a typed control instruction carried over the
 * ACP prompt frame (the only host→agent channel) but is NEVER a chat turn: the runtime dispatches it before
 * any model/TeamAgent/state logic. Encoded {@code key=value} with URL-encoded free-text values, so no field
 * ever contains a space. The runtime's parser of the same name is the ONLY consumer.
 *
 * <pre>#RSC1# manual_search request_id=&lt;uuid&gt; query=&lt;urlenc&gt;
 * #RSC1# set_language language=de|en</pre>
 */
public final class ResearchServiceCommandWire {

    /** Envelope marker; a prompt starting with this is a machine control command, never a chat message. */
    public static final String MARKER = "#RSC1# ";

    private ResearchServiceCommandWire() {
    }

    /**
     * Encode a user-triggered web search command. The query travels URL-encoded (never contains a space);
     * the language snapshot ("en"/"de") is AUTHORITATIVE for this search and re-synchronises the runtime's
     * session language on arrival.
     */
    public static String manualSearch(String requestId, String query, String languageCode) {
        StringBuilder sb = new StringBuilder(MARKER).append("manual_search")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "query", query);
        if (languageCode != null && !languageCode.isEmpty()) {
            sb.append(" language=").append("de".equalsIgnoreCase(languageCode) ? "de" : "en");
        }
        return sb.toString();
    }

    /**
     * Encode a live working-language switch (best-effort sync for the next TeamAgent turn; the language
     * snapshot on operation requests stays authoritative). Codes are single tokens, no encoding needed.
     */
    public static String setLanguage(String languageCode) {
        return MARKER + "set_language language="
                + ("de".equalsIgnoreCase(languageCode) ? "de" : "en");
    }

    private static void appendEncoded(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            sb.append(' ').append(key).append('=')
                    .append(java.net.URLEncoder.encode(value, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException ex) {
            // UTF-8 is guaranteed on every JVM; the field is simply omitted if it were missing.
        }
    }
}
