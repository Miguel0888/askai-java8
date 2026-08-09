package com.aresstack.askai.research.connector;

import com.aresstack.askai.mcp.api.McpToolContribution;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The settings-save path restarts the listener on the SAME port (changed secret → stop + immediate
 * re-bind). This must be reliable on Windows — a bind race here surfaced as a "could not start"
 * dialog right after saving a new secret.
 */
public class ConnectorRestartTest {

    private static final ChatGptConnectorServer.ToolProvider NO_TOOLS =
            new ChatGptConnectorServer.ToolProvider() {
                public List<McpToolContribution> tools() {
                    return Collections.emptyList();
                }
            };

    @Test
    public void fiftyImmediateStopStartCyclesOnTheSamePortAllBind() throws Exception {
        ConnectorConfig probe = new ConnectorConfig(0, "https://askai.example.com", "askai", "s", null);
        ChatGptConnectorServer first = new ChatGptConnectorServer(probe,
                new ConnectorOAuthService(probe), NO_TOOLS);
        first.start();
        int port = first.boundPort();
        first.stop();

        for (int cycle = 0; cycle < 50; cycle++) {
            ConnectorConfig config = new ConnectorConfig(port, "https://askai.example.com",
                    "askai", "secret-" + cycle, null);
            ChatGptConnectorServer server = new ChatGptConnectorServer(config,
                    new ConnectorOAuthService(config), NO_TOOLS);
            try {
                server.start();
            } catch (Exception bindFailure) {
                throw new AssertionError("re-bind failed in cycle " + cycle + ": "
                        + bindFailure.getMessage(), bindFailure);
            }
            assertEquals(port, server.boundPort());
            assertTrue(server.isRunning());
            server.stop();
        }
    }
}
