package com.aresstack.askai.java8.config;

import com.aresstack.askai.java8.net.CertificateTrustConfiguration;
import com.aresstack.askai.java8.net.HttpClientConfiguration;
import com.aresstack.askai.java8.net.ProxyConfiguration;
import com.aresstack.askai.java8.stt.SpeechToTextConfiguration;

import java.io.File;

public final class AppConfiguration {

    private final String ollamaBaseUrl;
    private final String keepAlive;
    private final ProxyConfiguration proxyConfiguration;
    private final CertificateTrustConfiguration certificateTrustConfiguration;
    private final HttpClientConfiguration httpClientConfiguration;
    private final String defaultQuantization;
    private final String huggingFaceToken;
    private final File modelDownloadDirectory;
    private final SpeechToTextConfiguration speechToTextConfiguration;
    private final String huggingFaceSearchSuggestions;

    /**
     * Default HuggingFace search suggestions for the Install panel dropdown, curated for a 16 GB
     * VRAM card. Mix of chat models (gpt-oss-20b fits in MXFP4; the rest comfortably at Q4/Q5) and
     * audio-capable models for the speech-to-text feature (gemma-3n, voxtral, qwen3-asr, ultravox —
     * plain llama/gemma/gpt-oss cannot take audio input).
     */
    public static final String DEFAULT_HF_SEARCH_SUGGESTIONS =
            "gpt-oss-20b\n"
                    + "llama-3.1-8b-instruct\n"
                    + "gemma-3-12b-it\n"
                    + "qwen2.5-14b-instruct\n"
                    + "qwen2.5-coder-14b\n"
                    + "phi-4\n"
                    + "mistral-nemo\n"
                    + "gemma-3n-e4b\n"
                    + "voxtral-mini-3b\n"
                    + "qwen3-asr\n"
                    + "ultravox";

    public AppConfiguration(String ollamaBaseUrl, String keepAlive) {
        this(ollamaBaseUrl, keepAlive, ProxyConfiguration.defaults(),
                CertificateTrustConfiguration.defaults(), "", defaultDownloadDirectory());
    }

    public AppConfiguration(String ollamaBaseUrl, String keepAlive, ProxyConfiguration proxyConfiguration,
                            String huggingFaceToken, File modelDownloadDirectory) {
        this(ollamaBaseUrl, keepAlive, proxyConfiguration, CertificateTrustConfiguration.defaults(),
                huggingFaceToken, modelDownloadDirectory);
    }

    public AppConfiguration(String ollamaBaseUrl, String keepAlive, ProxyConfiguration proxyConfiguration,
                            CertificateTrustConfiguration certificateTrustConfiguration,
                            String huggingFaceToken, File modelDownloadDirectory) {
        this(ollamaBaseUrl, keepAlive, proxyConfiguration, certificateTrustConfiguration,
                HttpClientConfiguration.defaults(), huggingFaceToken, modelDownloadDirectory);
    }

    public AppConfiguration(String ollamaBaseUrl, String keepAlive, ProxyConfiguration proxyConfiguration,
                            CertificateTrustConfiguration certificateTrustConfiguration,
                            HttpClientConfiguration httpClientConfiguration,
                            String huggingFaceToken, File modelDownloadDirectory) {
        this(ollamaBaseUrl, keepAlive, proxyConfiguration, certificateTrustConfiguration,
                httpClientConfiguration, "Q4_K_M", huggingFaceToken, modelDownloadDirectory);
    }

    public AppConfiguration(String ollamaBaseUrl, String keepAlive, ProxyConfiguration proxyConfiguration,
                            CertificateTrustConfiguration certificateTrustConfiguration,
                            HttpClientConfiguration httpClientConfiguration, String defaultQuantization,
                            String huggingFaceToken, File modelDownloadDirectory) {
        this(ollamaBaseUrl, keepAlive, proxyConfiguration, certificateTrustConfiguration,
                httpClientConfiguration, defaultQuantization, huggingFaceToken, modelDownloadDirectory,
                SpeechToTextConfiguration.defaults(), DEFAULT_HF_SEARCH_SUGGESTIONS);
    }

    private AppConfiguration(String ollamaBaseUrl, String keepAlive, ProxyConfiguration proxyConfiguration,
                             CertificateTrustConfiguration certificateTrustConfiguration,
                             HttpClientConfiguration httpClientConfiguration, String defaultQuantization,
                             String huggingFaceToken, File modelDownloadDirectory,
                             SpeechToTextConfiguration speechToTextConfiguration,
                             String huggingFaceSearchSuggestions) {
        this.ollamaBaseUrl = normalizeBaseUrl(ollamaBaseUrl);
        this.keepAlive = keepAlive == null || keepAlive.trim().length() == 0 ? "5m" : keepAlive.trim();
        this.proxyConfiguration = proxyConfiguration == null ? ProxyConfiguration.defaults() : proxyConfiguration;
        this.certificateTrustConfiguration = certificateTrustConfiguration == null
                ? CertificateTrustConfiguration.defaults() : certificateTrustConfiguration;
        this.httpClientConfiguration = httpClientConfiguration == null
                ? HttpClientConfiguration.defaults() : httpClientConfiguration;
        this.defaultQuantization = defaultQuantization == null || defaultQuantization.trim().length() == 0
                ? "Q4_K_M" : defaultQuantization.trim();
        this.huggingFaceToken = huggingFaceToken == null ? "" : huggingFaceToken.trim();
        this.modelDownloadDirectory = modelDownloadDirectory == null ? defaultDownloadDirectory() : modelDownloadDirectory;
        this.speechToTextConfiguration = speechToTextConfiguration == null
                ? SpeechToTextConfiguration.defaults() : speechToTextConfiguration;
        this.huggingFaceSearchSuggestions = huggingFaceSearchSuggestions == null
                || huggingFaceSearchSuggestions.trim().length() == 0
                ? DEFAULT_HF_SEARCH_SUGGESTIONS : huggingFaceSearchSuggestions;
    }

    /**
     * @return a copy of this configuration with the given speech-to-text settings. Save sites that
     *         rebuild an {@code AppConfiguration} use this to carry the STT settings over.
     */
    public AppConfiguration withSpeechToTextConfiguration(SpeechToTextConfiguration configuration) {
        return new AppConfiguration(ollamaBaseUrl, keepAlive, proxyConfiguration,
                certificateTrustConfiguration, httpClientConfiguration, defaultQuantization,
                huggingFaceToken, modelDownloadDirectory, configuration, huggingFaceSearchSuggestions);
    }

    /**
     * @return a copy of this configuration with the given newline-separated HuggingFace search
     *         suggestions (the Install panel dropdown entries).
     */
    public AppConfiguration withHuggingFaceSearchSuggestions(String suggestions) {
        return new AppConfiguration(ollamaBaseUrl, keepAlive, proxyConfiguration,
                certificateTrustConfiguration, httpClientConfiguration, defaultQuantization,
                huggingFaceToken, modelDownloadDirectory, speechToTextConfiguration, suggestions);
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

    public CertificateTrustConfiguration getCertificateTrustConfiguration() {
        return certificateTrustConfiguration;
    }

    public HttpClientConfiguration getHttpClientConfiguration() {
        return httpClientConfiguration;
    }

    public String getDefaultQuantization() {
        return defaultQuantization;
    }

    public SpeechToTextConfiguration getSpeechToTextConfiguration() {
        return speechToTextConfiguration;
    }

    /** @return the raw newline-separated suggestion list, as persisted. */
    public String getHuggingFaceSearchSuggestionsRaw() {
        return huggingFaceSearchSuggestions;
    }

    /** @return the HuggingFace search suggestions for the Install panel dropdown, in order. */
    public java.util.List<String> getHuggingFaceSearchSuggestions() {
        java.util.List<String> suggestions = new java.util.ArrayList<String>();
        String[] lines = huggingFaceSearchSuggestions.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() > 0 && !suggestions.contains(line)) {
                suggestions.add(line);
            }
        }
        return suggestions;
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
