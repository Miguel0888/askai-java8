package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.client.AskAiOllamaClient;
import com.aresstack.askai.java8.localmodels.LocalModelNames;
import com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager;

/**
 * AskAI's own local model runtime as a virtual Ollama container; owns every {@code local/} model.
 * {@link #createClient()} starts the Java-21 sidecar on demand (there is no fixed port — the
 * manager reads the base URL from the sidecar's ready line).
 */
public final class LocalAskAiContainerSource implements OllamaContainerSource {

    public static final String CONTAINER_ID = "askai-local";

    private final LocalModelRuntimeManager manager;

    public LocalAskAiContainerSource(LocalModelRuntimeManager manager) {
        this.manager = manager;
    }

    public String getContainerId() {
        return CONTAINER_ID;
    }

    public String getDisplayName() {
        return "Local";
    }

    public String getBaseUrl() {
        String baseUrl = manager.getBaseUrl();
        return baseUrl == null ? "" : baseUrl;
    }

    public boolean isLocal() {
        return true;
    }

    /** True when this source should contribute to lists (running, or installable models exist). */
    public boolean hasAnythingToServe() {
        return manager.isRunning() || manager.hasInstalledModels();
    }

    public AskAiOllamaClient createClient() throws Exception {
        return new AskAiOllamaClient(manager.ensureStarted());
    }

    public boolean ownsModel(String virtualModelName) {
        return LocalModelNames.isLocalModelName(virtualModelName);
    }
}
