package com.aresstack.askai.research.mcp;

/**
 * The DEV hand-off document for headless clients (issue #33): the internal service endpoint's connection
 * data (`<projectDir>/service-endpoint.json`) so gates, tests and an MCP-driving AI can invoke the explicit
 * user/host actions without the GUI. Localhost-only endpoint, per-session token that dies with the endpoint;
 * the file is overwritten on every session start and simply goes stale after close. Pure formatting — no IO
 * here, so the exact document shape is unit-testable.
 */
public final class ServiceEndpointDescriptorFile {

    private ServiceEndpointDescriptorFile() {
    }

    public static String toJson(String endpointId, String url, String transport, String token) {
        return "{\n"
                + "  \"endpointId\": \"" + escape(endpointId) + "\",\n"
                + "  \"url\": \"" + escape(url) + "\",\n"
                + "  \"transport\": \"" + escape(transport) + "\",\n"
                + "  \"token\": \"" + escape(token) + "\"\n"
                + "}\n";
    }

    private static String escape(String value) {
        return value == null ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\r", "\\r").replace("\n", "\\n");
    }
}
