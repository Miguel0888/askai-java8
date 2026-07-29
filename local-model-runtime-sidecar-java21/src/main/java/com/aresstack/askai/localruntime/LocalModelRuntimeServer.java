package com.aresstack.askai.localruntime;

import com.aresstack.windirectml.catalog.CatalogBackend;
import com.aresstack.windirectml.catalog.CatalogModelFamily;
import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ModelCapability;
import com.aresstack.windirectml.encoder.EmbeddingException;
import com.aresstack.windirectml.encoder.pack.EncoderPackageLifecycle;
import com.aresstack.windirectml.modelpack.ModelConversionResult;
import com.aresstack.windirectml.runtime.api.Backend;
import com.aresstack.windirectml.runtime.api.EmbeddingModelId;
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
 * The Ollama-compatible loopback HTTP surface of AskAI's virtual LOCAL model container. Every model fact
 * comes from the shared catalog-validated manifest, never from a name heuristic; a wrong endpoint for a
 * model's capability returns a typed capability error (never an empty fake vector). The host-only
 * {@code /internal/install} re-resolves the repository against the catalog itself (it ignores any
 * host-claimed family/capabilities), compiles the wdmlpack and runs a package-backed smoke-load.
 */
final class LocalModelRuntimeServer {

    static final String VERSION = "askai-local-1";

    // Typed error codes (returned alongside a human message).
    private static final String MODEL_NOT_FOUND = "MODEL_NOT_FOUND";
    private static final String MODEL_CAPABILITY_MISMATCH = "MODEL_CAPABILITY_MISMATCH";
    private static final String MODEL_NOT_LOADABLE = "MODEL_NOT_LOADABLE";
    private static final String INVALID_INPUT_TYPE = "INVALID_INPUT_TYPE";
    private static final String INVALID_REQUEST = "INVALID_REQUEST";

    private final LocalModelStore store;
    private final LocalModelEngine engine;
    private HttpServer server;

    LocalModelRuntimeServer(LocalModelStore store, LocalModelEngine engine) {
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
                case "/api/embed" -> handleEmbed(exchange, false);
                case "/api/embeddings" -> handleEmbed(exchange, true);
                case "/api/generate" -> handleGenerate(exchange);
                case "/api/chat" -> capabilityMismatch(exchange, modelOf(exchange), "chat");
                case "/api/pull", "/api/push", "/api/create", "/api/copy" ->
                        typed(exchange, 400, INVALID_REQUEST, "the AskAI local runtime installs models "
                                + "through the AskAI Hugging Face pane, not through '" + path + "'");
                case "/internal/install" -> handleInternalInstall(exchange);
                default -> typed(exchange, 404, INVALID_REQUEST, "unknown path '" + path + "'");
            }
        } catch (IllegalArgumentException bad) {
            typed(exchange, 400, INVALID_REQUEST, bad.getMessage());
        } catch (Exception failure) {
            System.err.println("[local-runtime] " + method + " " + path + " failed: " + failure);
            typed(exchange, 500, "INTERNAL_ERROR", String.valueOf(failure.getMessage()));
        }
    }

    // ------------------------------------------------------------------ Ollama surface

    private void handleTags(HttpExchange exchange) throws IOException {
        List<Object> models = new ArrayList<>();
        for (LocalModel model : store.runnableModels()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", model.virtualName());
            entry.put("model", model.virtualName());
            entry.put("modified_at", Instant.now().toString());
            entry.put("size", store.directorySizeBytes(model.directory()));
            entry.put("digest", "");
            entry.put("details", details(model));
            models.add(entry);
        }
        respond(exchange, 200, Map.of("models", models));
    }

    private void handleShow(HttpExchange exchange) throws IOException {
        LocalModel model = find(modelOf(exchange));
        if (model == null) {
            typed(exchange, 404, MODEL_NOT_FOUND, "model not found");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.virtualName());
        body.put("details", details(model));
        body.put("capabilities", model.manifest().getCapabilities());
        body.put("modified_at", Instant.now().toString());
        respond(exchange, 200, body);
    }

    private void handlePs(HttpExchange exchange) throws IOException {
        List<Object> models = new ArrayList<>();
        for (String virtualName : engine.loadedVirtualNames()) {
            LocalModel model = store.find(virtualName);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", virtualName);
            entry.put("model", virtualName);
            entry.put("size", model == null ? 0 : store.directorySizeBytes(model.directory()));
            entry.put("size_vram", 0);
            entry.put("expires_at", "");
            entry.put("details", model == null ? Map.of() : details(model));
            models.add(entry);
        }
        respond(exchange, 200, Map.of("models", models));
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        LocalModel model = find(modelOf(exchange));
        if (model == null) {
            typed(exchange, 404, MODEL_NOT_FOUND, "model not found");
            return;
        }
        // Stop new leases, let in-flight inference finish, close the handle, THEN delete the files.
        engine.unloadForDelete(model.virtualName());
        try {
            store.delete(model);
        } catch (IOException ex) {
            typed(exchange, 500, "DELETE_FAILED", "could not delete model files: " + ex.getMessage());
            return;
        }
        respond(exchange, 200, Map.of("status", "deleted", "model", model.virtualName()));
    }

    private void handleRerank(HttpExchange exchange) throws Exception {
        Map<String, Object> request = LocalJson.parseObject(body(exchange));
        LocalModel model = find(LocalJson.str(request, "model"));
        if (model == null) {
            typed(exchange, 404, MODEL_NOT_FOUND, "model not found");
            return;
        }
        if (!model.isReranker()) {
            capabilityMismatch(exchange, model.virtualName(), "rerank");
            return;
        }
        String query = LocalJson.str(request, "query");
        List<String> documents = LocalJson.strings(request, "documents");
        if (query.isEmpty() || documents.isEmpty()) {
            typed(exchange, 400, INVALID_REQUEST, "rerank needs 'query' and non-empty 'documents'");
            return;
        }
        int topN = request.get("top_n") instanceof Number n ? n.intValue() : 0;
        LocalModelEngine.Reranked reranked;
        try {
            reranked = engine.rerank(model, query, documents, topN);
        } catch (EmbeddingException notLoadable) {
            typed(exchange, 500, MODEL_NOT_LOADABLE, "reranker not loadable: " + notLoadable.getMessage());
            return;
        }
        List<Object> results = new ArrayList<>();
        for (RerankResult result : reranked.results()) {
            results.add(Map.of("index", result.originalIndex(), "score", result.score()));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("model", model.virtualName());
        response.put("results", results);
        response.put("total_duration", reranked.totalDurationNanos());
        response.put("load_duration", reranked.loadDurationNanos());
        respond(exchange, 200, response);
    }

    private void handleEmbed(HttpExchange exchange, boolean legacySingular) throws Exception {
        Map<String, Object> request = LocalJson.parseObject(body(exchange));
        LocalModel model = find(LocalJson.str(request, "model"));
        if (model == null) {
            typed(exchange, 404, MODEL_NOT_FOUND, "model not found");
            return;
        }
        if (!model.isEmbedding()) {
            capabilityMismatch(exchange, model.virtualName(), "embedding");
            return;
        }
        List<String> inputs = legacySingular
                ? List.of(LocalJson.str(request, "prompt")) : inputList(request.get("input"));
        if (inputs.isEmpty()) {
            typed(exchange, 400, INVALID_REQUEST,
                    legacySingular ? "embeddings needs a 'prompt'" : "embed needs 'input'");
            return;
        }
        LocalModelEngine.PrefixMode mode;
        try {
            mode = prefixMode(LocalJson.str(request, "input_type"));
        } catch (IllegalArgumentException unknown) {
            typed(exchange, 400, INVALID_INPUT_TYPE, unknown.getMessage());
            return;
        }
        if (mode != LocalModelEngine.PrefixMode.RAW && !model.isE5()) {
            typed(exchange, 400, INVALID_INPUT_TYPE, "model '" + model.virtualName()
                    + "' does not use query/passage prefixes; use input_type=raw");
            return;
        }
        // E5 prefixes are applied ONLY per request (the handle is loaded prefix-neutral); RAW is identity.
        List<String> transformed = new ArrayList<>(inputs.size());
        for (String text : inputs) {
            transformed.add(model.isE5() ? mode.apply(text) : text);
        }
        List<float[]> vectors;
        try {
            vectors = engine.embed(model, transformed);
        } catch (EmbeddingException notLoadable) {
            typed(exchange, 500, MODEL_NOT_LOADABLE, "embedding model not loadable: "
                    + notLoadable.getMessage());
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("model", model.virtualName());
        if (legacySingular) {
            response.put("embedding", floats(vectors.get(0)));
        } else {
            List<Object> rows = new ArrayList<>();
            for (float[] vector : vectors) {
                rows.add(floats(vector));
            }
            response.put("embeddings", rows);
        }
        respond(exchange, 200, response);
    }

    /** Ollama's unload convention: {@code {"model":…, "keep_alive":0}} without a prompt. */
    private void handleGenerate(HttpExchange exchange) throws IOException {
        Map<String, Object> request = LocalJson.parseObject(body(exchange));
        String model = LocalJson.str(request, "model");
        boolean unload = request.get("keep_alive") instanceof Number n && n.intValue() == 0
                && LocalJson.str(request, "prompt").isEmpty();
        if (unload) {
            if (find(model) == null) {
                typed(exchange, 404, MODEL_NOT_FOUND, "model not found");
                return;
            }
            engine.unload(model);
            respond(exchange, 200, Map.of("model", model, "done", true, "done_reason", "unload"));
            return;
        }
        capabilityMismatch(exchange, model, "generate");
    }

    // ------------------------------------------------------------------ host-only install

    private void handleInternalInstall(HttpExchange exchange) throws IOException {
        Map<String, Object> request = LocalJson.parseObject(body(exchange));
        String repositoryId = LocalJson.str(request, "repositoryId");
        String modelDir = LocalJson.str(request, "modelDirectory");
        if (modelDir.isEmpty()) {
            modelDir = LocalJson.str(request, "modelDir"); // accepted alias
        }
        if (repositoryId.isEmpty() || modelDir.isEmpty()) {
            respond(exchange, 200, installError("INVALID_REQUEST",
                    "install needs 'repositoryId' and 'modelDirectory'"));
            return;
        }
        // The sidecar re-resolves the repository against the catalog ITSELF; it never trusts a host-claimed
        // family or capability set.
        LocalRuntimeModelDescriptor descriptor = LocalModelCatalog.findByRepositoryId(repositoryId);
        if (descriptor == null) {
            respond(exchange, 200, installError("CATALOG_ENTRY_MISSING",
                    "'" + repositoryId + "' is not a catalogued local-engine model"));
            return;
        }
        if (!descriptor.isRunnable()) {
            respond(exchange, 200, installError("MODEL_NOT_RUNNABLE",
                    "'" + repositoryId + "' is catalogued but not RUNNABLE"));
            return;
        }
        CatalogModelFamily family = descriptor.runtimeFamily();
        boolean encoder = family == CatalogModelFamily.MINILM || family == CatalogModelFamily.E5;
        boolean reranker = family == CatalogModelFamily.CROSS_ENCODER;
        if (!encoder && !reranker) {
            respond(exchange, 200, installError("UNSUPPORTED_FAMILY",
                    "local installation of family '" + family.token() + "' is not available yet"));
            return;
        }
        Backend effectiveBackend = engine.backend();
        if (!backendSupported(descriptor, effectiveBackend)) {
            respond(exchange, 200, installError("UNSUPPORTED_BACKEND",
                    "backend '" + effectiveBackend.name().toLowerCase(Locale.ROOT)
                            + "' is not supported by '" + repositoryId + "'"));
            return;
        }
        boolean force = Boolean.TRUE.equals(request.get("force"));
        Path directory = Path.of(modelDir);
        try {
            ModelConversionResult conversion = (encoder ? EncoderPackageLifecycle.embedding()
                    : EncoderPackageLifecycle.reranker()).convert(directory, force);
            if (!conversion.ok()) {
                respond(exchange, 200, installError("PACKAGE_COMPILE_FAILED",
                        "package compile failed: " + conversion.message()));
                return;
            }
            // Package-backed smoke-load: the CPU weight loader opens <dir>/<package>.wdmlpack, never the raw
            // safetensors, so a successful smoke really proves the compiled package runs.
            try {
                if (encoder) {
                    engine.smokeLoadEncoder(directory,
                            EmbeddingModelId.valueOf(descriptor.runtimeModelId()));
                } else {
                    engine.smokeLoadReranker(directory,
                            RerankerModelId.valueOf(descriptor.runtimeModelId()));
                }
            } catch (EmbeddingException notLoadable) {
                respond(exchange, 200, installError("PACKAGE_NOT_LOADABLE",
                        "compiled package did not load: " + notLoadable.getMessage()));
                return;
            } catch (Exception smokeFailure) {
                respond(exchange, 200, installError("MODEL_SMOKE_FAILED",
                        "smoke inference failed: " + smokeFailure.getMessage()));
                return;
            }
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true); // kept for backward compatibility with the current host reader
            ok.put("status", "READY");
            ok.put("repositoryId", repositoryId);
            ok.put("virtualName", descriptor.virtualModelName());
            ok.put("runtimeModelId", descriptor.runtimeModelId());
            ok.put("runtimeFamily", descriptor.runtimeFamily().token());
            ok.put("runtimePackage", descriptor.runtimePackageFileName());
            ok.put("capabilities", InstalledModelManifest.expectedCapabilityTokens(descriptor));
            ok.put("backend", effectiveBackend.name().toLowerCase(Locale.ROOT));
            ok.put("packagePath", String.valueOf(conversion.output()));
            respond(exchange, 200, ok);
        } catch (Exception failure) {
            respond(exchange, 200, installError("PACKAGE_COMPILE_FAILED",
                    "compile/smoke failed: " + failure.getMessage()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean backendSupported(LocalRuntimeModelDescriptor descriptor, Backend backend) {
        try {
            return descriptor.supportedBackends().contains(CatalogBackend.valueOf(backend.name()));
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    private static Map<String, Object> installError(String code, String reason) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("ok", false);
        error.put("status", "ERROR");
        error.put("code", code);
        error.put("reason", reason);
        return error;
    }

    private static List<Float> floats(float[] vector) {
        List<Float> row = new ArrayList<>(vector.length);
        for (float value : vector) {
            row.add(value);
        }
        return row;
    }

    private static List<String> inputList(Object input) {
        List<String> inputs = new ArrayList<>();
        if (input instanceof String s) {
            if (!s.isEmpty()) {
                inputs.add(s);
            }
        } else if (input instanceof List<?> list) {
            for (Object item : list) {
                inputs.add(String.valueOf(item));
            }
        }
        return inputs;
    }

    private static LocalModelEngine.PrefixMode prefixMode(String inputType) {
        if (inputType == null || inputType.isEmpty()) {
            return LocalModelEngine.PrefixMode.RAW;
        }
        switch (inputType.toLowerCase(Locale.ROOT)) {
            case "raw": return LocalModelEngine.PrefixMode.RAW;
            case "query": return LocalModelEngine.PrefixMode.QUERY;
            case "passage": return LocalModelEngine.PrefixMode.PASSAGE;
            default: throw new IllegalArgumentException("unknown input_type '" + inputType
                    + "' (expected raw, query or passage)");
        }
    }

    private Map<String, Object> details(LocalModel model) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("format", "wdmlpack");
        details.put("family", model.runtimeFamily());
        details.put("families", List.of(model.runtimeFamily()));
        details.put("capabilities", model.manifest().getCapabilities());
        details.put("backends", model.manifest().getSupportedBackends());
        details.put("runtime", "win-directml-java");
        return details;
    }

    private LocalModel find(String virtualName) {
        return virtualName == null || virtualName.isEmpty() ? null : store.find(virtualName);
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

    private void capabilityMismatch(HttpExchange exchange, String model, String capability)
            throws IOException {
        typed(exchange, 400, MODEL_CAPABILITY_MISMATCH, "model '" + (model.isEmpty() ? "unknown" : model)
                + "' does not support " + capability);
    }

    private void typed(HttpExchange exchange, int status, String code, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null ? "unknown error" : message);
        body.put("code", code);
        respond(exchange, status, body);
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
