package com.aresstack.askai.localruntime;

import com.aresstack.windirectml.runtime.api.Backend;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Ollama-compatible surface without any real model: manifest scanning publishes only RUNNABLE
 * models, /api/show carries the rerank capability, unsupported operations answer with an
 * Ollama-style capability error (never an empty fake response), the keep_alive:0 unload convention
 * works, and /api/ps is empty until a model was really loaded.
 */
public class LocalModelRuntimeServerTest {

    private Path modelRoot;
    private LocalModelRuntimeServer server;
    private String baseUrl;

    @Before
    public void start() throws IOException {
        modelRoot = Files.createTempDirectory("askai-local-models");
        writeManifest("cross-encoder-ms-marco-MiniLM-L-6-v2",
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest", "RUNNABLE");
        writeManifest("broken-model", "local/broken:latest", "FAILED");
        server = new LocalModelRuntimeServer(new LocalModelStore(modelRoot),
                new LocalRerankerEngine(Backend.CPU));
        int port = server.start("127.0.0.1", 0);
        baseUrl = "http://127.0.0.1:" + port;
    }

    @After
    public void stop() {
        server.stop();
    }

    @Test
    public void versionAndTagsListOnlyRunnableModels() throws Exception {
        Map<String, Object> version = get("/api/version");
        assertEquals("askai-local-1", version.get("version"));

        Map<String, Object> tags = get("/api/tags");
        List<?> models = (List<?>) tags.get("models");
        assertEquals("the FAILED manifest must not be published", 1, models.size());
        Map<?, ?> model = (Map<?, ?>) models.get(0);
        assertEquals("local/cross-encoder/ms-marco-MiniLM-L6-v2:latest", model.get("name"));
        assertEquals("wdmlpack", ((Map<?, ?>) model.get("details")).get("format"));
    }

    @Test
    public void showCarriesTheRerankCapability() throws Exception {
        Map<String, Object> show = post("/api/show",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\"}", 200);
        assertEquals(List.of("rerank"), show.get("capabilities"));
        assertEquals("bert", ((Map<?, ?>) show.get("details")).get("family"));
    }

    @Test
    public void unsupportedOperationsAnswerWithCapabilityErrorsNeverFakeResponses() throws Exception {
        Map<String, Object> chat = post("/api/chat",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\"}", 400);
        assertEquals("model 'local/cross-encoder/ms-marco-MiniLM-L6-v2:latest' "
                + "does not support chat", chat.get("error"));
        Map<String, Object> embed = post("/api/embed",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\"}", 400);
        assertTrue(String.valueOf(embed.get("error")).contains("does not support embedding"));
        Map<String, Object> pull = post("/api/pull", "{\"model\":\"x\"}", 400);
        assertTrue(String.valueOf(pull.get("error")).contains("Hugging Face pane"));
    }

    @Test
    public void keepAliveZeroIsTheUnloadConventionAndPsStartsEmpty() throws Exception {
        Map<String, Object> ps = get("/api/ps");
        assertTrue("nothing was loaded yet", ((List<?>) ps.get("models")).isEmpty());
        Map<String, Object> unload = post("/api/generate",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\","
                        + "\"keep_alive\":0}", 200);
        assertEquals(Boolean.TRUE, unload.get("done"));
        // A REAL generate attempt stays a typed capability error.
        Map<String, Object> generate = post("/api/generate",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\","
                        + "\"prompt\":\"hi\"}", 400);
        assertTrue(String.valueOf(generate.get("error")).contains("does not support generate"));
    }

    @Test
    public void rerankOnAnUninstalledPackageFailsReadablyInsteadOfFaking() throws Exception {
        // The manifest claims RUNNABLE but no wdmlpack exists — loading must fail with a REASON.
        Map<String, Object> response = post("/api/rerank",
                "{\"model\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\","
                        + "\"query\":\"q\",\"documents\":[\"a\",\"b\"]}", 500);
        assertFalse(String.valueOf(response.get("error")).isEmpty());
    }

    @Test
    public void unknownModelsAndPathsFailTyped() throws Exception {
        Map<String, Object> show = post("/api/show", "{\"model\":\"local/ghost:latest\"}", 400);
        assertTrue(String.valueOf(show.get("error")).contains("not found"));
        assertEquals(404, statusOf("/api/nonsense"));
    }

    // ------------------------------------------------------------------ HTTP helpers

    private void writeManifest(String directory, String virtualName, String state)
            throws IOException {
        Path dir = modelRoot.resolve(directory);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(LocalModelManifest.FILE_NAME), LocalJson.write(Map.of(
                "schemaVersion", 1,
                "virtualName", virtualName,
                "huggingFaceRepository", "cross-encoder/ms-marco-MiniLM-L6-v2",
                "resolvedRevision", "abc123",
                "runtimeModelId", "MS_MARCO_MINILM_L6",
                "capabilities", List.of("rerank"),
                "backendSupport", List.of("cpu", "directml"),
                "state", state)));
    }

    private Map<String, Object> get(String path) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        return read(connection);
    }

    private int statusOf(String path) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        return connection.getResponseCode();
    }

    private Map<String, Object> post(String path, String body, int expectedStatus) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(expectedStatus, connection.getResponseCode());
        return read(connection);
    }

    private Map<String, Object> read(HttpURLConnection connection) throws Exception {
        InputStream in = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return LocalJson.parseObject(body);
    }
}
