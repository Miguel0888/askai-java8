package com.aresstack.askai.research.mcp;

/**
 * THE port onto ONE research session: structured command execution, the structured session state and the
 * phase-attributed chat history. Implemented by the session, resolved at call time; a {@code null} result
 * means "this session cannot answer right now" (not started / already torn down).
 * <p>
 * Deliberately free of any MCP type: both faces — the per-session {@link ResearchBotControlEndpoint} and the
 * public multi-session connector — are built ON this port, they are not part of it.
 */
public interface ResearchBotSessionGateway {

    /** Execute one command with arguments; empty command = the arguments are a plain chat message. */
    String execute(String command, String arguments);

    /** Phase/run state + currently valid commands, clickable buttons and search suggestions. */
    String describeState();

    /** The phase-attributed chat record; {@code raw} = every entry instead of phase summaries. */
    String describeHistory(boolean raw);

    /**
     * The tail of the session's TECHNICAL detail lines (the collapsed diagnostics area of the
     * transcript: wire logs, concept tool rounds, readiness verdicts). Observability for a
     * driving client — the same lines a human reads in the GUI, nothing extra. The default is
     * {@code null} (endpoint without an attached session / faces that do not serve it).
     */
    default String describeTechnicalLog(int tailLines) {
        return null;
    }
}
