package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolClient;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.capture.VisitedCapture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The PRODUCTIVE home of the {@code capture_id} convention (it is deliberately NOT in the agent loop): a
 * host-registered browser endpoint that delegates every {@code web_*} call to the Playwright sidecar via the
 * neutral {@link McpToolClient} and records every page-returning navigation into the session's Commit-37
 * {@link CaptureStore}. The model-facing result line is
 * {@code URL: <url> title="<title>" capture_id=<id>} followed by the cleaned text — the exact contract the
 * agent loop consumes and {@code source_accept} resolves. Endpoint identity is session+generation-scoped
 * like the research-control endpoint; {@link #close()} unregisters and is idempotent.
 */
public final class BrowserBridgeEndpoint {

    private final McpServerRegistry registry;
    private final McpToolClient sidecar;
    private final CaptureStore captures;
    private final String endpointId;
    private McpEndpointHandle handle;
    private boolean closed;

    public BrowserBridgeEndpoint(McpServerRegistry registry, McpToolClient sidecar, CaptureStore captures,
                                 String sessionKey, long generationId) {
        this.registry = registry;
        this.sidecar = sidecar;
        this.captures = captures;
        this.endpointId = "browser." + sessionKey + ".g" + generationId;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public McpEndpointHandle getHandle() {
        return handle;
    }

    /** Register and publish the bridge tool set. Idempotent. */
    public void open() {
        if (closed || handle != null) {
            return;
        }
        handle = registry.registerEndpoint(new McpEndpointDefinition(endpointId, "Browser"));
        List<McpToolContribution> tools = new ArrayList<McpToolContribution>();
        tools.add(passThrough("web_search", "Search the web via the configured provider.",
                McpToolParameter.string("query", true, "The search query")));
        tools.add(capturing("web_open", "Open a URL and return the cleaned page text with a capture id.",
                McpToolParameter.string("url", true, "The http(s) URL to open")));
        tools.add(capturing("web_read", "Return the cleaned text of the current page with a capture id."));
        tools.add(passThrough("web_links", "List the links of the current page with stable ids."));
        tools.add(capturing("web_follow", "Follow a link by its id from web_links.",
                McpToolParameter.string("link_id", true, "The link id to follow")));
        tools.add(capturing("web_back", "Go back to the previous page."));
        registry.updateTools(handle, tools);
    }

    /** Unregister the bridge (invalidates its token). Idempotent. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (handle != null) {
            registry.unregisterEndpoint(handle);
            handle = null;
        }
    }

    // ------------------------------------------------------------------ tool construction

    private McpToolContribution passThrough(final String tool, String description,
                                            McpToolParameter... parameters) {
        return McpToolContribution.of(tool, description, new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                return delegate(tool, call.getArguments());
            }
        }, parameters);
    }

    /** Page-returning tools additionally record a VISITED capture and prepend the contract line. */
    private McpToolContribution capturing(final String tool, String description,
                                          McpToolParameter... parameters) {
        return McpToolContribution.of(tool, description, new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                McpToolResult raw = delegate(tool, call.getArguments());
                if (raw.isError()) {
                    return raw;
                }
                String[] page = parseSidecarPage(raw.getText());
                VisitedCapture capture = captures.record(page[0], page[1], page[2]);
                return McpToolResult.ok("URL: " + page[0] + " title=\"" + page[1]
                        + "\" capture_id=" + capture.getCaptureId() + "\n" + page[2]);
            }
        }, parameters);
    }

    private McpToolResult delegate(String tool, Map<String, Object> arguments) {
        try {
            return McpToolResult.ok(sidecar.callTool(tool, arguments));
        } catch (McpToolClient.McpToolCallException ex) {
            return McpToolResult.error(ex.isEndpointUnavailable()
                    ? "Browser sidecar unavailable: " + ex.getMessage() : ex.getMessage());
        }
    }

    /** Parse the sidecar's render format ("URL: u\nTITLE: t\n[(truncated)]\n\ntext") into {url, title, text}. */
    static String[] parseSidecarPage(String rendered) {
        String url = "";
        String title = "";
        StringBuilder text = new StringBuilder();
        boolean inText = false;
        for (String line : (rendered == null ? "" : rendered).split("\n", -1)) {
            if (!inText && line.startsWith("URL: ")) {
                url = line.substring(5).trim();
            } else if (!inText && line.startsWith("TITLE: ")) {
                title = line.substring(7).trim();
            } else if (!inText && line.trim().isEmpty()) {
                inText = true;
            } else if (inText) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(line);
            }
        }
        return new String[]{url, title, text.toString()};
    }
}
