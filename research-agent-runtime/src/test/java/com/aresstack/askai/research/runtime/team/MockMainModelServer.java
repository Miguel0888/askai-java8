package com.aresstack.askai.research.runtime.team;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reusable mock of the Ollama-compatible {@code /api/chat} endpoint for driving the model-backed TeamAgent
 * end to end without a real model. It records every request (the {@code model} and the full message history),
 * answers scripted structured JSON turns in order, and can BLOCK a response on a latch so a cancel-on-close
 * test can prove the close does not wait for the full model timeout.
 */
public final class MockMainModelServer implements AutoCloseable {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpServer server;
    private final List<Recorded> requests =
            java.util.Collections.synchronizedList(new ArrayList<Recorded>());
    private final ConcurrentLinkedDeque<String> scripted = new ConcurrentLinkedDeque<String>();
    /** When non-null, each handler awaits this latch before answering (a blocking/slow model). */
    private volatile CountDownLatch gate;

    public MockMainModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", new Handler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Queue one raw model answer — the {@code content} the TeamAgentTurnParser will parse (a JSON turn). */
    public void enqueueRaw(String rawContent) {
        scripted.add(rawContent == null ? "" : rawContent);
    }

    /** Queue a minimal assistant-only turn. */
    public void enqueueMessage(String assistantMessage) {
        enqueueRaw("{\"assistantMessage\":\"" + jsonEscape(assistantMessage) + "\"}");
    }

    /** Queue a scope-proposing turn: an assistant message + a proposed command + the scope to confirm. */
    public void enqueueScopeProposal(String assistantMessage, String command, String question,
                                     List<String> aspects) {
        StringBuilder sb = new StringBuilder("{\"assistantMessage\":\"")
                .append(jsonEscape(assistantMessage)).append("\",\"proposedCommand\":\"")
                .append(jsonEscape(command)).append("\",\"scope\":{\"question\":\"")
                .append(jsonEscape(question)).append("\",\"aspects\":[");
        for (int i = 0; i < aspects.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(jsonEscape(aspects.get(i))).append('"');
        }
        sb.append("]}}");
        enqueueRaw(sb.toString());
    }

    /** Install a gate that blocks every subsequent response until the latch is counted down. */
    public void blockResponses(CountDownLatch latch) {
        this.gate = latch;
    }

    public List<Recorded> requests() {
        synchronized (requests) {
            return new ArrayList<Recorded>(requests);
        }
    }

    public boolean sawModel(String model) {
        for (Recorded r : requests()) {
            if (model.equals(r.model)) {
                return true;
            }
        }
        return false;
    }

    /** Write an {@code inference-config.json} pointing ASKAI at this server, and return the file. */
    public File writeInferenceConfig(File file, String model) throws IOException {
        String json = "{\"formatVersion\":1,\"configurationRevision\":1,\"model\":\"" + jsonEscape(model)
                + "\",\"baseUrl\":\"" + baseUrl() + "\",\"chatPath\":\"/api/chat\",\"timeoutMillis\":120000}";
        Files.write(file.toPath(), json.getBytes(UTF_8));
        return file;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ internals

    public static final class Recorded {
        public final String model;
        public final String body;

        Recorded(String model, String body) {
            this.model = model;
            this.body = body;
        }

        /** @return whether the recorded message history contains this substring anywhere. */
        public boolean historyContains(String needle) {
            return body.contains(needle);
        }
    }

    private final class Handler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String body = readAll(exchange.getRequestBody());
            Matcher m = MODEL.matcher(body);
            requests.add(new Recorded(m.find() ? m.group(1) : "", body));
            CountDownLatch latch = gate;
            if (latch != null) {
                try {
                    latch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            String content = scripted.isEmpty() ? "{\"assistantMessage\":\"(no scripted turn)\"}"
                    : scripted.poll();
            String response = "{\"message\":{\"role\":\"assistant\",\"content\":\""
                    + jsonEscape(content) + "\"}}";
            byte[] out = response.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            try {
                exchange.sendResponseHeaders(200, out.length);
                OutputStream os = exchange.getResponseBody();
                os.write(out);
                os.close();
            } catch (IOException closedByPeer) {
                // The client (a closing session) may have dropped the connection — that is exactly the
                // cancel-on-close path; nothing to do.
            } finally {
                exchange.close();
            }
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        try {
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        } finally {
            stream.close();
        }
        return new String(buffer.toByteArray(), UTF_8);
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
