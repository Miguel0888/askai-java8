package com.aresstack.askai.research.connector;

import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.research.mcp.ResearchBotControlEndpoint;

import java.util.Collections;
import java.util.List;

/**
 * The process-wide owner of the ChatGPT-connector server: ONE listener for the whole app, while research
 * sessions come and go. Sessions attach/detach their {@link ResearchBotControlEndpoint.SessionGateway};
 * the server resolves the CURRENT gateway at call time, so a ChatGPT conversation survives a session
 * restart (calls without an attached session return the MCP "no session" error).
 */
public final class ChatGptConnectorRuntime {

    private static final ChatGptConnectorRuntime INSTANCE = new ChatGptConnectorRuntime();

    private volatile ResearchBotControlEndpoint.SessionGateway gateway;
    private ChatGptConnectorServer server;
    private String startFailure;

    private ChatGptConnectorRuntime() {
    }

    public static ChatGptConnectorRuntime get() {
        return INSTANCE;
    }

    /** The session's driving gateway (or null on session close). */
    public void attachGateway(ResearchBotControlEndpoint.SessionGateway sessionGateway) {
        this.gateway = sessionGateway;
    }

    /** Start the listener once (idempotent). A bind failure is remembered and visible, never fatal. */
    public synchronized void ensureStarted(ConnectorConfig config) {
        if (server != null && server.isRunning()) {
            return;
        }
        if (!config.isComplete()) {
            startFailure = "incomplete configuration (public origin, client id and client secret are required)";
            System.out.println("[chatgpt-connector] not started: " + startFailure);
            return;
        }
        ChatGptConnectorServer created = new ChatGptConnectorServer(config,
                new ConnectorOAuthService(config), new ChatGptConnectorServer.ToolProvider() {
                    public List<McpToolContribution> tools() {
                        ResearchBotControlEndpoint.SessionGateway current = gateway;
                        return current == null ? Collections.<McpToolContribution>emptyList()
                                : ResearchBotControlEndpoint.drivingTools(current);
                    }
                });
        try {
            created.start();
            server = created;
            startFailure = null;
            System.out.println("[chatgpt-connector] listening on port " + created.boundPort()
                    + " for " + config.getPublicOrigin() + ConnectorConfig.MCP_PUBLIC_PATH
                    + " (TLS at the reverse proxy)");
        } catch (Exception bindFailure) {
            startFailure = bindFailure.getMessage() == null
                    ? bindFailure.getClass().getSimpleName() : bindFailure.getMessage();
            System.out.println("[chatgpt-connector] start FAILED on port " + config.getPort()
                    + ": " + startFailure);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        startFailure = null;
    }

    public synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public synchronized int runningPort() {
        return server == null ? -1 : server.boundPort();
    }

    /** The last start failure ("" when none) — surfaced in the settings UI. */
    public synchronized String lastStartFailure() {
        return startFailure == null ? "" : startFailure;
    }
}
