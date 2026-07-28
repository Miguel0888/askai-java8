package com.aresstack.askai.mcp.solon;

import com.aresstack.askai.mcp.api.McpToolClient;
import com.aresstack.askai.mcp.api.McpToolClientFactory;

import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.time.Duration;
import java.util.Map;

/**
 * Host-provided {@link McpToolClientFactory} over the real Solon streamable-HTTP MCP client. Connection and
 * timeout failures surface as {@code endpointUnavailable}; everything else as a plain tool failure. The URL
 * (which carries the token in its path) is never logged.
 */
public final class SolonMcpToolClientFactory implements McpToolClientFactory {

    @Override
    public McpToolClient connect(String url, String transport) {
        final McpClientProvider client = McpClientProvider.builder()
                .apiUrl(url)
                .channel(transport == null || transport.isEmpty() ? "streamable" : transport)
                .cacheSeconds(0)
                .initializationTimeout(Duration.ofSeconds(15))
                .requestTimeout(Duration.ofSeconds(60))
                .build();
        return new McpToolClient() {
            public String callTool(String toolName, Map<String, Object> arguments)
                    throws McpToolCallException {
                try {
                    ToolResult result = client.callTool(toolName, arguments);
                    String text = String.valueOf(result);
                    if (text.startsWith("Tool failed:") || text.startsWith("Not allowed")) {
                        throw new McpToolCallException(text, false);
                    }
                    return text;
                } catch (McpToolCallException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    boolean unavailable = message.contains("Connection") || message.contains("connect")
                            || message.contains("Timeout") || message.contains("timeout");
                    throw new McpToolCallException(message, unavailable);
                }
            }

            public void close() {
                try {
                    client.close();
                } catch (RuntimeException ignored) {
                }
            }
        };
    }
}
