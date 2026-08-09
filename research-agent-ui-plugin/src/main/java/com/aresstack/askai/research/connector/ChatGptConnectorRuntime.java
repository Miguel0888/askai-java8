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

    /** The ONE canonical refresh-token store, independent of any session/workspace lifecycle. */
    public static java.io.File defaultRefreshStore() {
        String appData = System.getenv("APPDATA");
        java.io.File base = appData == null || appData.trim().isEmpty()
                ? new java.io.File(System.getProperty("user.home"))
                : new java.io.File(appData);
        return new java.io.File(base, ".askai-java8/chatgpt-connector/oauth-refresh-tokens.json");
    }

    private ConnectorConfig runningConfig;

    /**
     * Start the listener (idempotent for the SAME configuration; a changed port/origin/client pair
     * restarts it). A bind failure is remembered and visible, never fatal.
     */
    public synchronized void ensureStarted(ConnectorConfig config) {
        if (server != null && server.isRunning()) {
            if (sameConfig(runningConfig, config)) {
                return;
            }
            server.stop();
            server = null;
        }
        if (!config.isComplete()) {
            startFailure = "incomplete configuration (the public origin is required)";
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
            runningConfig = config;
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
        runningConfig = null;
        startFailure = null;
    }

    private static boolean sameConfig(ConnectorConfig a, ConnectorConfig b) {
        return a != null && b != null
                && a.getPort() == b.getPort()
                && a.getPublicOrigin().equals(b.getPublicOrigin())
                && a.getClientId().equals(b.getClientId())
                && a.getClientSecret().equals(b.getClientSecret());
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
