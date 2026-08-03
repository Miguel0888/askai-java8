package com.aresstack.askai.research.search;

/**
 * A handle to one in-flight user-triggered web search. The {@code requestId} correlates the search with its
 * typed started/progress/completed/failed events (so stale/late events of a superseded or cancelled run can be
 * ignored), and {@link #cancel()} cancels the productive search — never a chat error, never a state-machine
 * event.
 */
public interface ManualWebSearchHandle {

    String getRequestId();

    /** Cancel this search; late events carrying its {@code requestId} must then be ignored by the caller. */
    void cancel();
}
