package com.aresstack.askai.mcp.api;

import java.util.Collection;

/**
 * The generic MCP server registry port. Endpoints are registered with a random token, their tool sets can be
 * updated dynamically (emitting a tool-list-changed signal), and they are unregistered (invalidating the
 * token). This port has NO Solon/MCP-SDK type; a Solon streamable-HTTP transport implements it behind the
 * scenes (loopback-only, per-endpoint token), and an in-process reference registry implements it for wiring
 * and tests.
 */
public interface McpServerRegistry {

    McpEndpointHandle registerEndpoint(McpEndpointDefinition definition);

    void updateTools(McpEndpointHandle handle, Collection<McpToolContribution> tools);

    void unregisterEndpoint(McpEndpointHandle handle);
}
