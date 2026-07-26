package com.aresstack.askai.research.backend;

/** Opaque handle to one backend session. */
public interface ResearchSessionHandle {

    String getSessionId();

    String getProjectId();
}
