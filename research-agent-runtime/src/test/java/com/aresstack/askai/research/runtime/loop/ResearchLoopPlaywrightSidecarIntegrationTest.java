package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.mcp.solon.SolonMcpServerRuntime;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.capture.ResearchSearchIndex;
import com.aresstack.askai.research.capture.SourceAcceptanceService;
import com.aresstack.askai.research.capture.VisitedCapture;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * 36C: the UNCHANGED {@link ResearchLoop} with the UNCHANGED {@link SolonToolInvoker} runs against the LIVE
 * Playwright4j sidecar — a separate Java-21 process (the fat sidecarJar) driving a real locally installed
 * Chromium-channel browser. Two local HTTP servers (two distinct hosts by port) serve pages whose text and
 * links exist ONLY after JavaScript execution, so a static fetch cannot produce this run. Between loop and
 * sidecar sits a Java-8 browser BRIDGE endpoint that adds the {@code capture_id} convention by recording
 * every {@code web_open} into the PRODUCTIVE Commit-37 {@link CaptureStore}; the research endpoint applies
 * the PRODUCTIVE {@link SourceAcceptanceService} (repository + index, real dedup, real result contract).
 * This bridge is exactly the host-side glue Commit 38 wires into the plugin; the loop and the invoker
 * contain no Playwright special case whatsoever. No fallback: if the sidecar does not report READY, the
 * test SKIPS with the sidecar's own status line — it never swaps in STATIC_HTTP.
 */
public class ResearchLoopPlaywrightSidecarIntegrationTest {

    private static final String PAGE_TEMPLATE = "<!doctype html><html><head><title>%TITLE%</title></head>"
            + "<body><div id='c'></div><script>"
            + "document.getElementById('c').textContent='%TEXT% Rendered by JavaScript.';"
            + "%LINKS%"
            + "</script></body></html>";

    @Test
    public void unchangedLoopReachesSufficientEvidenceThroughTheLiveSidecar() throws Exception {
        String sidecarJar = System.getProperty("browser.sidecar.jar", "");
        String sidecarJava = System.getProperty("sidecar.java", "");
        assumeTrue("SKIPPED: sidecar jar not built", !sidecarJar.isEmpty() && new File(sidecarJar).isFile());
        assumeTrue("SKIPPED: no Java 21 toolchain available for the sidecar",
                !sidecarJava.isEmpty() && new File(sidecarJava).isFile());

        // ---- two local JS servers = two distinct hosts (host = authority incl. port) ----
        HttpServer serverOne = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer serverTwo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseOne = "http://127.0.0.1:" + serverOne.getAddress().getPort();
        String baseTwo = "http://127.0.0.1:" + serverTwo.getAddress().getPort();

        serverOne.createContext("/find", page("Find", "search results.",
                jsLink("PF4J primer", baseOne + "/a")));
        serverOne.createContext("/a", page("PF4J primer",
                "pf4j is a plugin framework. Primary source.",
                jsLink("pf4j details", baseTwo + "/c")));
        serverTwo.createContext("/c", page("Independent pf4j review",
                "pf4j works well with java 8.",
                jsLink("pf4j extra evidence", baseTwo + "/e")));
        serverTwo.createContext("/e", page("pf4j in production",
                "More pf4j evidence from the field.", ""));
        serverOne.start();
        serverTwo.start();

        // ---- the LIVE sidecar process (Java 21) ----
        int sidecarPort = freePort();
        String token = "t-" + UUID.randomUUID();
        Process sidecar = new ProcessBuilder(sidecarJava, "-jar", sidecarJar,
                "--port=" + sidecarPort, "--token=" + token,
                "--allow-private=true", "--headless=true",
                "--search-url=" + baseOne + "/find?q={query}")
                .redirectErrorStream(false)
                .start();
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<String> readinessLine = new AtomicReference<String>();
        Thread stderrDrain = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(sidecar.getErrorStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.err.println("[sidecar] " + line);
                        if (line.contains("playwright readiness:")) {
                            readinessLine.set(line);
                        }
                        if (line.contains("ready on 127.0.0.1:")) {
                            ready.countDown();
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }, "sidecar-stderr-drain");
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        SolonMcpServerRuntime runtime = new SolonMcpServerRuntime();
        SolonToolInvoker sidecarClient = null;
        SolonToolInvoker browser = null;
        SolonToolInvoker research = null;
        McpEndpointHandle bridgeHandle = null;
        McpEndpointHandle researchHandle = null;
        try {
            assumeTrue("SKIPPED: sidecar did not come up within 120s", ready.await(120, TimeUnit.SECONDS));
            String readiness = readinessLine.get();
            assumeTrue("SKIPPED (environment-gated): " + readiness,
                    readiness != null && readiness.contains("READY"));

            String sidecarUrl = "http://127.0.0.1:" + sidecarPort + "/mcp/browser/" + token;
            sidecarClient = new SolonToolInvoker(sidecarUrl, "streamable");

            // ---- PRODUCTIVE Commit-37 lifecycle behind the research endpoint ----
            final CaptureStore captures = new CaptureStore(100);
            final InMemoryResearchSourceRepository repository = InMemoryResearchSourceRepository.empty();
            final ResearchSearchIndex.InMemory index = new ResearchSearchIndex.InMemory();
            final SourceAcceptanceService acceptance = new SourceAcceptanceService(captures, repository,
                    new SourceAcceptanceService.SourceCreator() {
                        public void create(ResearchSourceRecord record) {
                            repository.put(record);
                        }
                    }, index);
            final List<String> findings = new ArrayList<String>();

            // ---- browser BRIDGE endpoint: sidecar delegation + capture_id convention (host-side glue) ----
            final SolonToolInvoker toSidecar = sidecarClient;
            bridgeHandle = runtime.registerEndpoint(new McpEndpointDefinition("browser.bridge36c", "Browser"));
            runtime.updateTools(bridgeHandle, Arrays.asList(
                    McpToolContribution.of("web_search", "search", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            return delegate(toSidecar, "web_search", call.getArguments());
                        }
                    }, McpToolParameter.string("query", true, "q")),
                    McpToolContribution.of("web_open", "open", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            McpToolResult raw = delegate(toSidecar, "web_open", call.getArguments());
                            if (raw.isError()) {
                                return raw;
                            }
                            String[] parsed = parseSidecarPage(raw.getText());
                            VisitedCapture capture = captures.record(parsed[0], parsed[1], parsed[2]);
                            return McpToolResult.ok("URL: " + parsed[0] + " title=\"" + parsed[1]
                                    + "\" capture_id=" + capture.getCaptureId() + "\n" + parsed[2]);
                        }
                    }, McpToolParameter.string("url", true, "u")),
                    McpToolContribution.of("web_links", "links", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            return delegate(toSidecar, "web_links", call.getArguments());
                        }
                    })));

            // ---- research endpoint on the productive acceptance service ----
            researchHandle = runtime.registerEndpoint(
                    new McpEndpointDefinition("research.itest36c", "Research"));
            runtime.updateTools(researchHandle, Arrays.asList(
                    McpToolContribution.of("source_accept", "accept", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            return McpToolResult.ok(
                                    acceptance.accept(call.getString("capture_id")).render());
                        }
                    }, McpToolParameter.string("capture_id", true, "c")),
                    McpToolContribution.of("finding_add", "finding", new McpToolHandler() {
                        public McpToolResult invoke(McpToolCall call) {
                            String sourceId = call.getString("source_id");
                            if (repository.get(sourceId) == null) {
                                return McpToolResult.error("Unknown source: " + sourceId);
                            }
                            findings.add(sourceId + ": " + call.getString("text"));
                            return McpToolResult.ok("appended revision=1");
                        }
                    }, McpToolParameter.string("source_id", true, "s"),
                       McpToolParameter.string("text", true, "t"))));

            // ---- the IDENTICAL loop + IDENTICAL invoker as 36A — no Playwright special case ----
            browser = new SolonToolInvoker(runtime.endpointUrl(bridgeHandle), "streamable");
            research = new SolonToolInvoker(runtime.endpointUrl(researchHandle), "streamable");
            final AtomicLong now = new AtomicLong(0);
            final List<ResearchStopReason> phaseReady = new ArrayList<ResearchStopReason>();
            ResearchLoop loop = new ResearchLoop(browser, research,
                    new ResearchRunBudget(30, 20, 8, 3, 600_000, 2, 2),
                    new ResearchLoopClock() {
                        public long currentTimeMillis() {
                            return now.get();
                        }

                        public void sleepMillis(long millis) {
                            now.addAndGet(millis);
                        }
                    },
                    new ResearchLoopListener() {
                        public void status(String message) {
                        }

                        public void progress(ResearchRunProgress progress, ResearchRunActivity activity) {
                        }

                        public void phaseReady(ResearchStopReason reason) {
                            phaseReady.add(reason);
                        }

                        public void attention(String reason, String domainFamily, String url,
                                              boolean resolved) {
                        }
                    }, new AtomicBoolean(false));
            ResearchStopReason reason = loop.run("investigate pf4j plugin framework");

            assertEquals(ResearchStopReason.SUFFICIENT_EVIDENCE, reason);
            assertTrue("three JS pages visited", loop.getProgress().getPagesVisited() >= 3);
            assertTrue("two sources accepted via the productive service",
                    loop.getProgress().getAcceptedSources() >= 2);
            assertEquals("two distinct hosts (two local servers)",
                    2, loop.getProgress().getDistinctHosts().size());
            assertTrue("at least one finding referencing a real source", findings.size() >= 1);
            for (String finding : findings) {
                String sourceId = finding.substring(0, finding.indexOf(':'));
                assertNotNull("finding must reference a committed source", repository.get(sourceId));
            }
            assertEquals(1, phaseReady.size());
            // The decisive Playwright proof: every capture text exists ONLY after JS execution.
            assertTrue(captures.size() >= 3);
            for (VisitedCapture capture : captures.list()) {
                assertTrue("capture text must be JS-rendered: " + capture.getCanonicalUrl(),
                        capture.getText().contains("Rendered by JavaScript"));
            }
        } finally {
            close(browser);
            close(research);
            close(sidecarClient);
            if (bridgeHandle != null) {
                runtime.unregisterEndpoint(bridgeHandle);
            }
            if (researchHandle != null) {
                runtime.unregisterEndpoint(researchHandle);
            }
            runtime.shutdown();
            sidecar.destroy();
            if (!sidecar.waitFor(15, TimeUnit.SECONDS)) {
                sidecar.destroyForcibly();
                sidecar.waitFor(15, TimeUnit.SECONDS);
            }
            serverOne.stop(0);
            serverTwo.stop(0);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Delegate a tool call to the live sidecar; its failures surface as readable tool errors. */
    private static McpToolResult delegate(SolonToolInvoker sidecar, String tool, Map<String, Object> args) {
        try {
            return McpToolResult.ok(sidecar.call(tool, args));
        } catch (ToolInvoker.ToolFailure ex) {
            return McpToolResult.error(ex.getMessage());
        } catch (ToolInvoker.EndpointUnavailable ex) {
            return McpToolResult.error("Sidecar unavailable: " + ex.getMessage());
        }
    }

    /** Parse the sidecar's render format ("URL: u\nTITLE: t\n\ntext") into {url, title, text}. */
    static String[] parseSidecarPage(String rendered) {
        String url = "";
        String title = "";
        StringBuilder text = new StringBuilder();
        boolean inText = false;
        for (String line : rendered.split("\n", -1)) {
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

    private static HttpHandler page(String title, String text, String linkScript) {
        final String html = PAGE_TEMPLATE.replace("%TITLE%", title)
                .replace("%TEXT%", text).replace("%LINKS%", linkScript);
        return new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                OutputStream out = exchange.getResponseBody();
                out.write(body);
                exchange.close();
            }
        };
    }

    private static String jsLink(String text, String href) {
        return "var a=document.createElement('a');a.href='" + href + "';a.textContent='" + text
                + "';document.body.appendChild(a);";
    }

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static void close(SolonToolInvoker invoker) {
        if (invoker != null) {
            try {
                invoker.close();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
