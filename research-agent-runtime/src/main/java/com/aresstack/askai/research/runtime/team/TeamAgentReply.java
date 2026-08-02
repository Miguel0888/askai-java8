package com.aresstack.askai.research.runtime.team;

/**
 * Maps a {@link TeamAgentResult} to the single user-visible line the runtime sends over ACP as a plain agent
 * message. An OK turn shows the model's own {@code assistantMessage}; every failure shows an HONEST, typed line
 * (never a fabricated answer, never the withheld message of a rejected command) that invites a retry. Keeping
 * this pure and ASCII-only makes it unit-testable and safe to escape over the chat wire.
 */
public final class TeamAgentReply {

    private TeamAgentReply() {
    }

    public static String visible(TeamAgentResult result) {
        switch (result.getStatus()) {
            case OK:
                return result.getOutput().getAssistantMessage();
            case MODEL_UNAVAILABLE:
                return "I cannot reach the research assistant model right now"
                        + detailSuffix(result.getDetail()) + ". Please try again in a moment.";
            case UNUSABLE_ANSWER:
                return "I could not form a clear answer just now. Could you rephrase, or try again?";
            case COMMAND_REJECTED:
                return "That step is not available right now. Let's continue from where we are.";
            default:
                return "";
        }
    }

    private static String detailSuffix(String detail) {
        return detail == null || detail.trim().isEmpty() ? "" : " (" + detail.trim() + ")";
    }
}
