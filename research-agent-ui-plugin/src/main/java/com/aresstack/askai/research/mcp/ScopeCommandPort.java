package com.aresstack.askai.research.mcp;

/**
 * The session-side implementation of the ONE-command exclusion facade (live-gate 4 decision).
 * The session owns the scope coordinator, the concept service and the conflict registry; the
 * MCP tool handlers reach it through this port (wired like the bot gateway:
 * {@code resources.setScopeCommandPort(...)}), never through a second scope instance.
 */
public interface ScopeCommandPort {

    /**
     * @return the structured JSON reply (EXCLUDED + optional conceptConflict), a plain-text
     *         teaching error, or {@code null} when this session has no scope system at all
     */
    String excludeTopic(String topic);

    /** @return the structured JSON reply (REMOVED / KEPT_SUPPRESSED) or a plain-text error */
    String resolveConceptConflict(String conflictId, String decision);
}
