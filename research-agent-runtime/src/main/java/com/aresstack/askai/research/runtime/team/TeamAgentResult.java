package com.aresstack.askai.research.runtime.team;

/**
 * The outcome of one TeamAgent turn (greeting or reply). Exactly one of three honest states:
 * <ul>
 *   <li>{@link Status#OK} — the model answered and the answer parsed; {@link #getTurn()} is the structured
 *       intent and {@link #getValidatedCommand()} is its proposed command IFF that command is currently
 *       allowed by the host state machine (else {@code null});</li>
 *   <li>{@link Status#MODEL_UNAVAILABLE} — the main model could not be reached (transport/timeout/HTTP);
 *       the caller shows an honest status and offers a retry, never a fabricated outline;</li>
 *   <li>{@link Status#UNUSABLE_ANSWER} — the model answered but, even after one bounded repair, produced no
 *       parseable turn; again an honest error, never invented content.</li>
 * </ul>
 */
public final class TeamAgentResult {

    public enum Status {
        OK,
        MODEL_UNAVAILABLE,
        UNUSABLE_ANSWER
    }

    private final Status status;
    private final TeamAgentTurn turn;
    private final String validatedCommand;
    private final String detail;

    private TeamAgentResult(Status status, TeamAgentTurn turn, String validatedCommand, String detail) {
        this.status = status;
        this.turn = turn;
        this.validatedCommand = validatedCommand;
        this.detail = detail == null ? "" : detail;
    }

    public static TeamAgentResult ok(TeamAgentTurn turn, String validatedCommand) {
        return new TeamAgentResult(Status.OK, turn, validatedCommand, "");
    }

    public static TeamAgentResult modelUnavailable(String detail) {
        return new TeamAgentResult(Status.MODEL_UNAVAILABLE, null, null, detail);
    }

    public static TeamAgentResult unusableAnswer(String detail) {
        return new TeamAgentResult(Status.UNUSABLE_ANSWER, null, null, detail);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    public TeamAgentTurn getTurn() {
        return turn;
    }

    /** The proposed command, but only when the host currently allows it; {@code null} otherwise. */
    public String getValidatedCommand() {
        return validatedCommand;
    }

    public String getDetail() {
        return detail;
    }
}
