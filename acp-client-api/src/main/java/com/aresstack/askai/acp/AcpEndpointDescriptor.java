package com.aresstack.askai.acp;

/**
 * Neutral description of an MCP endpoint handed to the external agent (Commit 35): url, transport, token,
 * endpointId. The ACP layer knows ONLY this data — never ResearchToolPolicy or McpServerRegistry.
 */
public final class AcpEndpointDescriptor {

    private final String endpointId;
    private final String url;
    private final String transport;
    private final String token;

    public AcpEndpointDescriptor(String endpointId, String url, String transport, String token) {
        this.endpointId = endpointId;
        this.url = url;
        this.transport = transport;
        this.token = token;
    }

    public String getEndpointId() { return endpointId; }
    public String getUrl() { return url; }
    public String getTransport() { return transport; }
    public String getToken() { return token; }
}
