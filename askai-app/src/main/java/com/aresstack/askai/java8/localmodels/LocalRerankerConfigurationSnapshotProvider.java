package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;

import io.github.ollama4j.json.OllamaJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A5e: the productive host implementation of {@link RerankerConfigurationSnapshotProvider}. It resolves
 * the explicitly usable rerank-capable local model, ensures its runtime is started, builds the neutral
 * descriptor and writes an atomic per-session snapshot — then hands the agent only the file path.
 *
 * <p>There is NO silent "first found" fallback: exactly one installed model must advertise the
 * {@code RERANK} capability. Zero usable rerank models, or an ambiguous set of several, is a visible
 * {@link RerankerConfigurationException} that fails the productive session start rather than guessing.
 */
public final class LocalRerankerConfigurationSnapshotProvider
        implements RerankerConfigurationSnapshotProvider {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String SNAPSHOT_FILE_NAME = "reranker-config.json";

    private final LocalModelRuntimeManager manager;

    public LocalRerankerConfigurationSnapshotProvider(LocalModelRuntimeManager manager) {
        this.manager = manager;
    }

    @Override
    public RerankerConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory)
            throws RerankerConfigurationException {
        String model = resolveRerankModel();

        String baseUrl;
        try {
            baseUrl = manager.ensureStarted();
        } catch (IOException ex) {
            throw new RerankerConfigurationException(
                    "The local model runtime for the reranker could not be started: "
                            + ex.getMessage(), ex);
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new RerankerConfigurationException(
                    "The local model runtime reported no usable base URL for the reranker.");
        }

        RerankerConfigurationDocument document;
        try {
            document = LocalRerankerEndpointDescriptorFactory.forLocalReranker(manager, model, 0L);
        } catch (IOException ex) {
            throw new RerankerConfigurationException(
                    "The reranker endpoint descriptor could not be built: " + ex.getMessage(), ex);
        }

        File target = new File(sessionDirectory, SNAPSHOT_FILE_NAME);
        try {
            LocalRerankerConfigurationSnapshotWriter.write(document, target);
        } catch (IOException ex) {
            throw new RerankerConfigurationException(
                    "The reranker session snapshot could not be written to " + target + ": "
                            + ex.getMessage(), ex);
        }
        return new RerankerConfigurationSnapshot(target.getAbsoluteFile(), document);
    }

    /**
     * The virtual name of the single installed rerank-capable local model. Fails visibly when none is
     * installed, or when several are — an explicit selection is then required rather than a guess.
     */
    private String resolveRerankModel() throws RerankerConfigurationException {
        List<String> rerankModels = new ArrayList<String>();
        File root = manager.getModelRoot();
        File[] children = root == null ? null : root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory()) {
                    continue;
                }
                String virtualName = rerankCapableModelName(new File(child, "askai-local-model.json"));
                if (virtualName != null) {
                    rerankModels.add(virtualName);
                }
            }
        }
        if (rerankModels.isEmpty()) {
            throw new RerankerConfigurationException(
                    "No rerank-capable local model is installed. Install a reranker under "
                            + "\"Install locally in AskAI\" before starting a productive research session.");
        }
        if (rerankModels.size() > 1) {
            throw new RerankerConfigurationException(
                    "Several rerank-capable local models are installed " + rerankModels
                            + "; an explicit selection is required (no silent first-found fallback).");
        }
        return rerankModels.get(0);
    }

    /** The manifest's virtual name if it declares the RERANK capability, else {@code null}. */
    @SuppressWarnings("unchecked")
    private static String rerankCapableModelName(File manifestFile) {
        if (!manifestFile.isFile()) {
            return null;
        }
        Object parsed;
        try {
            parsed = OllamaJson.parse(new String(Files.readAllBytes(manifestFile.toPath()), UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return null; // a corrupt manifest is simply not a usable rerank model
        }
        if (!(parsed instanceof Map)) {
            return null;
        }
        Map<String, Object> manifest = (Map<String, Object>) parsed;
        Object virtualName = manifest.get("virtualName");
        Object capabilities = manifest.get("capabilities");
        if (!(virtualName instanceof String) || !(capabilities instanceof List)) {
            return null;
        }
        for (Object capability : (List<Object>) capabilities) {
            if (LocalRuntimeCapability.RERANK.getOllamaTag().equals(capability)) {
                return (String) virtualName;
            }
        }
        return null;
    }
}
