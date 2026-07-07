package com.aresstack.askai.java8.config;

import com.aresstack.askai.java8.net.ProxyConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public final class AppConfigurationRepository {

    private static final String OLLAMA_BASE_URL = "ollama.baseUrl";
    private static final String KEEP_ALIVE = "ollama.keepAlive";
    private static final String PROXY_ENABLED = "proxy.enabled";
    private static final String PROXY_HOST = "proxy.host";
    private static final String PROXY_PORT = "proxy.port";
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
            return new AppConfiguration(
                    properties.getProperty(OLLAMA_BASE_URL, defaults.getOllamaBaseUrl()),
                    properties.getProperty(KEEP_ALIVE, defaults.getKeepAlive()),
                    new ProxyConfiguration(
                            Boolean.parseBoolean(properties.getProperty(PROXY_ENABLED, "false")),
                            properties.getProperty(PROXY_HOST, ""),
                            parseInt(properties.getProperty(PROXY_PORT, "0"))),
                    properties.getProperty(HF_TOKEN, ""),
                    new File(properties.getProperty(DOWNLOAD_DIRECTORY,
                            defaults.getModelDownloadDirectory().getAbsolutePath())));
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
        Properties properties = new Properties();
        properties.setProperty(OLLAMA_BASE_URL, configuration.getOllamaBaseUrl());
        properties.setProperty(KEEP_ALIVE, configuration.getKeepAlive());
        properties.setProperty(PROXY_ENABLED, String.valueOf(configuration.getProxyConfiguration().isEnabled()));
        properties.setProperty(PROXY_HOST, configuration.getProxyConfiguration().getHost());
        properties.setProperty(PROXY_PORT, String.valueOf(configuration.getProxyConfiguration().getPort()));
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
