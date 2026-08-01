package com.aresstack.askai.acp;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Ordering/terminal guard for one prompt run: assigns monotonically increasing sequence numbers, guarantees
 * exactly ONE terminal callback, drops every update arriving after the terminal (late SDK callbacks), and
 * isolates listener exceptions so the protocol reader never dies because a consumer threw.
 */
public final class PromptDispatcher {

    private final String sessionId;
    private final String promptId;
    private final AcpUpdateListener listener;
    private final AcpStates.Prompt state = new AcpStates.Prompt();
    private final AtomicLong sequence = new AtomicLong();

    public PromptDispatcher(String sessionId, String promptId, AcpUpdateListener listener) {
        this.sessionId = sessionId;
        this.promptId = promptId;
        this.listener = listener;
        this.state.to(AcpPromptState.RUNNING);
    }

    public String getPromptId() {
        return promptId;
    }

    public AcpPromptState getState() {
        return state.get();
    }

    /** Nanotime of the most recent delivered update; 0 when none arrived yet. */
    private volatile long lastUpdateNanos;

    /** @return false when dropped (already terminal). */
    public boolean update(AcpUpdate.Kind kind, String text) {
        if (state.get().isTerminal()) {
            return false;
        }
        lastUpdateNanos = System.nanoTime();
        AcpUpdate update = new AcpUpdate(sessionId, promptId, sequence.incrementAndGet(), kind, text);
        try {
            listener.onUpdate(update);
        } catch (RuntimeException ignored) {
            // a broken consumer must not kill the reader
        }
        return true;
    }

    /**
     * Nanoseconds since the last delivered update, or {@link Long#MAX_VALUE} when none arrived. Used to
     * DRAIN before the terminal: the prompt response and the update notifications travel on different
     * threads, so on a slow machine the response can overtake the tail of the update stream — marking
     * terminal immediately would then drop REAL updates (they all precede the response on the wire), not
     * just late stragglers.
     */
    public long nanosSinceLastUpdate() {
        long last = lastUpdateNanos;
        return last == 0 ? Long.MAX_VALUE : System.nanoTime() - last;
    }

    /** Mark cancelling (idempotent; false when already terminal). */
    public boolean cancelling() {
        return state.to(AcpPromptState.CANCELLING);
    }

    /** Exactly one terminal wins; later attempts (cancel-vs-complete race, late callbacks) are no-ops. */
    public boolean terminal(AcpPromptState terminalState, String detail) {
        if (!terminalState.isTerminal()) {
            return false;
        }
        if (!state.to(terminalState)) {
            return false;
        }
        try {
            listener.onTerminal(promptId, terminalState, detail == null ? "" : detail);
        } catch (RuntimeException ignored) {
            // isolate consumer failures
        }
        return true;
    }
}
