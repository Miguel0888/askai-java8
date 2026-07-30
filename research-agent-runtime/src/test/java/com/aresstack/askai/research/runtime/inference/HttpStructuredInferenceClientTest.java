package com.aresstack.askai.research.runtime.inference;

import com.aresstack.askai.agent.model.inference.InferenceEndpointDescriptor;
import com.aresstack.askai.browser.search.ReasoningEffort;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.inference.StructuredInferenceRequest;
import com.aresstack.askai.browser.search.inference.StructuredInferenceResult;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The productive /api/chat structured-inference client against a mock Ollama-compatible endpoint: a valid
 * assistant message becomes SUCCESS with its text; malformed / content-less / non-2xx responses each become
 * the correct typed failure — never a fabricated success.
 */
public class HttpStructuredInferenceClientTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static StructuredInferenceRequest request() {
        return new StructuredInferenceRequest("profile-ignored", "system prompt", "user prompt",
                256, 0.0, ReasoningEffort.DEFAULT, 1, CancellationSignal.NONE);
    }

    /** Serve one fixed (status, body) at /api/chat, run the client, capture the request body seen. */
    private StructuredInferenceResult callWith(int status, String body, AtomicReference<String> seenBody)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                byte[] in = readAll(exchange);
                if (seenBody != null) {
                    seenBody.set(new String(in, UTF_8));
                }
                byte[] out = body.getBytes(UTF_8);
                exchange.sendResponseHeaders(status, out.length);
                OutputStream os = exchange.getResponseBody();
                os.write(out);
                os.close();
            }
        });
        server.start();
        try {
            InferenceEndpointDescriptor descriptor = new InferenceEndpointDescriptor("local/m:latest",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "/api/chat", 5000L);
            return new HttpStructuredInferenceClient(descriptor).execute(request());
        } finally {
            server.stop(0);
        }
    }

    private static byte[] readAll(HttpExchange exchange) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = exchange.getRequestBody().read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    @Test
    public void aValidAssistantMessageBecomesSuccessWithItsText() throws Exception {
        AtomicReference<String> seen = new AtomicReference<String>();
        StructuredInferenceResult result = callWith(200,
                "{\"model\":\"local/m:latest\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"{\\\"organicContainerId\\\":\\\"c-7\\\"}\"},\"done\":true}", seen);
        assertEquals(StructuredInferenceStatus.SUCCESS, result.status);
        assertEquals("{\"organicContainerId\":\"c-7\"}", result.rawText);
        // The request carried the descriptor model, a non-streaming flag and both messages.
        assertTrue(seen.get(), seen.get().contains("\"model\":\"local/m:latest\""));
        assertTrue(seen.get(), seen.get().contains("\"stream\":false"));
        assertTrue(seen.get(), seen.get().contains("\"role\":\"user\""));
    }

    @Test
    public void malformedJsonIsInvalidResponseNeverSuccess() throws Exception {
        StructuredInferenceResult result = callWith(200, "{not json", null);
        assertEquals(StructuredInferenceStatus.INVALID_RESPONSE, result.status);
    }

    @Test
    public void aResponseWithoutMessageContentIsInvalidResponse() throws Exception {
        StructuredInferenceResult result = callWith(200,
                "{\"model\":\"local/m:latest\",\"message\":{\"role\":\"assistant\"},\"done\":true}", null);
        assertEquals(StructuredInferenceStatus.INVALID_RESPONSE, result.status);
    }

    @Test
    public void anHttpErrorIsProviderFailure() throws Exception {
        StructuredInferenceResult result = callWith(500, "boom", null);
        assertEquals(StructuredInferenceStatus.PROVIDER_FAILURE, result.status);
    }

    @Test
    public void aCancelledRequestIsNeverCalled() {
        InferenceEndpointDescriptor descriptor = new InferenceEndpointDescriptor("local/m:latest",
                "http://127.0.0.1:1", "/api/chat", 5000L);
        StructuredInferenceRequest cancelled = new StructuredInferenceRequest("p", "s", "u", 256, 0.0,
                ReasoningEffort.DEFAULT, 1, new CancellationSignal() {
                    public boolean isCancelled() {
                        return true;
                    }
                });
        StructuredInferenceResult result = new HttpStructuredInferenceClient(descriptor).execute(cancelled);
        assertEquals(StructuredInferenceStatus.CANCELLED, result.status);
    }
}
