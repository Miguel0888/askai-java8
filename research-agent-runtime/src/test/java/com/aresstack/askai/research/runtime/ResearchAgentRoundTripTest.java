package com.aresstack.askai.research.runtime;

import com.aresstack.askai.acp.AcpConnection;
import com.aresstack.askai.acp.AcpException;
import com.aresstack.askai.acp.AcpPromptState;
import com.aresstack.askai.acp.AcpSession;
import com.aresstack.askai.acp.AcpUpdate;
import com.aresstack.askai.acp.AcpUpdateListener;
import com.aresstack.askai.acp.AgentLaunchSpec;
import com.aresstack.askai.acp.solon.SolonAcpAgentConnector;
import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.mcp.solon.SolonMcpServerRuntime;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * The full boundary walk of Commit 35b: SolonMcpServerRuntime hosts a research-control endpoint with
 * research_status; the external Java-8 research agent process is spawned over ACP, connects the endpoint
 * via its Solon MCP client during session/new (REAL readiness: tools/list + research_status), streams
 * RESEARCH_MCP_READY / BROWSER_NOT_AVAILABLE and status into ACP, completes, stays usable for a second
 * prompt, and shuts down; a wrong token fails session creation atomically.
 */
public class ResearchAgentRoundTripTest {

    private static SolonMcpServerRuntime runtime; // Solon is a process-global singleton → one per JVM

    private String javaBin;
    private String agentJar;

    @Before
    public void resolve() {
        agentJar = System.getProperty("research.agent.jar");
        assumeTrue("agent jar missing", agentJar != null && new File(agentJar).isFile());
        String home = System.getProperty("acp.java.home", System.getProperty("java.home"));
        javaBin = home + File.separator + "bin" + File.separator
                + (System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        assumeTrue("java missing", new File(javaBin).isFile());
        if (runtime == null) {
            runtime = new SolonMcpServerRuntime();
        }
    }

    @AfterClass
    public static void shutdownRuntime() {
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    private static final class Collecting implements AcpUpdateListener {
        final List<AcpUpdate> updates = new CopyOnWriteArrayList<AcpUpdate>();
        final AtomicReference<AcpPromptState> terminal = new AtomicReference<AcpPromptState>();
        final CountDownLatch terminated = new CountDownLatch(1);

        public void onUpdate(AcpUpdate update) {
            updates.add(update);
        }

        public void onTerminal(String promptId, AcpPromptState state, String detail) {
            terminal.set(state);
            terminated.countDown();
        }

        boolean sawMessage(String needle) {
            for (AcpUpdate u : updates) {
                if (u.getText().contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    public void fullBoundaryWalkThroughRealProcessAndRealMcp() throws Exception {
        // 1. Research-control endpoint FIRST (before the agent starts), with a recording research_status.
        final AtomicInteger statusCalls = new AtomicInteger();
        McpEndpointHandle handle = runtime.registerEndpoint(
                new McpEndpointDefinition("research.itest", "Research Control"));
        runtime.updateTools(handle, Collections.singletonList(
                McpToolContribution.of("research_status", "Current research state.",
                        new McpToolHandler() {
                            public McpToolResult invoke(McpToolCall call) {
                                statusCalls.incrementAndGet();
                                return McpToolResult.ok("RESEARCH/running rev=7");
                            }
                        })));
        String url = runtime.endpointUrl(handle);

        // 2. Registry handle → structured launch environment (as the backend does; browser fully absent).
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("ASKAI_SESSION_ID", "itest-1");
        env.put("ASKAI_PROJECT_ID", "p1");
        env.put("ASKAI_RESEARCH_MCP_URL", url);
        env.put("ASKAI_RESEARCH_MCP_TRANSPORT", "streamable");
        env.put("ASKAI_RESEARCH_MCP_TOKEN", handle.getToken());
        AgentLaunchSpec spec = new AgentLaunchSpec(javaBin, Arrays.asList("-jar", agentJar), env);

        // 3–7. Spawn → initialize → session/new (agent runs tools/list + research_status inside).
        SolonAcpAgentConnector connector = new SolonAcpAgentConnector(Duration.ofSeconds(45), null);
        AcpConnection connection = connector.connect(spec);
        try {
            AcpSession session = connection.newSession();
            assertTrue("agent must have called research_status during session/new",
                    statusCalls.get() >= 1);

            // 8–9. Prompt → readiness + honest browser absence + live status streamed over ACP.
            Collecting first = new Collecting();
            session.prompt("investigate pf4j", first);
            assertTrue(first.terminated.await(45, TimeUnit.SECONDS));
            assertEquals(AcpPromptState.COMPLETED, first.terminal.get());
            assertTrue("readiness announced", first.sawMessage("RESEARCH_MCP_READY"));
            assertTrue("browser absence visible", first.sawMessage("BROWSER_NOT_AVAILABLE"));
            assertTrue("live research_status mirrored", first.sawMessage("RESEARCH/running rev=7"));
            assertTrue("second research_status call", statusCalls.get() >= 2);

            // 10. Session stays usable for a second prompt.
            Collecting second = new Collecting();
            session.prompt("next step", second);
            assertTrue(second.terminated.await(45, TimeUnit.SECONDS));
            assertEquals(AcpPromptState.COMPLETED, second.terminal.get());

            session.close();
        } finally {
            connection.close();
        }

        // 11. Endpoint removal invalidates it.
        runtime.unregisterEndpoint(handle);
        assertTrue(runtime.endpointUrl(handle) == null);
    }

    @Test
    public void wrongTokenFailsSessionCreationAtomically() throws Exception {
        McpEndpointHandle handle = runtime.registerEndpoint(
                new McpEndpointDefinition("research.itest2", "Research Control"));
        runtime.updateTools(handle, Collections.singletonList(
                McpToolContribution.of("research_status", "x", new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        return McpToolResult.ok("ok");
                    }
                })));
        // Corrupt the token inside the URL → the agent's MCP connect/readiness must fail → session/new fails.
        String badUrl = runtime.endpointUrl(handle).replaceAll("/[0-9a-f]+$", "/deadbeef");
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("ASKAI_RESEARCH_MCP_URL", badUrl);
        env.put("ASKAI_RESEARCH_MCP_TRANSPORT", "streamable");
        AgentLaunchSpec spec = new AgentLaunchSpec(javaBin, Arrays.asList("-jar", agentJar), env);

        SolonAcpAgentConnector connector = new SolonAcpAgentConnector(Duration.ofSeconds(30), null);
        AcpConnection connection = connector.connect(spec);
        try {
            connection.newSession();
            fail("session/new must fail when the research endpoint is unreachable");
        } catch (AcpException expected) {
            assertEquals(AcpException.Phase.SESSION, expected.getPhase());
        } finally {
            connection.close(); // rollback: no half-started session survives
        }
        runtime.unregisterEndpoint(handle);
    }
}
