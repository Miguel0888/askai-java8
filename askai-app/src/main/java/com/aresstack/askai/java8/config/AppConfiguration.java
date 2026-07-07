package com.aresstack.askai.java8.config;

import com.aresstack.askai.java8.net.ProxyConfiguration;

import java.io.File;

public final class AppConfiguration {

    private final String ollamaBaseUrl;
    private final String keepAlive;
    private final ProxyConfiguration proxyConfiguration;
    private final String huggingFaceToken;
    private final File modelDownloadDirectory;

    public AppConfiguration(String ollamaBaseUrl, String keepAlive) {
        this(ollamaBaseUrl, keepAlive, ProxyConfiguration.defaults(), "", defaultDownloadDirectory());
    }

    public AppConfiguration(String ollamaBaseUrl, String keepAlive, ProxyConfiguration proxyConfiguration,
                            String huggingFaceToken, File modelDownloadDirectory) {
        this.ollamaBaseUrl = normalizeBaseUrl(ollamaBaseUrl);
        this.keepAlive = keepAlive == null || keepAlive.trim().length() == 0 ? "5m" : keepAlive.trim();
        this.proxyConfiguration = proxyConfiguration == null ? ProxyConfiguration.defaults() : proxyConfiguration;
        this.huggingFaceToken = huggingFaceToken == null ? "" : huggingFaceToken.trim();
        this.modelDownloadDirectory = modelDownloadDirectory == null ? defaultDownloadDirectory() : modelDownloadDirectory;
    }

    public static AppConfiguration defaults() {
        return new AppConfiguration("http://127.0.0.1:11434", "5m");
    }

    public String getOllamaBaseUrl() {
        return ollamaBaseUrl;
    }

    public String getKeepAlive() {
        return keepAlive;
    }

    public ProxyConfiguration getProxyConfiguration() {
        return proxyConfiguration;
    }

    public String getHuggingFaceToken() {
        return huggingFaceToken;
    }

    public File getModelDownloadDirectory() {
        return modelDownloadDirectory;
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null || value.trim().length() == 0
                ? "http://127.0.0.1:11434"
                : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static File defaultDownloadDirectory() {
        String appData = System.getenv("APPDATA");
        File baseDirectory;
        if (appData != null && appData.trim().length() > 0) {
            baseDirectory = new File(appData, ".askai-java8");
        } else {
            baseDirectory = new File(System.getProperty("user.home"), ".askai-java8");
        }
        return new File(baseDirectory, "models");
    }
}
