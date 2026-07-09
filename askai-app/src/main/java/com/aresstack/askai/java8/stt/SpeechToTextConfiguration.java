package com.aresstack.askai.java8.stt;

/**
 * Persisted speech-to-text settings. The backend is an extension point: only
 * {@link Backend#OLLAMA} exists today (the OpenAI-compatible {@code /v1/audio/transcriptions}
 * endpoint, reusing the configured Ollama base URL), but the enum keeps the door open for
 * whisper.cpp / faster-whisper / other OpenAI-compatible servers.
 */
public final class SpeechToTextConfiguration {

    public enum Backend {
        OLLAMA
    }

    public static final int DEFAULT_MAX_FILE_SIZE_MB = 200;
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    private final boolean enabled;
    private final Backend backend;
    private final String modelName;
    private final String language;
    private final String prompt;
    private final int maxFileSizeMb;
    private final int timeoutSeconds;

    public SpeechToTextConfiguration(boolean enabled, Backend backend, String modelName, String language,
                                     String prompt, int maxFileSizeMb, int timeoutSeconds) {
        this.enabled = enabled;
        this.backend = backend == null ? Backend.OLLAMA : backend;
        this.modelName = modelName == null ? "" : modelName.trim();
        this.language = language == null ? "" : language.trim();
        this.prompt = prompt == null ? "" : prompt.trim();
        this.maxFileSizeMb = maxFileSizeMb > 0 ? maxFileSizeMb : DEFAULT_MAX_FILE_SIZE_MB;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    }

    /** Enabled by default with no dedicated model: the chat panel then falls back to the chat model. */
    public static SpeechToTextConfiguration defaults() {
        return new SpeechToTextConfiguration(true, Backend.OLLAMA, "", "auto", "",
                DEFAULT_MAX_FILE_SIZE_MB, DEFAULT_TIMEOUT_SECONDS);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Backend getBackend() {
        return backend;
    }

    /** @return the dedicated STT model, or "" when the current chat model should be used. */
    public String getModelName() {
        return modelName;
    }

    /** @return the default language hint; "auto" or "" means let the model detect. */
    public String getLanguage() {
        return language;
    }

    public String getPrompt() {
        return prompt;
    }

    public int getMaxFileSizeMb() {
        return maxFileSizeMb;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public static Backend parseBackend(String value) {
        if (value == null) {
            return Backend.OLLAMA;
        }
        try {
            return Backend.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            return Backend.OLLAMA;
        }
    }
}
