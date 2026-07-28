package com.aresstack.askai.research.runtime.loop;

/** Status stream of a run (mapped to ACP updates by the caller). PHASE_READY is an EVENT, never a transition. */
public interface ResearchLoopListener {

    /** A technical diagnostic line — surfaced only in collapsible technical details, never as chat text. */
    void status(String message);

    /**
     * The run's counters changed or its current activity moved on. Callers render this as ONE in-place
     * progress card per run — never one bubble per page/source. {@code activityToken} is a stable machine
     * token (SEARCHING/OPENING_PAGE/READING_LINKS/RECORDING_SOURCE); {@code url} may be null.
     */
    void progress(ResearchRunProgress progress, String activityToken, String url);

    /** The loop considers the phase ready for user review. The HOST decides what happens next. */
    void phaseReady(ResearchStopReason reason);
}
