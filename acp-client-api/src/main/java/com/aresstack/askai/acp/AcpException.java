package com.aresstack.askai.acp;

/** A classified ACP failure (phase tells apart spawn / connect / initialize / session / prompt errors). */
public class AcpException extends Exception {

    public enum Phase { SPAWN, CONNECT, INITIALIZE, SESSION, PROMPT }

    private final Phase phase;

    public AcpException(Phase phase, String message, Throwable cause) {
        super(message, cause);
        this.phase = phase;
    }

    public Phase getPhase() {
        return phase;
    }
}
