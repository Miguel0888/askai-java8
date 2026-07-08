package com.aresstack.askai.java8.config;

import com.aresstack.askai.java8.net.CertificateTrustConfiguration;
import com.aresstack.askai.java8.net.ProxyConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public final class AppConfigurationRepository {

    private static final String OLLAMA_BASE_URL = "ollama.baseUrl";
    private static final String KEEP_ALIVE = "ollama.keepAlive";
    private static final String PROXY_MODE = "proxy.mode";
    private static final String PROXY_TEST_URL = "proxy.testUrl";
    private static final String PROXY_PAC_SCRIPT = "proxy.pacUrlDiscoveryScript";
    private static final String PROXY_PAC_URL = "proxy.pacUrl";
    private static final String PROXY_HOST = "proxy.host";
    private static final String PROXY_PORT = "proxy.port";
    private static final String TRUST_JVM_DEFAULT = "trust.jvmDefault";
    private static final String TRUST_WINDOWS_ROOT = "trust.windowsRoot";
    private static final String TRUST_WINDOWS_CA_STORES = "trust.windowsCaStores";
    private static final String HF_TOKEN = "huggingface.token";
    private static final String DOWNLOAD_DIRECTORY = "huggingface.downloadDirectory";

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
            String mode = properties.getProperty(PROXY_MODE, defaultProxy.getModeName());
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
                            parseBoolean(properties, TRUST_JVM_DEFAULT, defaultTrust.isUseJvmDefaultTrustStore()),
                            parseBoolean(properties, TRUST_WINDOWS_ROOT, defaultTrust.isUseWindowsRootStore()),
                            parseBoolean(properties, TRUST_WINDOWS_CA_STORES, defaultTrust.isUseWindowsCaStores())),
                    properties.getProperty(HF_TOKEN, ""),
                    new File(properties.getProperty(DOWNLOAD_DIRECTORY, defaults.getModelDownloadDirectory().getAbsolutePath())));
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
        Properties properties = new Properties();
        properties.setProperty(OLLAMA_BASE_URL, configuration.getOllamaBaseUrl());
        properties.setProperty(KEEP_ALIVE, configuration.getKeepAlive());
        properties.setProperty(PROXY_MODE, proxy.getModeName());
        properties.setProperty(PROXY_TEST_URL, proxy.getTestUrl());
        properties.setProperty(PROXY_PAC_SCRIPT, proxy.getPacUrlDiscoveryScript());
        properties.setProperty(PROXY_PAC_URL, proxy.getPacUrl());
        properties.setProperty(PROXY_HOST, proxy.getManualProxyHost());
        properties.setProperty(PROXY_PORT, String.valueOf(proxy.getManualProxyPort()));
        properties.setProperty(TRUST_JVM_DEFAULT, String.valueOf(trust.isUseJvmDefaultTrustStore()));
        properties.setProperty(TRUST_WINDOWS_ROOT, String.valueOf(trust.isUseWindowsRootStore()));
        properties.setProperty(TRUST_WINDOWS_CA_STORES, String.valueOf(trust.isUseWindowsCaStores()));
        properties.setProperty(HF_TOKEN, configuration.getHuggingFaceToken());
        properties.setProperty(DOWNLOAD_DIRECTORY, configuration.getModelDownloadDirectory().getAbsolutePath());
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

    private boolean parseBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
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
