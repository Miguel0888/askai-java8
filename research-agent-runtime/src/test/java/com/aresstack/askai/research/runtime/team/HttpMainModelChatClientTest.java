package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.inference.InferenceEndpointDescriptor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The productive multi-message {@code /api/chat} main-model client against a mock Ollama endpoint: a valid
 * assistant message becomes OK with its text and the request carries the descriptor's model and the full
 * message history; malformed / content-less / non-2xx responses each become the correct typed failure — never
 * a fabricated success. This is the seam that carries the user's selected chat model (e.g. gemma) to the wire.
 */
public class HttpMainModelChatClientTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static List<ChatMessage> conversation() {
        return Arrays.asList(
                ChatMessage.system("You are the research team agent."),
                ChatMessage.user("I want to research electric cars."),
                ChatMessage.assistant("Understood. Any focus?"),
                ChatMessage.user("Battery tech."));
    }

    private MainModelChatResult callWith(String model, int status, String body,
                                         AtomicReference<String> seenBody) throws Exception {
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
            InferenceEndpointDescriptor descriptor = new InferenceEndpointDescriptor(model,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "/api/chat", 5000L);
            return new HttpMainModelChatClient(descriptor).complete(conversation(), 0.4, 512);
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
    public void aValidAnswerIsOkAndTheRequestCarriesTheModelAndFullHistory() throws Exception {
        AtomicReference<String> seen = new AtomicReference<String>();
        MainModelChatResult result = callWith("gemma4:e2b", 200,
                "{\"model\":\"gemma4:e2b\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Here is your outline.\"},\"done\":true}", seen);
        assertEquals(MainModelChatResult.Status.OK, result.getStatus());
        assertEquals("Here is your outline.", result.getText());
        // The EXACT selected model reaches the wire, non-streaming, with system + both user turns + assistant.
        assertTrue(seen.get(), seen.get().contains("\"model\":\"gemma4:e2b\""));
        assertTrue(seen.get(), seen.get().contains("\"stream\":false"));
        assertTrue(seen.get(), seen.get().contains("\"role\":\"system\""));
        assertTrue(seen.get(), seen.get().contains("\"role\":\"assistant\""));
        assertTrue(seen.get(), seen.get().contains("Battery tech."));
    }

    @Test
    public void theModelNameIsReported() {
        InferenceEndpointDescriptor descriptor =
                new InferenceEndpointDescriptor("gemma4:e2b", "http://127.0.0.1:1", "/api/chat", 5000L);
        assertEquals("gemma4:e2b", new HttpMainModelChatClient(descriptor).modelName());
    }

    @Test
    public void malformedJsonIsInvalidResponseNeverOk() throws Exception {
        MainModelChatResult result = callWith("m", 200, "{not json", null);
        assertEquals(MainModelChatResult.Status.INVALID_RESPONSE, result.getStatus());
        assertFalse(result.isOk());
    }

    @Test
    public void aResponseWithoutMessageContentIsInvalidResponse() throws Exception {
        MainModelChatResult result = callWith("m", 200,
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\"},\"done\":true}", null);
        assertEquals(MainModelChatResult.Status.INVALID_RESPONSE, result.getStatus());
    }

    @Test
    public void anHttpErrorIsProviderFailure() throws Exception {
        MainModelChatResult result = callWith("m", 500, "boom", null);
        assertEquals(MainModelChatResult.Status.PROVIDER_FAILURE, result.getStatus());
    }

    @Test
    public void emptyMessagesNeverCallTheEndpoint() {
        InferenceEndpointDescriptor descriptor =
                new InferenceEndpointDescriptor("m", "http://127.0.0.1:1", "/api/chat", 5000L);
        MainModelChatResult result = new HttpMainModelChatClient(descriptor)
                .complete(java.util.Collections.<ChatMessage>emptyList(), 0.0, 0);
        assertEquals(MainModelChatResult.Status.INVALID_RESPONSE, result.getStatus());
    }
}
