package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A5c: the STRICT HTTP client for the local reranker's {@code POST <baseUrl>/api/rerank} dialect. It
 * scores the submitted documents and returns validated {@link RerankScore} rows in the endpoint's
 * response order; it never sorts, thresholds or truncates (that is the selection policy's job) and it
 * never guesses around a malformed answer. Every non-usable outcome — transport error, non-2xx status,
 * or a body that is not a contract-valid rerank response (not JSON, missing results, non-finite score,
 * duplicated or out-of-range document index) — surfaces as a typed {@link RerankerClientException}.
 */
public final class HttpRerankerClient {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final RerankerEndpointDescriptor descriptor;

    public HttpRerankerClient(RerankerEndpointDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    /**
     * Score {@code documents} for {@code query}. Returns validated rows in response order (NOT sorted).
     *
     * @throws RerankerClientException on any transport, status or contract-validity failure
     */
    @SuppressWarnings("unchecked")
    public List<RerankScore> rerank(String query, List<String> documents)
            throws RerankerClientException {
        if (documents.isEmpty()) {
            return new ArrayList<RerankScore>();
        }
        RerankRequest request = new RerankRequest(descriptor.modelName, query, documents);
        String responseBody = post(request.toJson());

        Object root;
        try {
            root = RerankJson.parse(responseBody);
        } catch (RerankJson.JsonParseException e) {
            throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                    "reranker response is not JSON: " + e.getMessage());
        }
        if (!(root instanceof Map)) {
            throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                    "reranker response root is not a JSON object");
        }
        Object resultsValue = ((Map<String, Object>) root).get("results");
        if (!(resultsValue instanceof List)) {
            throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                    "reranker response has no 'results' array");
        }

        List<RerankScore> scores = new ArrayList<RerankScore>();
        Set<Integer> seen = new HashSet<Integer>();
        for (Object element : (List<Object>) resultsValue) {
            if (!(element instanceof Map)) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker result entry is not an object");
            }
            Map<String, Object> entry = (Map<String, Object>) element;
            Object indexValue = entry.get("index");
            Object scoreValue = entry.get("score");
            if (!(indexValue instanceof Double) || !(scoreValue instanceof Double)) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker result entry must carry numeric 'index' and 'score'");
            }
            double rawIndex = (Double) indexValue;
            if (rawIndex != Math.floor(rawIndex) || Double.isInfinite(rawIndex)) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker result index is not an integer: " + rawIndex);
            }
            int index = (int) rawIndex;
            if (index < 0 || index >= documents.size()) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker result index " + index + " is outside 0.." + (documents.size() - 1));
            }
            if (!seen.add(index)) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker returned duplicate index " + index);
            }
            double score = (Double) scoreValue;
            if (Double.isNaN(score) || Double.isInfinite(score)) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker score for index " + index + " is not finite");
            }
            scores.add(new RerankScore(index, score));
        }
        return scores;
    }

    private String post(String requestBody) throws RerankerClientException {
        URL url;
        try {
            url = new URL(joinUrl(descriptor.baseUrl, "/api/rerank"));
        } catch (IOException e) {
            throw new RerankerClientException(RerankerClientFailure.TRANSPORT,
                    "reranker base URL is unusable: " + e.getMessage(), e);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            int timeout = (int) Math.min(Integer.MAX_VALUE, descriptor.requestTimeoutMillis);
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);

            byte[] payload = requestBody.getBytes(UTF_8);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                out.close();
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String errorBody = readAll(connection.getErrorStream());
                throw new RerankerClientException(RerankerClientFailure.HTTP_STATUS,
                        "reranker returned HTTP " + status
                                + (errorBody.isEmpty() ? "" : ": " + errorBody));
            }
            return readAll(connection.getInputStream());
        } catch (RerankerClientException e) {
            throw e;
        } catch (IOException e) {
            throw new RerankerClientException(RerankerClientFailure.TRANSPORT,
                    "reranker call failed: " + e.getMessage(), e);
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
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        return baseUrl + path;
    }
}
