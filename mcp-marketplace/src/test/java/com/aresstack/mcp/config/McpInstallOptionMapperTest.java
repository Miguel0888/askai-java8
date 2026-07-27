package com.aresstack.mcp.config;

import com.aresstack.mcp.marketplace.McpInstallOption;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Marketplace install option → neutral configuration: transports, env/headers, no activation, validation. */
public class McpInstallOptionMapperTest {

    private static Map<String, String> map(String k, String v) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k, v);
        return m;
    }

    @Test
    public void mapsStdioWithCommandArgsAndEnvironment() {
        McpInstallOption option = new McpInstallOption("npx", "stdio", "npx",
                Arrays.asList("-y", "@some/mcp-server"), null,
                map("API_KEY", "${API_KEY}"), null, "registry", "low");
        McpServerConfiguration config =
                McpInstallOptionMapper.toConfiguration("some.server", "Some Server", option);
        assertEquals(McpTransport.STDIO, config.getTransport());
        assertEquals("npx", config.getCommand());
        assertEquals(Arrays.asList("-y", "@some/mcp-server"), config.getArguments());
        assertEquals("${API_KEY}", config.getEnvironment().get("API_KEY")); // env preserved
        assertFalse("saving must never activate", config.isEnabled());
        assertEquals(McpApprovalPolicy.ALWAYS_ASK, config.getApprovalPolicy());
    }

    @Test
    public void mapsHttpWithEndpointAndHeaders() {
        McpInstallOption option = new McpInstallOption("remote", "http", null, null,
                "https://mcp.example.com/sse", null, map("Authorization", "Bearer x"), "registry", "low");
        McpServerConfiguration config =
                McpInstallOptionMapper.toConfiguration("remote.server", "Remote", option);
        assertEquals(McpTransport.HTTP, config.getTransport());
        assertEquals("https://mcp.example.com/sse", config.getEndpoint());
        assertEquals("Bearer x", config.getHeaders().get("Authorization")); // headers preserved
        assertFalse(config.isEnabled());
    }

    @Test
    public void mapsStreamableHttp() {
        McpInstallOption option = new McpInstallOption("s", "streamable-http", null, null,
                "https://mcp.example.com/mcp", null, null, "registry", "low");
        assertEquals(McpTransport.STREAMABLE_HTTP,
                McpInstallOptionMapper.toConfiguration("s.server", "S", option).getTransport());
    }

    @Test
    public void unknownTransportIsRejectedReadably() {
        McpInstallOption option = new McpInstallOption("x", "websocket", null, null,
                "wss://x", null, null, "registry", "low");
        try {
            McpInstallOptionMapper.toConfiguration("x.server", "X", option);
            fail("unknown transport must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("websocket"));
        }
    }

    @Test
    public void structurallyInvalidOptionsAreRejected() {
        // stdio without command
        try {
            McpInstallOptionMapper.toConfiguration("a.b", "A",
                    new McpInstallOption("bad", "stdio", null, null, null, null, null, "r", "low"));
            fail("stdio without command must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("command"));
        }
        // http without url
        try {
            McpInstallOptionMapper.toConfiguration("a.b", "A",
                    new McpInstallOption("bad", "http", null, null, null, null, null, "r", "low"));
            fail("http without endpoint must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("endpoint"));
        }
    }

    @Test
    public void enableIsAnExplicitSeparateStep() {
        McpInstallOption option = new McpInstallOption("npx", "stdio", "npx", null, null,
                null, null, "registry", "low");
        McpServerConfiguration saved = McpInstallOptionMapper.toConfiguration("a.b", "A", option);
        assertFalse(saved.isEnabled());
        McpServerConfiguration enabled = saved.withEnabled(true);
        assertTrue(enabled.isEnabled());
        assertFalse("original stays untouched", saved.isEnabled());
        assertEquals(saved.getCommand(), enabled.getCommand());
    }
}
