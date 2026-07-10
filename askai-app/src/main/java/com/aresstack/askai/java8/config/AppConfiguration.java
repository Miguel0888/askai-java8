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
     * VRAM card. Format per line: {@code <term> | <modality>,<modality>} — see
     * {@link HuggingFaceSearchSuggestion}.
     *
     * <p>An audio/vision tag here means a mainstream GGUF repository for that search actually ships
     * the model's encoder (mmproj), so installing it from HuggingFace yields a working multimodal
     * model. The audio entries are verified to include an mmproj: {@code voxtral-mini-3b}
     * (ggml-org/Voxtral-Mini-3B-2507-GGUF) and {@code ultravox}
     * (ggml-org/ultravox-v0_5-llama-3_1-8b-GGUF). Note: gemma-3n's common GGUF repos are
     * language-only (no mmproj), so it is tagged text here; its audio/vision works via
     * {@code ollama pull gemma3n:e4b}, not the single-file HuggingFace import.</p>
     */
    public static final String DEFAULT_HF_SEARCH_SUGGESTIONS =
            "gpt-oss-20b | text\n"
                    + "llama-3.1-8b-instruct | text\n"
                    + "gemma-3-12b-it | text,vision\n"
                    + "qwen2.5-14b-instruct | text\n"
                    + "qwen2.5-coder-14b | text\n"
                    + "phi-4 | text\n"
                    + "mistral-nemo | text\n"
                    + "gemma-3n-e4b | text\n"
                    + "voxtral-mini-3b | text,audio\n"
                    + "ultravox | text,audio";

    // Earlier default suggestion lists that shipped inaccurate audio tags (e.g. gemma-3n as audio).
    // A persisted list identical to one of these was never customized, so upgrade it to the current
    // default instead of freezing the old, misleading tags.
    private static final String[] LEGACY_HF_SEARCH_SUGGESTIONS = {
            "gpt-oss-20b\nllama-3.1-8b-instruct\ngemma-3-12b-it\nqwen2.5-14b-instruct\n"
                    + "qwen2.5-coder-14b\nphi-4\nmistral-nemo\ngemma-3n-e4b\nvoxtral-mini-3b\n"
                    + "qwen3-asr\nultravox",
            "gpt-oss-20b | text\nllama-3.1-8b-instruct | text\ngemma-3-12b-it | text,vision\n"
                    + "qwen2.5-14b-instruct | text\nqwen2.5-coder-14b | text\nphi-4 | text\n"
                    + "mistral-nemo | text\ngemma-3n-e4b | text,audio,vision\nvoxtral-mini-3b | text,audio\n"
                    + "qwen3-asr | audio\nultravox | text,audio"
    };

    /**
     * @return the current default when {@code raw} equals a superseded default list (silent
     *         migration of the inaccurate audio/vision tags), otherwise {@code raw} unchanged.
     */
    public static String migrateSearchSuggestions(String raw) {
        if (raw == null) {
            return DEFAULT_HF_SEARCH_SUGGESTIONS;
        }
        String trimmed = raw.trim();
        for (int i = 0; i < LEGACY_HF_SEARCH_SUGGESTIONS.length; i++) {
            if (trimmed.equals(LEGACY_HF_SEARCH_SUGGESTIONS[i].trim())) {
                return DEFAULT_HF_SEARCH_SUGGESTIONS;
            }
        }
        return raw;
    }

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

    /** @return the parsed HuggingFace search suggestions for the Install panel dropdown, in order. */
    public java.util.List<HuggingFaceSearchSuggestion> getHuggingFaceSearchSuggestions() {
        return HuggingFaceSearchSuggestion.parseList(huggingFaceSearchSuggestions);
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
