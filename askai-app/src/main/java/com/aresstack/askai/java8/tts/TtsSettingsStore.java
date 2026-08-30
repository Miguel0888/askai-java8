package com.aresstack.askai.java8.tts;

import com.aresstack.askai.java8.settings.AskAiPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The speech-output settings file: {@code %APPDATA%\.askai-java8\askai-tts.properties}. A separate
 * file (not {@code askai-java8.properties}) so the central repository's whole-file rewrites can
 * never clobber these keys and vice versa. Reads are cheap and always fresh — callers load per use
 * instead of caching, so a change from the settings dialog is picked up by the very next speak.
 */
public final class TtsSettingsStore {

    private static final String KEY_ENGINE = "tts.engine";
    private static final String KEY_VOICE = "tts.voice";
    private static final String KEY_STARTUP_TIMEOUT_SECONDS = "tts.startupTimeoutSeconds";
    private static final String KEY_NETWORK_TIMEOUT_SECONDS = "tts.networkTimeoutSeconds";

    private final Path file;

    public TtsSettingsStore() {
        this(AskAiPaths.appDirectory().resolve("askai-tts.properties"));
    }

    /** Visible for tests: an explicit file location. */
    public TtsSettingsStore(Path file) {
        this.file = file;
    }

    public TextToSpeechSettings load() {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException unreadable) {
                return TextToSpeechSettings.defaults(); // a broken file must never break speaking
            }
        }
        return new TextToSpeechSettings(
                TextToSpeechSettings.parseEngine(properties.getProperty(KEY_ENGINE)),
                properties.getProperty(KEY_VOICE, ""),
                parseInt(properties.getProperty(KEY_STARTUP_TIMEOUT_SECONDS),
                        TextToSpeechSettings.DEFAULT_STARTUP_TIMEOUT_SECONDS),
                parseInt(properties.getProperty(KEY_NETWORK_TIMEOUT_SECONDS),
                        TextToSpeechSettings.DEFAULT_NETWORK_TIMEOUT_SECONDS));
    }

    public void save(TextToSpeechSettings settings) throws IOException {
        TextToSpeechSettings value = settings == null ? TextToSpeechSettings.defaults() : settings;
        Properties properties = new Properties();
        properties.setProperty(KEY_ENGINE, value.getEngine().name());
        properties.setProperty(KEY_VOICE, value.getVoiceId());
        properties.setProperty(KEY_STARTUP_TIMEOUT_SECONDS,
                String.valueOf(value.getStartupTimeoutSeconds()));
        properties.setProperty(KEY_NETWORK_TIMEOUT_SECONDS,
                String.valueOf(value.getNetworkTimeoutSeconds()));
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "AskAI speech output (read-aloud) settings");
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
