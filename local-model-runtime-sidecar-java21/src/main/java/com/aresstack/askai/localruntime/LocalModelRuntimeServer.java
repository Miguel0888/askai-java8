package com.aresstack.askai.localruntime;

import com.aresstack.windirectml.encoder.pack.EncoderPackageLifecycle;
import com.aresstack.windirectml.inference.artifact.ModelConversionResult;
import com.aresstack.windirectml.runtime.api.RerankResult;
import com.aresstack.windirectml.runtime.api.RerankerModelId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Ollama-compatible loopback HTTP surface of AskAI's virtual LOCAL model container:
 * {@code /api/version, /api/tags, /api/show, /api/ps, /api/delete, /api/rerank} plus the
 * {@code keep_alive:0} unload convention on {@code /api/generate}. Unsupported operations answer
 * with an Ollama-style capability error — never an empty fake response. The host-only
 * {@code /internal/install} endpoint runs the wdmlpack compile + runtime smoke-load for a staged
 * model directory (the Java-8 host cannot run the Java-21 compiler itself).
 */
final class LocalModelRuntimeServer {

    static final String VERSION = "askai-local-1";

    private final LocalModelStore store;
    private final LocalRerankerEngine engine;
    private HttpServer server;

    LocalModelRuntimeServer(LocalModelStore store, LocalRerankerEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    /** @return the bound port (the OS chooses one for requestedPort=0). */
    int start(String host, int requestedPort) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
        engine.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        try {
            switch (path) {
                case "/api/version" -> respond(exchange, 200, Map.of("version", VERSION));
                case "/api/tags" -> handleTags(exchange);
                case "/api/show" -> handleShow(exchange);
                case "/api/ps" -> handlePs(exchange);
                case "/api/delete" -> handleDelete(exchange);
                case "/api/rerank" -> handleRerank(exchange);
                case "/api/generate" -> handleGenerate(exchange);
                case "/api/chat" -> capabilityError(exchange, modelOf(exchange), "chat");
                case "/api/embed", "/api/embeddings" ->
                        capabilityError(exchange, modelOf(exchange), "embedding");
                case "/api/pull", "/api/push", "/api/create", "/api/copy" ->
                        error(exchange, 400, "the AskAI local runtime installs models through the "
                                + "AskAI Hugging Face pane, not through '" + path + "'");
                case "/internal/install" -> handleInternalInstall(exchange);
                default -> error(exchange, 404, "unknown path '" + path + "'");
            }
        } catch (IllegalArgumentException bad) {
            error(exchange, 400, bad.getMessage());
        } catch (Exception failure) {
            System.err.println("[local-runtime] " + method + " " + path + " failed: " + failure);
            error(exchange, 500, String.valueOf(failure.getMessage()));
        }
    }

    // ------------------------------------------------------------------ Ollama surface

    private void handleTags(HttpExchange exchange) throws IOException {
        List<Object> models = new ArrayList<>();
        for (LocalModelManifest manifest : store.runnableModels()) {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("name", manifest.virtualName());
            model.put("model", manifest.virtualName());
            model.put("modified_at", Instant.now().toString());
            model.put("size", store.directorySizeBytes(manifest.modelDirectory()));
            model.put("digest", "");
            model.put("details", details());
            models.add(model);
        }
        respond(exchange, 200, Map.of("models", models));
    }

    private void handleShow(HttpExchange exchange) throws IOException {
        LocalModelManifest manifest = requireModel(modelOf(exchange));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", manifest.virtualName());
        body.put("details", details());
        body.put("capabilities", manifest.capabilities());
        body.put("modified_at", Instant.now().toString());
        respond(exchange, 200, body);
    }

    private void handlePs(HttpExchange exchange) throws IOException {
        List<Object> models = new ArrayList<>();
        String loaded = engine.loadedVirtualName();
        if (loaded != null) {
            LocalModelManifest manifest = store.find(loaded);
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("name", loaded);
            model.put("model", loaded);
            model.put("size", manifest == null ? 0
                    : store.directorySizeBytes(manifest.modelDirectory()));
            model.put("size_vram", 0);
            model.put("expires_at", "");
            model.put("details", details());
            models.add(model);
        }
        respond(exchange, 200, Map.of("models", models));
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        LocalModelManifest manifest = requireModel(modelOf(exchange));
        engine.unload(manifest.virtualName());
        store.delete(manifest);
        respond(exchange, 200, Map.of());
    }

    private void handleRerank(HttpExchange exchange) throws Exception {
        Map<String, Object> request = LocalJson.parseObject(body(exchange));
        LocalModelManifest manifest = requireModel(LocalJson.str(request, "model"));
        if (!manifest.capabilities().contains("rerank")) {
            capabilityError(exchange, manifest.virtualName(), "rerank");
            return;
        }
        String query = LocalJson.str(request, "query");
        List<String> documents = LocalJson.strings(request, "documents");
        if (query.isEmpty() || documents.isEmpty()) {
            throw new IllegalArgumentException("rerank needs 'query' and non-empty 'documents'");
        }
        int topN = request.get("top_n") instanceof Number n ? n.intValue() : 0;
        LocalRerankerEngine.Reranked reranked = engine.rerank(manifest, query, documents, topN);
        List<Object> results = new ArrayList<>();
        for (RerankResult result : reranked.results()) {
            results.add(Map.of("index", result.originalIndex(), "score", result.score()));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("model", manifest.virtualName());
        response.put("results", results);
        response.put("total_duration", reranked.totalDurationNanos());
        response.put("load_duration", reranked.loadDurationNanos());
        respond(exchange, 200, response);
    }

    /** Ollama's unload convention: {@code {"model":…, "keep_alive":0}} without a prompt. */
    private void handleGenerate(HttpExchange exchange) throws IOException {
        Map<String, Object> request = LocalJson.parseObject(body(exchange));
        String model = LocalJson.str(request, "model");
        boolean unload = request.get("keep_alive") instanceof Number n && n.intValue() == 0
                && LocalJson.str(request, "prompt").isEmpty();
        if (unload) {
            requireModel(model);
            engine.unload(model);
            respond(exchange, 200, Map.of("model", model, "done", true, "done_reason", "unload"));
            return;
        }
        capabilityError(exchange, model, "generate");
    }

    // ------------------------------------------------------------------ host-only install

    private void handleInternalInstall(HttpExchange exchange) throws IOException {
        Map<String, Object> request = LocalJson.parseObject(body(exchange));
        Path modelDir = Path.of(LocalJson.str(request, "modelDir"));
        RerankerModelId modelId;
        try {
            modelId = RerankerModelId.valueOf(LocalJson.str(request, "runtimeModelId"));
        } catch (IllegalArgumentException unknown) {
            respond(exchange, 200, Map.of("ok", false,
                    "reason", "unknown runtimeModelId '" + LocalJson.str(request, "runtimeModelId")
                            + "'"));
            return;
        }
        try {
            // download -> CONVERT -> run-only-from-package: the model is RUNNABLE only after the
            // wdmlpack was compiled AND validated by a real runtime smoke load.
            ModelConversionResult conversion =
                    EncoderPackageLifecycle.reranker().convert(modelDir, true);
            if (!conversion.ok()) {
                respond(exchange, 200, Map.of("ok", false,
                        "reason", "package compile failed: " + conversion.message()));
                return;
            }
            engine.smokeLoad(modelDir, modelId);
            respond(exchange, 200, Map.of("ok", true,
                    "packagePath", String.valueOf(conversion.output()),
                    "reason", conversion.message()));
        } catch (Exception failure) {
            respond(exchange, 200, Map.of("ok", false,
                    "reason", "compile/smoke-load failed: " + failure.getMessage()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("format", "wdmlpack");
        details.put("family", "bert");
        details.put("families", List.of("bert", "cross-encoder"));
        details.put("parameter_size", "22.7M");
        details.put("quantization_level", "");
        return details;
    }

    private LocalModelManifest requireModel(String virtualName) {
        LocalModelManifest manifest = store.find(virtualName);
        if (manifest == null) {
            throw new IllegalArgumentException("model '" + virtualName + "' not found");
        }
        return manifest;
    }

    private String modelOf(HttpExchange exchange) throws IOException {
        String body = body(exchange);
        if (body.isEmpty()) {
            return "";
        }
        try {
            return LocalJson.str(LocalJson.parseObject(body), "model");
        } catch (RuntimeException notJson) {
            return "";
        }
    }

    private void capabilityError(HttpExchange exchange, String model, String capability)
            throws IOException {
        error(exchange, 400, "model '" + (model.isEmpty() ? "unknown" : model)
                + "' does not support " + capability);
    }

    private void error(HttpExchange exchange, int status, String message) throws IOException {
        respond(exchange, status, Map.of("error", message == null ? "unknown error" : message));
    }

    private String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, Object json) throws IOException {
        byte[] bytes = LocalJson.write(json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
