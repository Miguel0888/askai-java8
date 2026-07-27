package com.aresstack.askai.plugin.host;

/**
 * Coordinates the session side of a transactional plugin-generation swap across the EDT boundary.
 *
 * <p>{@link #detachOutgoing()} runs on the EDT and only detaches the outgoing generation's sessions from routing
 * (a cheap, non-blocking model mutation). The returned {@link OutgoingSessions#closeAll()} is then invoked
 * <em>off</em> the EDT, where the potentially blocking work (scheduler shutdown, file/resource release) happens.
 * The old generation is retired only if {@code closeAll()} reports success, so no live session ever outlives its
 * plugin classloader.</p>
 */
public interface GenerationSwapHook {

    /** EDT: atomically detach the outgoing generation's sessions from routing. Never blocks. */
    OutgoingSessions detachOutgoing();

    /** A handle to the detached sessions, closed off the EDT. */
    interface OutgoingSessions {
        /** Off-EDT: close every detached session, collecting per-session failures. Must not touch Swing. */
        SessionCloseResult closeAll();
    }
}
