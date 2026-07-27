package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.mcp.solon.SolonMcpServerRuntime;

import org.junit.AfterClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Leitplanke 2: the deterministic browser is a REAL MCP endpoint. The loop runs through real Solon MCP
 * clients (SolonToolInvoker) against real streamable endpoints — tool discovery, schemas, serialization and
 * results are exercised end-to-end; only the page contents are fixtures. The research endpoint applies the
 * Commit-37 result contract server-side.
 */
public class ResearchLoopMcpIntegrationTest {

    private static SolonMcpServerRuntime runtime;

    @AfterClass
    public static void shutdown() {
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    @Test
    public void loopRunsAgainstRealMcpEndpointsAndReachesSufficientEvidence() throws Exception {
        if (runtime == null) {
            runtime = new SolonMcpServerRuntime();
        }
        // ---- deterministic browser endpoint (fixture pages, capture ids in results) ----
        final Map<String, String[]> pages = new LinkedHashMap<String, String[]>(); // url -> {title, text, links}
        pages.put("https://host1.com/a", new String[]{"PF4J primer",
                "pf4j is a plugin framework. Primary source.",
                "pf4j details — https://host2.net/c"});
        pages.put("https://host2.net/c", new String[]{"Independent pf4j review",
                "pf4j works well with java 8.",
                "pf4j extra evidence — https://host2.net/e"});
        pages.put("https://host2.net/e", new String[]{"pf4j in production",
                "More pf4j evidence from the field.", ""});
        final Map<String, String> captureByUrl = new LinkedHashMap<String, String>();
        final AtomicInteger capSeq = new AtomicInteger();
        final String[] current = {null};

        McpEndpointHandle browserHandle = runtime.registerEndpoint(
                new McpEndpointDefinition("browser.itest", "Browser"));
        List<McpToolContribution> browserTools = new ArrayList<McpToolContribution>();
        browserTools.add(McpToolContribution.of("web_search", "search", new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                return McpToolResult.ok("1: PF4J primer — https://host1.com/a");
            }
        }, McpToolParameter.string("query", true, "q")));
        browserTools.add(McpToolContribution.of("web_open", "open", new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                String url = call.getString("url");
                String[] page = pages.get(url);
                if (page == null) {
                    return McpToolResult.error("404 " + url);
                }
                current[0] = url;
                String cap = captureByUrl.get(url);
                if (cap == null) {
                    cap = "cap-" + capSeq.incrementAndGet();
                    captureByUrl.put(url, cap);
                }
                return McpToolResult.ok("URL: " + url + " title=\"" + page[0]
                        + "\" capture_id=" + cap + "\n" + page[1]);
            }
        }, McpToolParameter.string("url", true, "u")));
        browserTools.add(McpToolContribution.of("web_links", "links", new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                String[] page = current[0] == null ? null : pages.get(current[0]);
                return McpToolResult.ok(page == null || page[2].isEmpty() ? "" : page[2]);
            }
        }));
        runtime.updateTools(browserHandle, browserTools);

        // ---- research endpoint honoring the Commit-37 contract ----
        final List<String> findings = new ArrayList<String>();
        final Map<String, String> sourceByCapture = new LinkedHashMap<String, String>();
        final AtomicInteger srcSeq = new AtomicInteger();
        McpEndpointHandle researchHandle = runtime.registerEndpoint(
                new McpEndpointDefinition("research.itest36a", "Research"));
        runtime.updateTools(researchHandle, Arrays.asList(
                McpToolContribution.of("source_accept", "accept", new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String cap = call.getString("capture_id");
                        String already = sourceByCapture.get(cap);
                        if (already != null) {
                            return McpToolResult.ok("status=ALREADY_ACCEPTED source_id=" + already
                                    + " duplicate=false");
                        }
                        String id = "source-" + srcSeq.incrementAndGet();
                        sourceByCapture.put(cap, id);
                        return McpToolResult.ok("status=ACCEPTED source_id=" + id
                                + " title=\"t\" passage_count=1 duplicate=false");
                    }
                }, McpToolParameter.string("capture_id", true, "c")),
                McpToolContribution.of("finding_add", "finding", new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        findings.add(call.getString("source_id") + ": " + call.getString("text"));
                        return McpToolResult.ok("appended revision=1");
                    }
                }, McpToolParameter.string("source_id", true, "s"),
                   McpToolParameter.string("text", true, "t"))));

        // ---- the loop over REAL Solon MCP clients ----
        SolonToolInvoker browser = new SolonToolInvoker(runtime.endpointUrl(browserHandle), "streamable");
        SolonToolInvoker research = new SolonToolInvoker(runtime.endpointUrl(researchHandle), "streamable");
        final AtomicLong now = new AtomicLong(0);
        final List<ResearchStopReason> ready = new ArrayList<ResearchStopReason>();
        try {
            ResearchLoop loop = new ResearchLoop(browser, research,
                    new ResearchRunBudget(30, 20, 8, 3, 600_000, 2, 2),
                    new ResearchLoopClock() {
                        public long currentTimeMillis() {
                            return now.get();
                        }
                    },
                    new ResearchLoopListener() {
                        public void status(String message) {
                        }

                        public void phaseReady(ResearchStopReason reason) {
                            ready.add(reason);
                        }
                    }, new AtomicBoolean(false));
            ResearchStopReason reason = loop.run("investigate pf4j");
            assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, reason);
            assertTrue(loop.getProgress().getPagesVisited() >= 3);
            assertTrue(loop.getProgress().getAcceptedSources() >= 2);
            assertTrue(loop.getProgress().getDistinctHosts().size() >= 2);
            assertTrue(findings.size() >= 1);
            assertEquals(1, ready.size());
        } finally {
            browser.close();
            research.close();
            runtime.unregisterEndpoint(browserHandle);
            runtime.unregisterEndpoint(researchHandle);
        }
    }
}
