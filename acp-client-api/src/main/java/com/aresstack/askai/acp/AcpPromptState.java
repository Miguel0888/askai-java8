package com.aresstack.askai.acp;

/** Lifecycle of one prompt run. CANCELLED/COMPLETED/FAILED are terminal. */
public enum AcpPromptState {
    IDLE, RUNNING, CANCELLING, CANCELLED, COMPLETED, FAILED;

    public boolean isTerminal() {
        return this == CANCELLED || this == COMPLETED || this == FAILED;
    }
}
