package com.aresstack.askai.mcp.solon;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpTestTools;
import com.aresstack.askai.mcp.api.McpToolContribution;

import org.junit.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Proves the real Solon streamable-HTTP transport end-to-end with a real Solon MCP client on 127.0.0.1:
 * tools/list, ping, echo, dynamic tool removal, wrong-token rejection, controlled shutdown. Solon is a
 * process-global singleton, so the whole flow runs in one test to avoid start/stop churn.
 */
public class SolonMcpServerRuntimeTest {

    private static Set<String> toolNames(Collection<FunctionTool> tools) {
        Set<String> names = new HashSet<String>();
        for (FunctionTool t : tools) {
            names.add(t.name());
        }
        return names;
    }

    private static McpClientProvider client(String url) {
        return McpClientProvider.builder()
                .apiUrl(url)
                .channel("streamable")
                .cacheSeconds(0)
                .initializationTimeout(Duration.ofSeconds(15))
                .requestTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Test
    public void solonStreamableRoundTripWithRealClient() {
        SolonMcpServerRuntime runtime = new SolonMcpServerRuntime();
        McpEndpointHandle handle = runtime.registerEndpoint(
                new McpEndpointDefinition("research", "Research Control"));
        List<McpToolContribution> both = Arrays.asList(McpTestTools.ping(), McpTestTools.echo());
        runtime.updateTools(handle, both);

        String url = runtime.endpointUrl(handle);
        assertNotNull("endpoint url", url);
        assertTrue("must bind loopback", url.startsWith("http://127.0.0.1:"));

        McpClientProvider client = client(url);
        try {
            // tools/list over the real transport
            Set<String> names = toolNames(client.getTools());
            assertTrue("ping discoverable", names.contains("ping"));
            assertTrue("echo discoverable", names.contains("echo"));

            // ping + echo round-trip (client -> HTTP -> Solon -> handler -> back)
            ToolResult pong = client.callTool("ping", Collections.<String, Object>emptyMap());
            assertNotNull(pong);
            assertTrue("ping returns pong", String.valueOf(pong).toLowerCase().contains("pong"));

            ToolResult echoed = client.callTool("echo",
                    Collections.<String, Object>singletonMap("text", "hello-mcp"));
            assertNotNull(echoed);
            assertTrue("echo returns its text", String.valueOf(echoed).contains("hello-mcp"));
        } finally {
            client.close();
        }

        // Dynamic tool set change: remove echo, a fresh client sees only ping.
        runtime.updateTools(handle, Collections.singletonList(McpTestTools.ping()));
        McpClientProvider client2 = client(url);
        try {
            Set<String> names = toolNames(client2.getTools());
            assertTrue(names.contains("ping"));
            assertEquals("echo must be gone", false, names.contains("echo"));
        } finally {
            client2.close();
        }

        // Wrong token → different path → not reachable.
        boolean rejected = false;
        String badUrl = "http://127.0.0.1:" + runtime.getPort() + "/mcp/research/deadbeefdeadbeef";
        try {
            McpClientProvider bad = client(badUrl);
            try {
                bad.getTools();
                rejected = false; // should not succeed
            } finally {
                bad.close();
            }
        } catch (RuntimeException expected) {
            rejected = true;
        }
        assertTrue("wrong token endpoint must be rejected", rejected);

        runtime.unregisterEndpoint(handle);
        runtime.shutdown();
        runtime.shutdown(); // idempotent
    }
}
