package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A5c proof: the strict client returns validated rows verbatim and hard-fails (never guesses) on any
 * non-2xx status or contract-invalid body — non-finite score, duplicate index, or out-of-range index.
 */
public class HttpRerankerClientTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private HttpServer server;
    private volatile String responseBody;
    private volatile int responseStatus;

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/rerank", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                // Drain the request so the client's write always completes.
                exchange.getRequestBody().close();
                byte[] body = responseBody.getBytes(UTF_8);
                exchange.sendResponseHeaders(responseStatus, body.length);
                OutputStream out = exchange.getResponseBody();
                out.write(body);
                out.close();
            }
        });
        server.start();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpRerankerClient client() {
        RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(
                RerankerProvider.ASKAI_LOCAL,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest",
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT, 5_000L,
                RerankerSelectionConfiguration.topN(10));
        return new HttpRerankerClient(descriptor);
    }

    private List<String> docs() {
        return Arrays.asList("doc a", "doc b", "doc c");
    }

    @Test
    public void returnsValidatedRowsInResponseOrder() throws Exception {
        responseStatus = 200;
        responseBody = "{\"model\":\"m\",\"results\":["
                + "{\"index\":2,\"score\":-0.5},"
                + "{\"index\":0,\"score\":0.12},"
                + "{\"index\":1,\"score\":-3.4}],"
                + "\"total_duration\":1,\"load_duration\":1}";
        List<RerankScore> scores = client().rerank("q", docs());

        assertEquals(3, scores.size());
        assertEquals(2, scores.get(0).documentIndex);
        assertEquals(-0.5, scores.get(0).score, 1e-9);
        assertEquals(0, scores.get(1).documentIndex);
        assertEquals(0.12, scores.get(1).score, 1e-9);
    }

    @Test
    public void rejectsNonFiniteScore() {
        responseStatus = 200;
        responseBody = "{\"results\":[{\"index\":0,\"score\":1e400}]}"; // 1e400 -> Infinity
        expectFailure(RerankerClientFailure.INVALID_RESPONSE);
    }

    @Test
    public void rejectsDuplicateIndex() {
        responseStatus = 200;
        responseBody = "{\"results\":[{\"index\":0,\"score\":1.0},{\"index\":0,\"score\":2.0}]}";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE);
    }

    @Test
    public void rejectsOutOfRangeIndex() {
        responseStatus = 200;
        responseBody = "{\"results\":[{\"index\":9,\"score\":1.0}]}";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE);
    }

    @Test
    public void rejectsMissingResultsArray() {
        responseStatus = 200;
        responseBody = "{\"model\":\"m\"}";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE);
    }

    @Test
    public void rejectsNonJsonBody() {
        responseStatus = 200;
        responseBody = "not json at all";
        expectFailure(RerankerClientFailure.INVALID_RESPONSE);
    }

    @Test
    public void surfacesNonSuccessStatus() {
        responseStatus = 500;
        responseBody = "{\"error\":\"boom\"}";
        expectFailure(RerankerClientFailure.HTTP_STATUS);
    }

    private void expectFailure(RerankerClientFailure expected) {
        try {
            client().rerank("q", docs());
            fail("expected a " + expected + " failure");
        } catch (RerankerClientException e) {
            assertEquals(e.getMessage(), expected, e.getFailure());
            assertTrue(e.getMessage(), e.getMessage().length() > 0);
        }
    }
}
