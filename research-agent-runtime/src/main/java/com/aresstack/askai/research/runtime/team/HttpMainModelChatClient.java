package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.inference.InferenceEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.MiniJson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * The productive {@link MainModelChat}: a THIN HTTP adapter over the Ollama-compatible {@code /api/chat}
 * contract at the host-published inference endpoint (the centrally selected AskAI main/chat model). Unlike the
 * SERP-repair {@code HttpStructuredInferenceClient} it sends the FULL multi-message conversation (a system
 * message plus the alternating user/assistant history), non-streaming, reads {@code message.content}, and maps
 * transport/timeout/HTTP/empty-answer onto the typed {@link MainModelChatResult.Status} — never a fabricated
 * success. The model and endpoint come entirely from the descriptor AskAI wrote.
 */
public final class HttpMainModelChatClient implements MainModelChat {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final InferenceEndpointDescriptor descriptor;
    /** The connection of the call in flight (if any), so a cancel/close can abort it promptly. */
    private final java.util.concurrent.atomic.AtomicReference<HttpURLConnection> inFlight =
            new java.util.concurrent.atomic.AtomicReference<HttpURLConnection>();
    /** Set by {@link #cancelInFlight()}; reset at the start of each call so it is never sticky. */
    private volatile boolean cancelled;

    public HttpMainModelChatClient(InferenceEndpointDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        this.descriptor = descriptor;
    }

    /**
     * Abort the call in flight (if any) so a session/tab close or a pause/cancel returns promptly instead of
     * waiting out the full model timeout. The aborted call surfaces as an honest non-OK result, never a
     * fabricated answer. Safe to call when no call is in flight.
     */
    public void cancelInFlight() {
        cancelled = true;
        HttpURLConnection connection = inFlight.get();
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (RuntimeException ignored) {
                // best-effort abort; the blocked read then fails and the call returns non-OK
            }
        }
    }

    @Override
    public String modelName() {
        return descriptor.model;
    }

    @Override
    public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        if (messages == null || messages.isEmpty()) {
            return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "no messages to send");
        }
        cancelled = false; // per-call: a previous cancel never poisons a fresh turn after a client swap
        String body = buildChatBody(messages, temperature, maxOutputTokens);
        String responseJson;
        try {
            responseJson = post(body);
        } catch (SocketTimeoutException timeout) {
            return MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT,
                    "main-model call timed out after " + descriptor.timeoutMillis + "ms");
        } catch (IOException transport) {
            return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                    "main-model call failed: " + transport.getMessage());
        }
        return extractAssistantText(responseJson);
    }

    @SuppressWarnings("unchecked")
    private MainModelChatResult extractAssistantText(String responseJson) {
        Object root;
        try {
            root = MiniJson.parse(responseJson);
        } catch (MiniJson.JsonParseException malformed) {
            return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "main-model response was not valid JSON: " + malformed.getMessage());
        }
        if (!(root instanceof Map)) {
            return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "main-model response root is not a JSON object");
        }
        Object message = ((Map<String, Object>) root).get("message");
        if (!(message instanceof Map)) {
            return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "main-model response has no message object");
        }
        Object content = ((Map<String, Object>) message).get("content");
        if (!(content instanceof String) || ((String) content).trim().isEmpty()) {
            // A thinking model that spent the whole answer on reasoning is a different failure from a
            // model that returned nothing. Both used to read as "no text content", which is exactly the
            // ambiguity that made the review failure undiagnosable.
            Object thinking = ((Map<String, Object>) message).get("thinking");
            if (thinking instanceof String && !((String) thinking).trim().isEmpty()) {
                return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                        "main-model response carried only reasoning, no answer ("
                                + ((String) thinking).trim().length() + " reasoning chars)");
            }
            return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "main-model response message has no text content");
        }
        return MainModelChatResult.ok((String) content);
    }

    /** Build a non-streaming /api/chat body with the full message history + options. */
    private String buildChatBody(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"model\":\"").append(escape(descriptor.model)).append("\",");
        sb.append("\"stream\":false,");
        sb.append("\"options\":{\"temperature\":").append(temperature);
        if (maxOutputTokens > 0) {
            sb.append(",\"num_predict\":").append(maxOutputTokens);
        }
        sb.append("},");
        sb.append("\"messages\":[");
        boolean first = true;
        for (ChatMessage message : messages) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"role\":\"").append(message.getRole().wire()).append("\",\"content\":\"")
                    .append(escape(message.getContent())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String post(String requestBody) throws IOException {
        URL url = new URL(joinUrl(descriptor.baseUrl, descriptor.chatPath));
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            inFlight.set(connection);
            if (cancelled) {
                throw new IOException("cancelled before sending");
            }
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            int timeout = (int) Math.min(Integer.MAX_VALUE, descriptor.timeoutMillis);
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);

            OutputStream out = connection.getOutputStream();
            try {
                out.write(requestBody.getBytes(UTF_8));
                out.flush();
            } finally {
                out.close();
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String errorBody = readAll(connection.getErrorStream());
                throw new IOException("main-model endpoint returned HTTP " + status
                        + (errorBody.isEmpty() ? "" : ": " + errorBody));
            }
            return readAll(connection.getInputStream());
        } finally {
            if (connection != null) {
                inFlight.compareAndSet(connection, null);
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
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

    private static String joinUrl(String baseUrl, String path) {
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl + path.substring(1);
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    private static String escape(String value) {
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
