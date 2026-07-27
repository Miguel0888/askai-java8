package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;

/**
 * The agent side of the shared composer's routing. The composer keeps ONE physical widget and one send path:
 * in Yapping it uses its own Ollama path; when {@link #isActive()} (Questing with a live agent session) it
 * routes plain prompts and stop here instead. The router never duplicates the Ollama path — it only exposes
 * the active {@link com.aresstack.askai.plugin.api.agent.AgentSession}'s target through a generic interface.
 */
public interface ChatSubmissionRouter {

    /** @return whether an agent session is active and should receive composer input (Questing). */
    boolean isActive();

    /** Generic availability of the active agent target (never derived from domain enums by the UI). */
    SubmissionAvailability getAvailability();

    /** Submit a plain prompt to the active agent session. No-op when {@link #isActive()} is false. */
    void submitText(String text);

    /** Stop/interrupt the active agent run. No-op when {@link #isActive()} is false. */
    void stop();

    /** Notified on the UI thread whenever the active target or its availability changes. */
    void addChangeListener(Runnable listener);

    void removeChangeListener(Runnable listener);
}
