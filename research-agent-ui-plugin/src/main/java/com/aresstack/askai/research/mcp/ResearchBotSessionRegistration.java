package com.aresstack.askai.research.mcp;

/**
 * One live research session in the {@link ResearchBotSessionDirectory}: the PUBLIC chat session id (the
 * host's stable chat UUID — what an external client passes as {@code sessionId}), the internal agent session
 * key ({@code <agentId>#<chatId>}) and the session's gateway.
 * <p>
 * A registration is its OWN identity (reference equality). That is what makes unregistering safe when a
 * chat is closed and immediately reopened: the late {@code close()} of the old session can only remove the
 * registration it created, never the new session's one.
 */
public final class ResearchBotSessionRegistration {

    private final String publicSessionId;
    private final String internalSessionKey;
    private final ResearchBotSessionGateway gateway;

    ResearchBotSessionRegistration(String publicSessionId, String internalSessionKey,
                                   ResearchBotSessionGateway gateway) {
        this.publicSessionId = publicSessionId;
        this.internalSessionKey = internalSessionKey;
        this.gateway = gateway;
    }

    /** The chat UUID an external client addresses this session by. */
    public String getPublicSessionId() {
        return publicSessionId;
    }

    /** The internal agent session key ({@code agentId#chatId}); diagnostics only, never a public id. */
    public String getInternalSessionKey() {
        return internalSessionKey;
    }

    public ResearchBotSessionGateway getGateway() {
        return gateway;
    }
}
