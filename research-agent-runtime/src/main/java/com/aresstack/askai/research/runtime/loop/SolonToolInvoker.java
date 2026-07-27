package com.aresstack.askai.research.runtime.loop;

import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.time.Duration;
import java.util.Map;

/**
 * Production {@link ToolInvoker}: a real Solon MCP client over a streamable endpoint. Tool-level failures
 * (error results / call exceptions) surface as {@link ToolFailure}; a dead endpoint as
 * {@link EndpointUnavailable}. This is the exact invoker 36C reuses against the live Playwright sidecar.
 */
public final class SolonToolInvoker implements ToolInvoker, AutoCloseable {

    private final McpClientProvider client;

    public SolonToolInvoker(String url, String transport) {
        this.client = McpClientProvider.builder()
                .apiUrl(url)
                .channel(transport == null || transport.isEmpty() ? "streamable" : transport)
                .cacheSeconds(0)
                .initializationTimeout(Duration.ofSeconds(15))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String call(String toolName, Map<String, Object> args)
            throws ToolFailure, EndpointUnavailable {
        try {
            ToolResult result = client.callTool(toolName, args);
            String text = String.valueOf(result);
            // The Solon runtime maps handler errors to exceptions client-side; a textual error marker from
            // our own tool contract is still treated as a tool failure.
            if (text.startsWith("Tool failed:") || text.startsWith("Not allowed")) {
                throw new ToolFailure(text);
            }
            return text;
        } catch (ToolFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (message.contains("Connection") || message.contains("connect")
                    || message.contains("Timeout") || message.contains("timeout")) {
                throw new EndpointUnavailable(message);
            }
            throw new ToolFailure(message);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
