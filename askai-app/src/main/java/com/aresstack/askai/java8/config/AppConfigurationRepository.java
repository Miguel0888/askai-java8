package com.aresstack.askai.java8.config;

import com.aresstack.askai.java8.net.CertificateTrustConfiguration;
import com.aresstack.askai.java8.net.HttpClientConfiguration;
import com.aresstack.askai.java8.net.ProxyConfiguration;
import com.aresstack.askai.java8.stt.SpeechToTextConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public final class AppConfigurationRepository {

    private static final String OLLAMA_BASE_URL = "ollama.baseUrl";
    private static final String KEEP_ALIVE = "ollama.keepAlive";
    private static final String OLLAMA_QUANTIZATION = "ollama.quantization";
    private static final String PROXY_MODE = "proxy.mode";
    private static final String PROXY_TEST_URL = "proxy.testUrl";
    private static final String PROXY_PAC_SCRIPT = "proxy.pacUrlDiscoveryScript";
    private static final String PROXY_PAC_URL = "proxy.pacUrl";
    private static final String PROXY_HOST = "proxy.host";
    private static final String PROXY_PORT = "proxy.port";
    private static final String HF_TOKEN = "huggingface.token";
    private static final String DOWNLOAD_DIRECTORY = "huggingface.downloadDirectory";
    private static final String HF_SEARCH_SUGGESTIONS = "huggingface.searchSuggestions";
    private static final String TRUST_JVM_DEFAULT = "trust.jvmDefault";
    private static final String TRUST_WINDOWS_ROOT = "trust.windowsRoot";
    private static final String TRUST_WINDOWS_CA_STORES = "trust.windowsCaStores";
    private static final String HTTP_USER_AGENT = "http.userAgent";
    private static final String HTTP_PREFER_IPV6 = "http.preferIpv6";
    private static final String PROXY_AUTH_MODE = "proxyauth.mode";
    private static final String PROXY_AUTH_USERNAME = "proxyauth.username";
    private static final String PROXY_AUTH_PASSWORD = "proxyauth.password";
    private static final String STT_ENABLED = "stt.enabled";
    private static final String STT_BACKEND = "stt.backend";
    private static final String STT_MODEL = "stt.model";
    private static final String STT_LANGUAGE = "stt.language";
    private static final String STT_PROMPT = "stt.prompt";
    private static final String STT_MAX_FILE_SIZE_MB = "stt.maxFileSizeMb";
    private static final String STT_TIMEOUT_SECONDS = "stt.timeoutSeconds";

    private final File configurationFile;

    public AppConfigurationRepository() {
        this.configurationFile = new File(configurationDirectory(), "askai-java8.properties");
    }

    public AppConfiguration load() {
        if (!configurationFile.isFile()) {
            return AppConfiguration.defaults();
        }
        Properties properties = new Properties();
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(configurationFile);
            properties.load(inputStream);
            AppConfiguration defaults = AppConfiguration.defaults();
            ProxyConfiguration defaultProxy = defaults.getProxyConfiguration();
            CertificateTrustConfiguration defaultTrust = defaults.getCertificateTrustConfiguration();
            HttpClientConfiguration defaultHttp = defaults.getHttpClientConfiguration();
            SpeechToTextConfiguration defaultStt = defaults.getSpeechToTextConfiguration();
            String mode = properties.getProperty(PROXY_MODE, defaultProxy.getModeName());
            SpeechToTextConfiguration stt = new SpeechToTextConfiguration(
                    parseBoolean(properties.getProperty(STT_ENABLED), defaultStt.isEnabled()),
                    SpeechToTextConfiguration.parseBackend(
                            properties.getProperty(STT_BACKEND, defaultStt.getBackend().name())),
                    properties.getProperty(STT_MODEL, defaultStt.getModelName()),
                    properties.getProperty(STT_LANGUAGE, defaultStt.getLanguage()),
                    properties.getProperty(STT_PROMPT, defaultStt.getPrompt()),
                    parseInt(properties.getProperty(STT_MAX_FILE_SIZE_MB,
                            String.valueOf(defaultStt.getMaxFileSizeMb()))),
                    parseInt(properties.getProperty(STT_TIMEOUT_SECONDS,
                            String.valueOf(defaultStt.getTimeoutSeconds()))));
            return new AppConfiguration(
                    properties.getProperty(OLLAMA_BASE_URL, defaults.getOllamaBaseUrl()),
                    properties.getProperty(KEEP_ALIVE, defaults.getKeepAlive()),
                    new ProxyConfiguration(
                            mode,
                            properties.getProperty(PROXY_TEST_URL, defaultProxy.getTestUrl()),
                            properties.getProperty(PROXY_PAC_SCRIPT, defaultProxy.getPacUrlDiscoveryScript()),
                            properties.getProperty(PROXY_PAC_URL, defaultProxy.getPacUrl()),
                            properties.getProperty(PROXY_HOST, defaultProxy.getManualProxyHost()),
                            parseInt(properties.getProperty(PROXY_PORT, String.valueOf(defaultProxy.getManualProxyPort())))),
                    new CertificateTrustConfiguration(
                            parseBoolean(properties.getProperty(TRUST_JVM_DEFAULT), defaultTrust.isUseJvmDefault()),
                            parseBoolean(properties.getProperty(TRUST_WINDOWS_ROOT), defaultTrust.isUseWindowsRoot()),
                            parseBoolean(properties.getProperty(TRUST_WINDOWS_CA_STORES), defaultTrust.isUseWindowsCaStores())),
                    new HttpClientConfiguration(
                            properties.getProperty(HTTP_USER_AGENT, defaultHttp.getUserAgent()),
                            HttpClientConfiguration.parseProxyAuthMode(
                                    properties.getProperty(PROXY_AUTH_MODE, defaultHttp.getProxyAuthMode().name())),
                            properties.getProperty(PROXY_AUTH_USERNAME, defaultHttp.getProxyAuthUsername()),
                            properties.getProperty(PROXY_AUTH_PASSWORD, defaultHttp.getProxyAuthPassword()),
                            parseBoolean(properties.getProperty(HTTP_PREFER_IPV6), defaultHttp.isPreferIpv6())),
                    properties.getProperty(OLLAMA_QUANTIZATION, defaults.getDefaultQuantization()),
                    properties.getProperty(HF_TOKEN, ""),
                    new File(properties.getProperty(DOWNLOAD_DIRECTORY, defaults.getModelDownloadDirectory().getAbsolutePath())))
                    .withSpeechToTextConfiguration(stt)
                    .withHuggingFaceSearchSuggestions(AppConfiguration.migrateSearchSuggestions(
                            properties.getProperty(HF_SEARCH_SUGGESTIONS,
                                    AppConfiguration.DEFAULT_HF_SEARCH_SUGGESTIONS)));
        } catch (IOException ex) {
            return AppConfiguration.defaults();
        } finally {
            closeQuietly(inputStream);
        }
    }

    public void save(AppConfiguration configuration) {
        File directory = configurationFile.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return;
        }
        ProxyConfiguration proxy = configuration.getProxyConfiguration();
        CertificateTrustConfiguration trust = configuration.getCertificateTrustConfiguration();
        HttpClientConfiguration http = configuration.getHttpClientConfiguration();
        Properties properties = new Properties();
        properties.setProperty(OLLAMA_BASE_URL, configuration.getOllamaBaseUrl());
        properties.setProperty(KEEP_ALIVE, configuration.getKeepAlive());
        properties.setProperty(OLLAMA_QUANTIZATION, configuration.getDefaultQuantization());
        properties.setProperty(PROXY_MODE, proxy.getModeName());
        properties.setProperty(PROXY_TEST_URL, proxy.getTestUrl());
        properties.setProperty(PROXY_PAC_SCRIPT, proxy.getPacUrlDiscoveryScript());
        properties.setProperty(PROXY_PAC_URL, proxy.getPacUrl());
        properties.setProperty(PROXY_HOST, proxy.getManualProxyHost());
        properties.setProperty(PROXY_PORT, String.valueOf(proxy.getManualProxyPort()));
        properties.setProperty(TRUST_JVM_DEFAULT, String.valueOf(trust.isUseJvmDefault()));
        properties.setProperty(TRUST_WINDOWS_ROOT, String.valueOf(trust.isUseWindowsRoot()));
        properties.setProperty(TRUST_WINDOWS_CA_STORES, String.valueOf(trust.isUseWindowsCaStores()));
        properties.setProperty(HTTP_USER_AGENT, http.getUserAgent());
        properties.setProperty(HTTP_PREFER_IPV6, String.valueOf(http.isPreferIpv6()));
        properties.setProperty(PROXY_AUTH_MODE, http.getProxyAuthMode().name());
        properties.setProperty(PROXY_AUTH_USERNAME, http.getProxyAuthUsername());
        properties.setProperty(PROXY_AUTH_PASSWORD, http.getProxyAuthPassword());
        SpeechToTextConfiguration stt = configuration.getSpeechToTextConfiguration();
        properties.setProperty(STT_ENABLED, String.valueOf(stt.isEnabled()));
        properties.setProperty(STT_BACKEND, stt.getBackend().name());
        properties.setProperty(STT_MODEL, stt.getModelName());
        properties.setProperty(STT_LANGUAGE, stt.getLanguage());
        properties.setProperty(STT_PROMPT, stt.getPrompt());
        properties.setProperty(STT_MAX_FILE_SIZE_MB, String.valueOf(stt.getMaxFileSizeMb()));
        properties.setProperty(STT_TIMEOUT_SECONDS, String.valueOf(stt.getTimeoutSeconds()));
        properties.setProperty(HF_TOKEN, configuration.getHuggingFaceToken());
        properties.setProperty(DOWNLOAD_DIRECTORY, configuration.getModelDownloadDirectory().getAbsolutePath());
        properties.setProperty(HF_SEARCH_SUGGESTIONS, configuration.getHuggingFaceSearchSuggestionsRaw());
        FileOutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(configurationFile);
            properties.store(outputStream, "AskAI Java 8 configuration");
        } catch (IOException ignored) {
        } finally {
            closeQuietly(outputStream);
        }
    }

    private File configurationDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData != null && appData.trim().length() > 0) {
            return new File(appData, ".askai-java8");
        }
        return new File(System.getProperty("user.home"), ".askai-java8");
    }

    private boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.trim().length() == 0) {
            return fallback;
        }
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        return fallback;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void closeQuietly(FileInputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(FileOutputStream outputStream) {
        if (outputStream == null) {
            return;
        }
        try {
            outputStream.close();
        } catch (IOException ignored) {
        }
    }
}
