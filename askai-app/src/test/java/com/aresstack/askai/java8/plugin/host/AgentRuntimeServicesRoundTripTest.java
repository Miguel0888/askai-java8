package com.aresstack.askai.java8.plugin.host;

import com.aresstack.askai.acp.AcpAgentConnector;
import com.aresstack.askai.acp.AcpConnection;
import com.aresstack.askai.acp.AgentLaunchSpec;
import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolResult;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/**
 * Reproduces the GUI's agent start ON THE APP CLASSPATH: the same {@link AgentRuntimeServices} (lazy Solon
 * MCP registry + ACP connector) the frame hands to the plugin, a research endpoint with research_status, and
 * the REAL agent jar spawned over ACP. This is the exact chain behind the reported
 * "session/new failed ... [code=-32603]" — if an app-classpath conflict breaks the MCP server, THIS test
 * fails the same way, headless.
 */
public class AgentRuntimeServicesRoundTripTest {

    @Test
    public void agentSessionNewSucceedsAgainstTheAppHostedRegistry() throws Exception {
        String agentJar = System.getProperty("research.agent.jar", "");
        assumeTrue("SKIPPED: agent jar not built", new File(agentJar).isFile());
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + (System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");

        AgentRuntimeServices services = new AgentRuntimeServices();
        Map<Class<?>, Object> map = services.asServiceMap();
        McpServerRegistry registry = (McpServerRegistry) map.get(McpServerRegistry.class);
        AcpAgentConnector connector = (AcpAgentConnector) map.get(AcpAgentConnector.class);

        McpEndpointHandle handle = registry.registerEndpoint(
                new McpEndpointDefinition("research.appdiag", "Research Control"));
        registry.updateTools(handle, Collections.singletonList(
                McpToolContribution.of("research_status", "Current research state.",
                        new McpToolHandler() {
                            public McpToolResult invoke(McpToolCall call) {
                                return McpToolResult.ok("scoping/new rev=0");
                            }
                        })));
        String url = registry.endpointUrl(handle);
        assertNotNull(url);

        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("ASKAI_SESSION_ID", "appdiag");
        env.put("ASKAI_PROJECT_ID", "p1");
        env.put("ASKAI_RESEARCH_MCP_URL", url);
        env.put("ASKAI_RESEARCH_MCP_TRANSPORT", "streamable");
        AcpConnection connection = connector.connect(
                new AgentLaunchSpec(javaBin, Arrays.asList("-jar", agentJar), env));
        try {
            // The GUI failure happens exactly here: the agent's MCP client initialize inside session/new.
            connection.newSession();
        } finally {
            connection.close();
            registry.unregisterEndpoint(handle);
        }
    }
}
