package com.aresstack.askai.mcp.api;

/**
 * Host-provided factory for {@link McpToolClient}s. The URL carries the endpoint token in its path (like all
 * session endpoints in this runtime); implementations must not log it.
 */
public interface McpToolClientFactory {

    McpToolClient connect(String url, String transport);
}
