package com.aresstack.askai.research.search;

/**
 * The slice-S1 default {@link ManualWebSearchPort}: it only records that a user search was requested (a compact,
 * non-sensitive {@code [manual-search]} trace on the runWithDevPlugins console) and performs NO execution. It
 * exists so the yellow suggestion click is already decoupled from the agent chat turn while the productive
 * backend transport is still being built — slice S2 replaces this with the port that reuses the runtime
 * {@code SearchStrategy}. It never touches the state machine, the agent or any artifact.
 */
public final class LoggingManualWebSearchPort implements ManualWebSearchPort {

    @Override
    public ManualWebSearchHandle search(ManualWebSearchRequest request) {
        final String requestId = java.util.UUID.randomUUID().toString();
        if (request != null && !request.isBlank()) {
            // Log only the length, never the query text: the console trace stays free of user content.
            System.err.println("[manual-search] requested (placeholder, no backend transport) queryLen="
                    + request.getQuery().length());
        }
        return new ManualWebSearchHandle() {
            public String getRequestId() {
                return requestId;
            }

            public void cancel() {
                // Nothing to cancel: the placeholder never dispatched a real search.
            }
        };
    }
}
