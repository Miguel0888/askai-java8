package com.aresstack.askai.research.backend;

import com.aresstack.askai.research.state.ResearchCommandType;

/**
 * The fachlich port between the research UI and whatever runs the research. Today a deterministic fake
 * implements it; later {@code AcpResearchSessionBackend} implements exactly this interface, so no ACP type
 * ever reaches the UI. Free of Swing/PF4J/ACP/Reactor/Solon/executor types.
 */
public interface ResearchSessionBackend {

    ResearchSessionHandle createSession(ResearchProjectRequest request, ResearchSessionListener listener);

    /** @return whether the command is currently legal for this session (for button enablement). */
    boolean canExecute(ResearchSessionHandle session, ResearchCommandType command);

    void executeCommand(ResearchSessionHandle session, ResearchCommandType command);

    void submitPrompt(ResearchSessionHandle session, ResearchPrompt prompt);

    /**
     * Carry a typed SERVICE COMMAND control envelope (e.g. a user web search, {@code #RSC1#}) to the runtime.
     * Semantically SEPARATE from {@link #submitPrompt} — a service command is never a chat turn — even though a
     * transport (ACP) may physically reuse the same frame underneath. The default ignores it: a fake/clickdummy
     * backend has no service transport. The ACP backend overrides it.
     */
    default void submitServiceCommand(ResearchSessionHandle session, String controlEnvelope) {
        // no-op by default
    }

    void approve(ResearchSessionHandle session, String approvalId);

    void reject(ResearchSessionHandle session, String approvalId, String reason);

    void pause(ResearchSessionHandle session);

    void resume(ResearchSessionHandle session);

    void cancel(ResearchSessionHandle session);

    void close(ResearchSessionHandle session);
}
