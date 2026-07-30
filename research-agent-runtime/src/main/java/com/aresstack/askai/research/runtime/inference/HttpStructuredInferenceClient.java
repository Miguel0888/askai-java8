package com.aresstack.askai.research.runtime.inference;

import com.aresstack.askai.agent.model.inference.InferenceEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.MiniJson;
import com.aresstack.askai.browser.search.inference.StructuredInferencePort;
import com.aresstack.askai.browser.search.inference.StructuredInferenceRequest;
import com.aresstack.askai.browser.search.inference.StructuredInferenceResult;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * The productive {@link StructuredInferencePort}: a THIN HTTP adapter over the Ollama-compatible
 * {@code /api/chat} contract at the host-published endpoint (the central AskAI main model). It builds a
 * non-streaming chat request (system + user message), reads the assistant text from {@code message.content},
 * and maps transport/timeout/HTTP/empty-answer failures onto the typed {@link StructuredInferenceStatus}
 * — NEVER fabricating a success. The agent performs no model management: the model and endpoint come
 * entirely from the descriptor AskAI wrote.
 */
public final class HttpStructuredInferenceClient implements StructuredInferencePort {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final InferenceEndpointDescriptor descriptor;

    public HttpStructuredInferenceClient(InferenceEndpointDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        this.descriptor = descriptor;
    }

    @Override
    public StructuredInferenceResult execute(StructuredInferenceRequest request) {
        if (request.cancellationSignal != null && request.cancellationSignal.isCancelled()) {
            return StructuredInferenceResult.of(StructuredInferenceStatus.CANCELLED,
                    "cancelled before the inference call");
        }
        String body = buildChatBody(request);
        String responseJson;
        try {
            responseJson = post(body);
        } catch (SocketTimeoutException timeout) {
            return StructuredInferenceResult.of(StructuredInferenceStatus.TIMEOUT,
                    "inference call timed out after " + descriptor.timeoutMillis + "ms");
        } catch (IOException transport) {
            return StructuredInferenceResult.of(StructuredInferenceStatus.PROVIDER_FAILURE,
                    "inference call failed: " + transport.getMessage());
        }
        return extractAssistantText(responseJson);
    }

    @SuppressWarnings("unchecked")
    private StructuredInferenceResult extractAssistantText(String responseJson) {
        Object root;
        try {
            root = MiniJson.parse(responseJson);
        } catch (MiniJson.JsonParseException malformed) {
            return StructuredInferenceResult.of(StructuredInferenceStatus.INVALID_RESPONSE,
                    "inference response was not valid JSON: " + malformed.getMessage());
        }
        if (!(root instanceof Map)) {
            return StructuredInferenceResult.of(StructuredInferenceStatus.INVALID_RESPONSE,
                    "inference response root is not a JSON object");
        }
        Object message = ((Map<String, Object>) root).get("message");
        if (!(message instanceof Map)) {
            return StructuredInferenceResult.of(StructuredInferenceStatus.INVALID_RESPONSE,
                    "inference response has no message object");
        }
        Object content = ((Map<String, Object>) message).get("content");
        if (!(content instanceof String) || ((String) content).trim().isEmpty()) {
            return StructuredInferenceResult.of(StructuredInferenceStatus.INVALID_RESPONSE,
                    "inference response message has no text content");
        }
        return StructuredInferenceResult.success((String) content);
    }

    /** Build a non-streaming /api/chat body: system (when present) + user message, options + stream:false. */
    private String buildChatBody(StructuredInferenceRequest request) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"model\":\"").append(escape(descriptor.model)).append("\",");
        sb.append("\"stream\":false,");
        sb.append("\"options\":{\"temperature\":").append(request.temperature);
        if (request.maximumOutputTokens > 0) {
            sb.append(",\"num_predict\":").append(request.maximumOutputTokens);
        }
        sb.append("},");
        sb.append("\"messages\":[");
        boolean first = true;
        if (request.systemPrompt != null && !request.systemPrompt.trim().isEmpty()) {
            sb.append("{\"role\":\"system\",\"content\":\"").append(escape(request.systemPrompt)).append("\"}");
            first = false;
        }
        if (!first) {
            sb.append(',');
        }
        sb.append("{\"role\":\"user\",\"content\":\"").append(escape(request.userPrompt)).append("\"}");
        sb.append("]}");
        return sb.toString();
    }

    private String post(String requestBody) throws IOException {
        URL url = new URL(joinUrl(descriptor.baseUrl, descriptor.chatPath));
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
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
                throw new IOException("inference endpoint returned HTTP " + status
                        + (errorBody.isEmpty() ? "" : ": " + errorBody));
            }
            return readAll(connection.getInputStream());
        } finally {
            if (connection != null) {
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
