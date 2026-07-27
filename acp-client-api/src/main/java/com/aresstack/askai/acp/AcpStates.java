package com.aresstack.askai.acp;

/**
 * Guarded state holders for the three ACP lifecycles. Transitions are enforced centrally: an invalid
 * transition (e.g. COMPLETED→CANCELLING, CLOSED→ACTIVE, FAILED→READY) is rejected (returns false) and never
 * mutates the state. Each holder is thread-safe and guarantees at most one terminal state.
 */
public final class AcpStates {

    private AcpStates() {
    }

    public static final class Connection {
        private AcpConnectionState state = AcpConnectionState.STARTING;

        public synchronized AcpConnectionState get() {
            return state;
        }

        public synchronized boolean to(AcpConnectionState next) {
            boolean ok;
            switch (next) {
                case INITIALIZING: ok = state == AcpConnectionState.STARTING; break;
                case READY:        ok = state == AcpConnectionState.INITIALIZING; break;
                case FAILED:       ok = state == AcpConnectionState.STARTING
                                        || state == AcpConnectionState.INITIALIZING
                                        || state == AcpConnectionState.READY; break;
                case CLOSED:       ok = state == AcpConnectionState.READY; break;
                default:           ok = false;
            }
            if (ok) {
                state = next;
            }
            return ok;
        }
    }

    public static final class Session {
        private AcpSessionState state = AcpSessionState.CREATED;

        public synchronized AcpSessionState get() {
            return state;
        }

        public synchronized boolean to(AcpSessionState next) {
            boolean ok;
            switch (next) {
                case ACTIVE:  ok = state == AcpSessionState.CREATED; break;
                case CLOSING: ok = state == AcpSessionState.ACTIVE; break;
                case CLOSED:  ok = state == AcpSessionState.CLOSING || state == AcpSessionState.ACTIVE
                                   || state == AcpSessionState.CREATED; break;
                default:      ok = false;
            }
            if (ok) {
                state = next;
            }
            return ok;
        }
    }

    public static final class Prompt {
        private AcpPromptState state = AcpPromptState.IDLE;

        public synchronized AcpPromptState get() {
            return state;
        }

        public synchronized boolean to(AcpPromptState next) {
            if (state.isTerminal()) {
                return false; // exactly one terminal outcome, ever
            }
            boolean ok;
            switch (next) {
                case RUNNING:    ok = state == AcpPromptState.IDLE; break;
                case CANCELLING: ok = state == AcpPromptState.RUNNING; break;
                case CANCELLED:  ok = state == AcpPromptState.CANCELLING || state == AcpPromptState.RUNNING; break;
                case COMPLETED:
                case FAILED:     ok = state == AcpPromptState.RUNNING || state == AcpPromptState.CANCELLING; break;
                default:         ok = false;
            }
            if (ok) {
                state = next;
            }
            return ok;
        }
    }
}
