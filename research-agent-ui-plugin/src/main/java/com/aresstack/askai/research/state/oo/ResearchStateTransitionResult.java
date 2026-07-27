package com.aresstack.askai.research.state.oo;

import com.aresstack.askai.research.state.ResearchEvent;

import java.util.Collections;
import java.util.List;

/**
 * Outcome of {@link ResearchStateMachinePort#dispatch}. On acceptance it carries the next
 * {@link ResearchStateMemento} (the new single source of truth) and the emitted domain events; on rejection it
 * carries the unchanged memento and a reason. Illegal transitions are rejections, never exceptions.
 */
public final class ResearchStateTransitionResult {

    private final boolean accepted;
    private final ResearchStateMemento nextMemento;
    private final List<ResearchEvent> events;
    private final String rejectionReason;

    private ResearchStateTransitionResult(boolean accepted, ResearchStateMemento nextMemento,
                                          List<ResearchEvent> events, String rejectionReason) {
        this.accepted = accepted;
        this.nextMemento = nextMemento;
        this.events = events == null ? Collections.<ResearchEvent>emptyList()
                : Collections.unmodifiableList(events);
        this.rejectionReason = rejectionReason;
    }

    public static ResearchStateTransitionResult accepted(ResearchStateMemento nextMemento,
                                                         List<ResearchEvent> events) {
        return new ResearchStateTransitionResult(true, nextMemento, events, null);
    }

    public static ResearchStateTransitionResult rejected(ResearchStateMemento unchanged, String reason) {
        return new ResearchStateTransitionResult(false, unchanged, Collections.<ResearchEvent>emptyList(), reason);
    }

    public boolean isAccepted() {
        return accepted;
    }

    /** @return the next memento on acceptance, or the unchanged memento on rejection. */
    public ResearchStateMemento getNextMemento() {
        return nextMemento;
    }

    public List<ResearchEvent> getEvents() {
        return events;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }
}
