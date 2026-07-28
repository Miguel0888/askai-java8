package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.WebSearchItem;

import org.noear.solon.Solon;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolHandler;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;

import java.util.Map;

/**
 * Entry point of the Java-21 Browser-MCP sidecar process. Boots a Solon streamable-HTTP MCP endpoint on
 * 127.0.0.1 with a caller-provided port and session token (both passed by the spawning host) and exposes the
 * exact flat tool set {@code web_search, web_open, web_read, web_links, web_follow, web_back} over a
 * {@link BrowserSession}. Args: {@code --port=<port> --token=<token>} plus optional
 * {@code --browser-channel=chrome|msedge} (default chrome), {@code --headless=true|false} (default true),
 * {@code --allow-private=true|false} (default false; true only for local test servers) and
 * {@code --search-url=<template with {query}>}. Logs go to STDERR only; the token never appears in them.
 *
 * <p>The Playwright-backed session ({@link PlaywrightSessionFactory}) runs a structured capability probe;
 * when the runtime is unavailable every tool reports the SPECIFIC status as a readable error. A missing
 * driver or browser never breaks packaging or the host build, and nothing is downloaded. See problems.md
 * MCP-P005.</p>
 */
public final class BrowserMcpSidecarMain {

    private BrowserMcpSidecarMain() {
    }

    public static void main(String[] args) {
        int port = intArg(args, "--port=", 0);
        String token = stringArg(args, "--token=");
        if (port <= 0 || token == null || token.isEmpty()) {
            System.err.println("usage: --port=<port> --token=<token>");
            System.exit(2);
        }

        Solon.start(BrowserMcpSidecarMain.class, new String[]{
                "--server.host=127.0.0.1",
                "--server.port=" + port
        });

        String channel = stringArg(args, "--browser-channel=");
        BrowserSession session = PlaywrightSessionFactory.create(
                channel == null ? "chrome" : channel,
                !"false".equalsIgnoreCase(stringArg(args, "--headless=")),
                "true".equalsIgnoreCase(stringArg(args, "--allow-private=")),
                stringArg(args, "--search-url="),
                com.aresstack.askai.browser.BrowserLimits.defaults());
        McpServerEndpointProvider endpoint = McpServerEndpointProvider.builder()
                .name("browser")
                .version("0.1")
                .channel("streamable")
                .mcpEndpoint("/mcp/browser/" + token)
                .build();
        registerTools(endpoint, session);
        endpoint.postStart();
        // Ordered teardown even on SIGTERM: page/context/browser/driver-child close before the JVM exits,
        // so no Chromium process is left behind.
        final BrowserSession toClose = session;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                toClose.close();
            }
        }, "browser-session-shutdown"));
        System.err.println("[browser-mcp] ready on 127.0.0.1:" + port
                + " backend=" + session.getBackendKind());
    }

    static void registerTools(McpServerEndpointProvider endpoint, final BrowserSession session) {
        endpoint.addTool(new FunctionToolDesc("web_search")
                .description("Search the web; returns numbered results with title, url and snippet.")
                .stringParamAdd("query", "The search query")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        StringBuilder sb = new StringBuilder();
                        for (WebSearchItem item : session.search(str(args, "query")).getItems()) {
                            sb.append(item.getId()).append(": ").append(item.getTitle())
                              .append(" — ").append(item.getUrl()).append('\n')
                              .append("   ").append(item.getSnippet()).append('\n');
                        }
                        return sb.length() == 0 ? "No results." : sb.toString();
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_open")
                .description("Open a URL and return the cleaned page text.")
                .stringParamAdd("url", "The http(s) URL to open")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return render(session.open(str(args, "url")));
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_read")
                .description("Return the cleaned text of the current page.")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return render(session.currentPage());
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_links")
                .description("List the links of the current page with stable ids.")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        StringBuilder sb = new StringBuilder();
                        for (BrowserLink link : session.links()) {
                            sb.append(link.getId()).append(": ").append(link.getText())
                              .append(" — ").append(link.getUrl()).append('\n');
                        }
                        return sb.length() == 0 ? "No links." : sb.toString();
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_follow")
                .description("Follow a link by its id from web_links.")
                .stringParamAdd("link_id", "The link id to follow")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return render(session.follow(str(args, "link_id")));
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_back")
                .description("Go back to the previous page.")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return render(session.back());
                    }
                }));
    }

    private static String render(BrowserPageSnapshot snap) {
        return "URL: " + snap.getUrl() + "\nTITLE: " + snap.getTitle()
                + (snap.isTruncated() ? "\n(truncated)" : "") + "\n\n" + snap.getText();
    }

    private static String str(Map<String, Object> args, String key) throws BrowserException {
        Object v = args == null ? null : args.get(key);
        if (v == null || String.valueOf(v).trim().isEmpty()) {
            throw new BrowserException("Missing required argument: " + key);
        }
        return String.valueOf(v);
    }

    private static int intArg(String[] args, String prefix, int fallback) {
        String v = stringArg(args, prefix);
        try {
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String stringArg(String[] args, String prefix) {
        for (String a : args) {
            if (a != null && a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return null;
    }
}
