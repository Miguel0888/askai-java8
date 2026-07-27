package com.aresstack.askai.acp;

/**
 * Receives one prompt run's stream. Called on the adapter's neutral callback executor — NEVER on the EDT;
 * UI layers marshal via their own UiExecutor. Exactly one terminal callback per prompt; no update after it.
 */
public interface AcpUpdateListener {

    void onUpdate(AcpUpdate update);

    /** Terminal outcome: state is CANCELLED, COMPLETED or FAILED. */
    void onTerminal(String promptId, AcpPromptState state, String detail);
}
