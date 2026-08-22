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

    /**
     * The listener must never keep the JVM alive. AskAI shuts down by closing the window and letting the JVM
     * exit NATURALLY, so ONE non-daemon pool thread here outlives the app: the process stays up, keeps the
     * port bound, and the next AskAI start silently serves clients from the OLD generation because it could
     * not bind. Pinned: the workers are daemons AND stop() actually releases them.
     */
    @Test
    public void theListenerNeitherKeepsTheJvmAliveNorStrandsItsWorkers() throws Exception {
        ConnectorConfig config = new ConnectorConfig(0, "https://askai.example.com", "askai", "s", null);
        ChatGptConnectorServer server = new ChatGptConnectorServer(config,
                new ConnectorOAuthService(config), NO_TOOLS);
        server.start();
        // One real request, so a worker thread is actually created (the pool builds them lazily).
        java.net.HttpURLConnection health = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:" + server.boundPort() + "/health").openConnection();
        assertEquals(200, health.getResponseCode());
        health.getInputStream().close();

        assertTrue("a worker must exist for this test to mean anything", connectorThreadCount() > 0);
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("chatgpt-connector-")) {
                assertTrue("connector worker " + thread.getName() + " must be a daemon",
                        thread.isDaemon());
            }
        }

        server.stop();
        for (int wait = 0; wait < 50 && connectorThreadCount() > 0; wait++) {
            Thread.sleep(20);
        }
        assertEquals("stop() must release the workers, not strand them", 0, connectorThreadCount());
    }

    private static int connectorThreadCount() {
        int alive = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("chatgpt-connector-") && thread.isAlive()) {
                alive++;
            }
        }
        return alive;
    }
}
