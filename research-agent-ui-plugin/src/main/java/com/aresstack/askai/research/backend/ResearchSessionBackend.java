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

    void approve(ResearchSessionHandle session, String approvalId);

    void reject(ResearchSessionHandle session, String approvalId, String reason);

    void pause(ResearchSessionHandle session);

    void resume(ResearchSessionHandle session);

    void cancel(ResearchSessionHandle session);

    void close(ResearchSessionHandle session);
}
