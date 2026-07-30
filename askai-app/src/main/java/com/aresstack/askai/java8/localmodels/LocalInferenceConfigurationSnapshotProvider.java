package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.agent.model.inference.InferenceConfigurationDocument;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationException;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshot;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.inference.InferenceEndpointDescriptor;
import com.aresstack.askai.java8.config.AppConfigurationRepository;

import java.io.File;
import java.io.IOException;

/**
 * The productive host implementation of {@link InferenceConfigurationSnapshotProvider}. It reads the central
 * main (chat) model ({@code ai.mainModel}), resolves it to its ACTUAL serving endpoint — the local model
 * runtime sidecar for a {@code local/...} model, or the configured remote Ollama base URL otherwise — and
 * writes a sekret-free {@code inference-config.json} into the session directory. AskAI stays the only model
 * manager; the agent receives only a file path and never selects a model.
 *
 * <p>A missing main model or a runtime that will not start is a visible {@link
 * InferenceConfigurationException}; the caller (the plugin backend factory) treats that as "no inference
 * descriptor" so the agent keeps the honest unavailable-fallback for SERP layout repair.</p>
 */
public final class LocalInferenceConfigurationSnapshotProvider
        implements InferenceConfigurationSnapshotProvider {

    static final String SNAPSHOT_FILE_NAME = "inference-config.json";
    static final String CHAT_PATH = "/api/chat";
    static final long DEFAULT_TIMEOUT_MILLIS = 120_000L;

    /** The three endpoint sources, seamed so the local/remote routing is testable without a sidecar. */
    interface EndpointSources {
        /** The central main model name ("" when none is selected). */
        String modelName();

        /** The local runtime sidecar base URL (starts it); only called for a {@code local/...} model. */
        String localRuntimeBaseUrl() throws IOException;

        /** The configured remote Ollama base URL; only called for a non-local model. */
        String remoteOllamaBaseUrl();
    }

    private final EndpointSources sources;
    /** Rising configuration revision so a re-published descriptor is recognisably newer than the last. */
    private final java.util.concurrent.atomic.AtomicLong revision =
            new java.util.concurrent.atomic.AtomicLong();

    public LocalInferenceConfigurationSnapshotProvider(LocalModelRuntimeManager manager,
                                                       AppConfigurationRepository centralConfig) {
        this(productionSources(manager, centralConfig));
    }

    LocalInferenceConfigurationSnapshotProvider(EndpointSources sources) {
        this.sources = sources;
    }

    @Override
    public InferenceConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory)
            throws InferenceConfigurationException {
        String model = sources.modelName() == null ? "" : sources.modelName().trim();
        if (model.isEmpty()) {
            throw new InferenceConfigurationException(
                    "No main model is selected. Choose a chat model in the AskAI chat window "
                            + "(it is the shared main model for all plugins) before starting a productive "
                            + "research session, or the SERP layout repair stays unavailable.");
        }
        String baseUrl = resolveBaseUrl(model);
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new InferenceConfigurationException(
                    "The serving endpoint for the main model \"" + model + "\" could not be resolved "
                            + "(no usable base URL).");
        }

        InferenceConfigurationDocument document = InferenceConfigurationDocument.current(
                revision.incrementAndGet(),
                new InferenceEndpointDescriptor(model, baseUrl, CHAT_PATH, DEFAULT_TIMEOUT_MILLIS));
        File target = new File(sessionDirectory, SNAPSHOT_FILE_NAME);
        try {
            LocalInferenceConfigurationSnapshotWriter.write(document, target);
        } catch (IOException ex) {
            throw new InferenceConfigurationException(
                    "The inference session snapshot could not be written to " + target + ": "
                            + ex.getMessage(), ex);
        }
        return new InferenceConfigurationSnapshot(target.getAbsoluteFile(), document);
    }

    /** Local {@code local/...} models resolve to the runtime sidecar; everything else to remote Ollama. */
    private String resolveBaseUrl(String model) throws InferenceConfigurationException {
        if (LocalModelNames.isLocalModelName(model)) {
            try {
                return sources.localRuntimeBaseUrl();
            } catch (IOException ex) {
                throw new InferenceConfigurationException(
                        "The local model runtime for the main model could not be started: "
                                + ex.getMessage(), ex);
            }
        }
        return sources.remoteOllamaBaseUrl();
    }

    private static EndpointSources productionSources(final LocalModelRuntimeManager manager,
                                                     final AppConfigurationRepository centralConfig) {
        return new EndpointSources() {
            public String modelName() {
                return centralConfig.load().getAiModelSelections().getMainModel();
            }

            public String localRuntimeBaseUrl() throws IOException {
                if (manager == null) {
                    throw new IOException("no local model runtime is available for a local/ main model");
                }
                return manager.ensureStarted();
            }

            public String remoteOllamaBaseUrl() {
                return centralConfig.load().getOllamaBaseUrl();
            }
        };
    }
}
