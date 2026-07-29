package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * A5e/A5g: the productive host implementation of {@link RerankerConfigurationSnapshotProvider}. It
 * validates the EXPLICITLY selected rerank-capable local model (persisted research runtime setting),
 * ensures its runtime is started, builds the neutral descriptor and writes an atomic per-session
 * snapshot — then hands the agent only the file path.
 *
 * <p>There is NO silent "first found" fallback and no guessing: the selected virtual model id must
 * resolve to an installed manifest that declares the {@code RERANK} capability and a usable state. A
 * missing, removed or incompatible selection is a visible {@link RerankerConfigurationException} that
 * fails the productive session start.
 */
public final class LocalRerankerConfigurationSnapshotProvider
        implements RerankerConfigurationSnapshotProvider {

    private static final String SNAPSHOT_FILE_NAME = "reranker-config.json";

    private final LocalModelRuntimeManager manager;
    private final LocalRerankerModelCatalog catalog;

    public LocalRerankerConfigurationSnapshotProvider(LocalModelRuntimeManager manager) {
        this.manager = manager;
        this.catalog = new LocalRerankerModelCatalog(manager);
    }

    @Override
    public RerankerConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                           String selectedModel)
            throws RerankerConfigurationException {
        String model = resolveSelectedModel(selectedModel);

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
     * The validated virtual model id of the EXPLICIT selection. Fails visibly when no selection is
     * persisted, or when the selected model is no longer installed, lost its {@code RERANK} capability
     * or is not in a usable state — never replaced by a first-match guess.
     */
    String resolveSelectedModel(String selectedModel) throws RerankerConfigurationException {
        List<String> installed = catalog.listInstalledRerankModels();
        if (selectedModel == null || selectedModel.trim().isEmpty()) {
            throw new RerankerConfigurationException(installed.isEmpty()
                    ? "No reranker model is selected and no rerank-capable local model is installed. "
                            + "Install a reranker under \"Install locally in AskAI\" and select it in "
                            + "the Research Runtime Settings before starting a productive session."
                    : "No reranker model is selected. Choose one of the installed rerank models "
                            + installed + " in the Research Runtime Settings (there is no silent "
                            + "first-match selection).");
        }
        String selection = selectedModel.trim();
        if (!installed.contains(selection)) {
            throw new RerankerConfigurationException(
                    "The selected reranker model \"" + selection + "\" is not an installed, usable "
                            + "rerank-capable local model (installed: " + installed + "). Update the "
                            + "selection in the Research Runtime Settings.");
        }
        return selection;
    }
}
