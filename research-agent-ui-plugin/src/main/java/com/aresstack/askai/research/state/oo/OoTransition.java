package com.aresstack.askai.research.state.oo;

/**
 * Result of handling a command in the OO state model: either accepted with the next {@link ResearchPhaseState},
 * or rejected with a reason. Illegal transitions are rejections, never exceptions. Event/revision bookkeeping
 * is done by the facade that maps this back to the legacy {@code ResearchSessionState}.
 */
public final class OoTransition {

    private final boolean accepted;
    private final ResearchPhaseState next;
    private final String reason;

    private OoTransition(boolean accepted, ResearchPhaseState next, String reason) {
        this.accepted = accepted;
        this.next = next;
        this.reason = reason;
    }

    public static OoTransition accepted(ResearchPhaseState next) {
        return new OoTransition(true, next, null);
    }

    public static OoTransition rejected(String reason) {
        return new OoTransition(false, null, reason);
    }

    public boolean isAccepted() {
        return accepted;
    }

    public ResearchPhaseState getNext() {
        return next;
    }

    public String getReason() {
        return reason;
    }
}
