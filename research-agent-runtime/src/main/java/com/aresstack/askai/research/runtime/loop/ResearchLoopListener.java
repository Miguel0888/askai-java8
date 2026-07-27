package com.aresstack.askai.research.runtime.loop;

/** Status stream of a run (mapped to ACP updates by the caller). PHASE_READY is an EVENT, never a transition. */
public interface ResearchLoopListener {

    void status(String message);

    /** The loop considers the phase ready for user review. The HOST decides what happens next. */
    void phaseReady(ResearchStopReason reason);
}
