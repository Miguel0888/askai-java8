package com.aresstack.askai.java8.settings;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Centralizes user-specific storage locations. Java 8 port: the app directory and settings file
 * match the existing {@code AppConfigurationRepository} locations so both views of the
 * configuration stay in sync.
 */
public final class AskAiPaths {

    private static final String APP_DIR_NAME = ".askai-java8";

    private AskAiPaths() {
    }

    public static Path appDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.trim().isEmpty()) {
            appData = System.getProperty("user.home");
        }
        return Paths.get(appData, APP_DIR_NAME);
    }

    public static Path settingsFile() {
        return appDirectory().resolve("askai-java8.properties");
    }

    public static Path defaultModelRoot() {
        return appDirectory().resolve("models");
    }
}
