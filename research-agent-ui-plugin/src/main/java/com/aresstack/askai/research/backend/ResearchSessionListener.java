package com.aresstack.askai.research.backend;

/**
 * Receives backend events for one session, delivered serially in monotonic sequence order on the backend
 * thread. The controller marshals these onto the EDT. No calls arrive after {@code close()}.
 */
public interface ResearchSessionListener {

    void onEvent(ResearchBackendEvent event);
}
