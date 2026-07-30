package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The STRUCTURED intent the main model returns for one TeamAgent turn. The model never mutates research state
 * directly: it proposes, and the host's two nested state patterns decide legality. This value type is exactly
 * that proposal —
 * <ul>
 *   <li>{@link #getAssistantMessage()} — what to say to the user (always present);</li>
 *   <li>{@link #getProposedCommand()} — an OPTIONAL research-control command NAME to attempt (validated
 *       against the live allowed set before it can run — an unknown/illegal name is dropped, never invented);</li>
 *   <li>{@link #getQuestion()} + {@link #getAspects()} — the understood research scope so far;</li>
 *   <li>{@link #isApprovalRequested()} + {@link #getApprovalSubject()} — an explicit ask for the user's
 *       approval (e.g. of the outline);</li>
 *   <li>{@link #getSearchQueries()} — varying search queries proposed AFTER approval.</li>
 * </ul>
 */
public final class TeamAgentTurn {

    private final String assistantMessage;
    private final String proposedCommand;
    private final String question;
    private final List<String> aspects;
    private final boolean approvalRequested;
    private final String approvalSubject;
    private final List<String> searchQueries;

    public TeamAgentTurn(String assistantMessage, String proposedCommand, String question,
                         List<String> aspects, boolean approvalRequested, String approvalSubject,
                         List<String> searchQueries) {
        this.assistantMessage = assistantMessage == null ? "" : assistantMessage;
        this.proposedCommand = emptyToNull(proposedCommand);
        this.question = emptyToNull(question);
        this.aspects = immutableCopy(aspects);
        this.approvalRequested = approvalRequested;
        this.approvalSubject = emptyToNull(approvalSubject);
        this.searchQueries = immutableCopy(searchQueries);
    }

    /** A plain assistant message with no proposed command/scope/approval (e.g. an honest fallback line). */
    public static TeamAgentTurn message(String assistantMessage) {
        return new TeamAgentTurn(assistantMessage, null, null, null, false, null, null);
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public String getProposedCommand() {
        return proposedCommand;
    }

    public boolean hasProposedCommand() {
        return proposedCommand != null;
    }

    public String getQuestion() {
        return question;
    }

    public List<String> getAspects() {
        return aspects;
    }

    public boolean isApprovalRequested() {
        return approvalRequested;
    }

    public String getApprovalSubject() {
        return approvalSubject;
    }

    public List<String> getSearchQueries() {
        return searchQueries;
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static List<String> immutableCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> copy = new ArrayList<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                copy.add(value.trim());
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
