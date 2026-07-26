package com.aresstack.askai.research.state;

import java.util.Collections;
import java.util.List;

/**
 * Outcome of {@link ResearchStateMachine#dispatch}. On acceptance it carries the new state and the emitted
 * events; on rejection it carries the unchanged state and a reason. Illegal transitions are rejections,
 * never exceptions.
 */
public final class ResearchTransitionResult {

    private final boolean accepted;
    private final ResearchSessionState state;
    private final List<ResearchEvent> events;
    private final String rejectionReason;

    private ResearchTransitionResult(boolean accepted, ResearchSessionState state,
                                     List<ResearchEvent> events, String rejectionReason) {
        this.accepted = accepted;
        this.state = state;
        this.events = events == null ? Collections.<ResearchEvent>emptyList()
                : Collections.unmodifiableList(events);
        this.rejectionReason = rejectionReason;
    }

    static ResearchTransitionResult accepted(ResearchSessionState state, List<ResearchEvent> events) {
        return new ResearchTransitionResult(true, state, events, null);
    }

    static ResearchTransitionResult rejected(ResearchSessionState unchanged, String reason) {
        return new ResearchTransitionResult(false, unchanged, Collections.<ResearchEvent>emptyList(), reason);
    }

    public boolean isAccepted() {
        return accepted;
    }

    public ResearchSessionState getState() {
        return state;
    }

    public List<ResearchEvent> getEvents() {
        return events;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }
}
