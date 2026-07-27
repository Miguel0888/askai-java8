package com.aresstack.askai.acp;

/** The OS-process view of the external agent — deliberately distinct from the ACP connection. */
public interface AgentProcessHandle {

    boolean isAlive();

    /** Force-kill after a shutdown timeout; safe to call repeatedly. */
    void destroyForcibly();
}
