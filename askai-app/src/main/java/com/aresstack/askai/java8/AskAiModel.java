package com.aresstack.askai.java8;

import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;

import java.io.File;
import java.nio.file.Path;

/**
 * Holds mutable application state shared by Swing panels, mirroring the original AskAI
 * {@code AskAiModel}. Java 8 port: persistence is delegated to the existing
 * {@link AppConfigurationRepository} (the same properties file the rest of the app uses), so
 * settings edited here and in the Network/Install panels never diverge.
 */
public final class AskAiModel {

    private final AppConfigurationRepository configurationRepository;
    private String ollamaBaseUrl;
    private File modelRoot;
    private String defaultQuantization;
    private String defaultKeepAlive;

    public AskAiModel(AppConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
        AppConfiguration configuration = configurationRepository.load();
        this.ollamaBaseUrl = configuration.getOllamaBaseUrl();
        this.modelRoot = configuration.getModelDownloadDirectory();
        this.defaultQuantization = configuration.getDefaultQuantization();
        this.defaultKeepAlive = configuration.getKeepAlive();
    }

    public String getOllamaBaseUrl() {
        return ollamaBaseUrl;
    }

    public void setOllamaBaseUrl(String ollamaBaseUrl) {
        this.ollamaBaseUrl = ollamaBaseUrl;
    }

    public Path getModelRoot() {
        return modelRoot.toPath();
    }

    public void setModelRoot(Path modelRoot) {
        this.modelRoot = modelRoot.toFile();
    }

    public String getDefaultQuantization() {
        return defaultQuantization;
    }

    public void setDefaultQuantization(String defaultQuantization) {
        this.defaultQuantization = defaultQuantization;
    }

    public String getDefaultKeepAlive() {
        return defaultKeepAlive;
    }

    public void setDefaultKeepAlive(String defaultKeepAlive) {
        this.defaultKeepAlive = defaultKeepAlive;
    }

    /**
     * Persists the buffered values, preserving every other setting (proxy, TLS trust, HTTP client,
     * HuggingFace token) exactly as currently stored.
     */
    public void saveSettings() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(new AppConfiguration(
                ollamaBaseUrl,
                defaultKeepAlive,
                current.getProxyConfiguration(),
                current.getCertificateTrustConfiguration(),
                current.getHttpClientConfiguration(),
                defaultQuantization,
                current.getHuggingFaceToken(),
                modelRoot));
        this.ollamaBaseUrl = configurationRepository.load().getOllamaBaseUrl();
    }
}
