package com.aresstack.askai.research.runtime.loop;

/**
 * Agent-side encoder of the research ACP extension: structured run events travel as ACP MESSAGE lines with a
 * reserved machine envelope. The UI-plugin's {@code ResearchRunWire} parser is the ONLY consumer; nothing in
 * this format is meant for (or shown to) humans, and the mapper never interprets human-readable text. The two
 * classes are deliberately small duplicates on either side of the process boundary (the runtime jar and the
 * plugin share no module); a round-trip test pins the format.
 *
 * <pre>
 * #RSX1# progress pages=6 sources=4 hosts=2 tools=12 min_sources=3 min_hosts=2 activity=OPENING_PAGE url=…
 * #RSX1# outcome stop=TOOL_BUDGET_EXHAUSTED pages=10 sources=7 hosts=1 min_sources=3 min_hosts=2 \
 *        recoverable=true limitation=INSUFFICIENT_HOST_DIVERSITY action=CONTINUE_RESEARCH
 * #RSX1# log &lt;free text to end of line&gt;
 * </pre>
 */
public final class ResearchRunWire {

    /** Envelope marker; a MESSAGE starting with this is a machine event, never a chat bubble. */
    public static final String MARKER = "#RSX1# ";

    private ResearchRunWire() {
    }

    /** One in-place progress update ({@code activity} is a stable token; {@code url} may be null). */
    public static String progress(ResearchRunProgress p, ResearchRunBudget budget,
                                  String activityToken, String url) {
        StringBuilder sb = new StringBuilder(MARKER).append("progress")
                .append(" pages=").append(p.getPagesVisited())
                .append(" sources=").append(p.getAcceptedSources())
                .append(" hosts=").append(p.getDistinctHosts().size())
                .append(" tools=").append(p.getToolCalls())
                .append(" min_sources=").append(budget.getMinimumAcceptedSources())
                .append(" min_hosts=").append(budget.getMinimumDistinctHosts())
                .append(" activity=").append(activityToken == null ? "WORKING" : activityToken);
        if (url != null && !url.isEmpty()) {
            sb.append(" url=").append(url); // URLs never contain spaces; always the LAST field
        }
        return sb.toString();
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

    /** A technical diagnostic line (collapsible "technical details" only — never a chat bubble). */
    public static String log(String message) {
        return MARKER + "log " + (message == null ? "" : message.replace('\n', ' '));
    }
}
