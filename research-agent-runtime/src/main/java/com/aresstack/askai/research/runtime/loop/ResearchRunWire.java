package com.aresstack.askai.research.runtime.loop;

/**
 * Agent-side encoder of the research ACP extension: structured run events travel as ACP MESSAGE lines with a
 * reserved machine envelope. The UI-plugin's {@code ResearchRunWire} parser is the ONLY consumer; nothing in
 * this format is meant for (or shown to) humans, and the mapper never interprets human-readable text. The two
 * classes are deliberately small duplicates on either side of the process boundary (the runtime jar and the
 * plugin share no module); a round-trip test pins the format.
 *
 * <pre>
 * #RSX1# progress pages=6 sources=4 hosts=2 tools=12 min_sources=3 min_hosts=2 activity=READING_PAGE \
 *        query=&lt;urlenc&gt; host=pf4j.org title=&lt;urlenc&gt; url=…
 * #RSX1# outcome stop=TOOL_BUDGET_EXHAUSTED pages=10 sources=7 hosts=1 min_sources=3 min_hosts=2 \
 *        recoverable=true limitation=INSUFFICIENT_HOST_DIVERSITY action=CONTINUE_RESEARCH
 * #RSX1# log &lt;free text to end of line&gt;
 * </pre>
 *
 * <p>Values must never contain spaces (the parser splits on them): free-text values (search query, page
 * title) travel URL-encoded and are decoded by the plugin's {@code decodedField}.</p>
 */
public final class ResearchRunWire {

    /** Envelope marker; a MESSAGE starting with this is a machine event, never a chat bubble. */
    public static final String MARKER = "#RSX1# ";

    private ResearchRunWire() {
    }

    /** One in-place progress update with the structured activity context (query/host/title/url). */
    public static String progress(ResearchRunProgress p, ResearchRunBudget budget,
                                  ResearchRunActivity activity) {
        StringBuilder sb = new StringBuilder(MARKER).append("progress")
                .append(" pages=").append(p.getPagesVisited())
                .append(" sources=").append(p.getAcceptedSources())
                .append(" hosts=").append(p.getDistinctHosts().size())
                .append(" tools=").append(p.getToolCalls())
                .append(" min_sources=").append(budget.getMinimumAcceptedSources())
                .append(" min_hosts=").append(budget.getMinimumDistinctHosts())
                .append(" activity=").append(activity == null || activity.getToken().isEmpty()
                        ? "WORKING" : activity.getToken());
        if (activity != null) {
            appendEncoded(sb, "query", activity.getSearchQuery());
            appendEncoded(sb, "host", activity.getHost());
            appendEncoded(sb, "title", activity.getPageTitle());
            if (!activity.getUrl().isEmpty()) {
                sb.append(" url=").append(activity.getUrl()); // URLs never contain spaces; always LAST
            }
        }
        return sb.toString();
    }

    private static void appendEncoded(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            sb.append(' ').append(key).append('=')
                    .append(java.net.URLEncoder.encode(value, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException ex) {
            // UTF-8 is guaranteed on every JVM; if it were missing, the field is simply omitted.
        }
    }

    /** The terminal outcome of one run — the only basis for the user-facing result card. */
    public static String outcome(ResearchRunOutcome o) {
        return MARKER + "outcome"
                + " stop=" + o.getStopReason()
                + " pages=" + o.getPagesVisited()
                + " sources=" + o.getAcceptedSources()
                + " hosts=" + o.getDistinctHosts()
                + " min_sources=" + o.getMinimumSources()
                + " min_hosts=" + o.getMinimumDistinctHosts()
                + " recoverable=" + o.isRecoverable()
                + " limitation=" + o.getLimitation()
                + " action=" + o.getRecommendedAction();
    }

    /**
     * A user-attention transition: a manual challenge (CAPTCHA) requires the user ({@code REQUIRED}) or was
     * solved ({@code RESOLVED}). Never a failure, never a technical log line — the UI renders it visibly.
     */
    public static String attention(String reason, String domainFamily, String url, boolean resolved) {
        StringBuilder sb = new StringBuilder(MARKER).append("attention")
                .append(" reason=").append(reason == null || reason.isEmpty() ? "UNKNOWN" : reason)
                .append(" domain=").append(domainFamily == null ? "" : domainFamily)
                .append(" state=").append(resolved ? "RESOLVED" : "REQUIRED");
        if (url != null && !url.isEmpty()) {
            sb.append(" url=").append(url); // URLs never contain spaces; always the LAST field
        }
        return sb.toString();
    }

    /** A technical diagnostic line (collapsible "technical details" only — never a chat bubble). */
    public static String log(String message) {
        return MARKER + "log " + (message == null ? "" : message.replace('\n', ' '));
    }
}
