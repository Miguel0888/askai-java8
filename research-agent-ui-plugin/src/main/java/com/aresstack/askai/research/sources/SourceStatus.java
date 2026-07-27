package com.aresstack.askai.research.sources;

/** Lifecycle status of a research source. Exclusion is a status, not a physical delete. */
public enum SourceStatus {
    NEW,
    REVIEWED,
    ACCEPTED,
    EXCLUDED,
    DUPLICATE,
    SUPERSEDED
}
