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

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The browser invoker's configured timeout (ASKAI_BROWSER_TOOL_TIMEOUT_SECONDS, default 300) must cover the
 * WHOLE blocking call, on the HTTP socket too. Solon's {@code requestTimeout} alone leaves the HTTP read
 * timeout at its 30 s default: a web_search_prepare over three Bing result pages (~30 s on Camoufox) then
 * died with "SocketTimeoutException: Read timed out" and the manual search ended as a technical failure
 * (MCP_UNAVAILABLE) although the sidecar was alive and still browsing. The slow call below deliberately
 * exceeds those 30 s.
 */
public class SolonToolInvokerTimeoutTest {

    private static final long OVER_THE_HTTP_DEFAULT_MILLIS = 31_500L;

    private static SolonMcpServerRuntime runtime;

    @AfterClass
    public static void shutdown() {
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    /** {@code slow(millis)}: blocks the handler for the given time, then answers "slept <millis>". */
    private static McpToolContribution slowTool() {
        return McpToolContribution.of("slow", "Sleeps for millis, then answers.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        long millis = call.getInteger("millis", 0L);
                        try {
                            Thread.sleep(millis);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return McpToolResult.error("interrupted");
                        }
                        return McpToolResult.ok("slept " + millis);
                    }
                },
                McpToolParameter.string("millis", true, "How long to sleep (ms)"));
    }

    private static String endpoint() {
        if (runtime == null) {
            runtime = new SolonMcpServerRuntime();
        }
        McpEndpointHandle handle = runtime.registerEndpoint(
                new McpEndpointDefinition("slow-browser", "Slow browser"));
        runtime.updateTools(handle, Collections.singletonList(slowTool()));
        return runtime.endpointUrl(handle);
    }

    @Test
    public void aCallLongerThanSolonsHttpDefaultSucceedsWithinTheConfiguredTimeout() throws Exception {
        SolonToolInvoker invoker = new SolonToolInvoker(endpoint(), "streamable", 90);
        long started = System.nanoTime();
        try {
            String answer = invoker.call("slow",
                    Collections.<String, Object>singletonMap("millis", OVER_THE_HTTP_DEFAULT_MILLIS));
            assertEquals("slept " + OVER_THE_HTTP_DEFAULT_MILLIS, answer);
        } catch (ToolInvoker.EndpointUnavailable ex) {
            fail("a " + OVER_THE_HTTP_DEFAULT_MILLIS + " ms call under a 90 s timeout was read as a dead "
                    + "endpoint after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + " ms: " + ex.getMessage());
        } finally {
            invoker.close();
        }
        assertTrue("the handler really slept", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                >= OVER_THE_HTTP_DEFAULT_MILLIS);
    }

    @Test
    public void theConfiguredTimeoutStillBoundsTheCallAsEndpointUnavailable() throws Exception {
        SolonToolInvoker invoker = new SolonToolInvoker(endpoint(), "streamable", 2);
        long started = System.nanoTime();
        try {
            invoker.call("slow", Collections.<String, Object>singletonMap("millis", 8_000L));
            fail("a 8 s call must not survive a 2 s timeout");
        } catch (ToolInvoker.EndpointUnavailable expected) {
            // the timeout is the dead-endpoint verdict the loop knows how to handle
        } finally {
            invoker.close();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("the timeout fired, not the handler's end (" + elapsedMillis + " ms)",
                elapsedMillis < 8_000L);
    }
}
