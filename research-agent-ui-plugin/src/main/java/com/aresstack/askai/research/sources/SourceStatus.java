package com.aresstack.askai.research.sources;

/** Lifecycle status of a research source. Exclusion is a status, not a physical delete. */
public enum SourceStatus {
    /** A reranked candidate written to the store before the page was visited: it carries a score but its
     * full text is still empty ("parked"). Promoted to NEW once the page is successfully read. */
    PARKED,
    NEW,
    REVIEWED,
    ACCEPTED,
    EXCLUDED,
    DUPLICATE,
    SUPERSEDED
}
