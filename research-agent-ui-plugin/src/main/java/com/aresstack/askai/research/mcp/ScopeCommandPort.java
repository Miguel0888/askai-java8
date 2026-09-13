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

    /**
     * Offer 3-5 AI-authored orientation searches as the user's yellow exploration tags (gate 8b:
     * the optional in-band field starved under the generation grammar — as a command the model
     * actually offers). {@code suggestionsJson} = {@code [{"query":..,"purpose":..},..]}.
     * @return the structured JSON reply (OFFERED + count) or a plain-text error
     */
    String offerSearches(String suggestionsJson);

    /** The current blacklist terms (excluded labels/ids + exclusions), for the concept tools. */
    java.util.List<String> blacklistedTerms();

    /**
     * A concept card at {@code path} was renamed to {@code newName}: the session rewrites every
     * open conflict path through that node atomically (interim fix until the ID sidecar).
     * Default no-op so fakes stay source-compatible.
     */
    default void conceptNodeRenamed(java.util.List<String> path, String newName) {
    }

    /** One technical-log line from a concept tool handler. Default no-op (fakes). */
    default void conceptToolLog(String line) {
    }
}
