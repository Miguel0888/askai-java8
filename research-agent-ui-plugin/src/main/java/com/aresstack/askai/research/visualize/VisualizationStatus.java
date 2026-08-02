package com.aresstack.askai.research.visualize;

/**
 * The lifecycle status of the derived visualization, so the UI can tell apart states that otherwise look
 * identical: never run vs. preparing vs. running vs. the model deliberately chose NONE vs. a failure.
 */
public enum VisualizationStatus {

    /** No visualization has ever been requested for the current session. */
    NOT_STARTED,
    /** An artifact change was seen; the debounce is running (or deferred while the agent is busy). */
    PREPARING,
    /** The visualizer model call is in flight. */
    RUNNING,
    /** A diagram was produced. */
    HAS_DIAGRAM,
    /** The model deliberately decided there is nothing useful to visualize (a valid outcome). */
    NONE_DECIDED,
    /** No result could be produced (no inference port, timeout, transport failure, unparseable output). */
    FAILED
}
