package com.aresstack.askai.java8.tts;

/**
 * Persisted speech-output (read-aloud) settings. The engine default is deliberately
 * {@link Engine#WINDOWS} — the OS voice needs no download and always works; a Piper MODEL voice is
 * an explicit opt-in (chat settings → Audio &amp; Dictation, or the post-install prompt in
 * Models → Setup → Speech Output). Kept OUT of {@code AppConfiguration} on purpose: these settings
 * live in their own small properties file (see {@link TtsSettingsStore}), like the audio profiles.
 */
public final class TextToSpeechSettings {

    public enum Engine {
        /** The Windows OS voice (System.Speech / SAPI) — the zero-setup default. */
        WINDOWS,
        /** A locally installed Piper model voice — runs entirely on the CPU. */
        PIPER
    }

    /** Upper bound for waiting on the FIRST audio byte (engine start + model load), not playback. */
    public static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 60;
    /** Connect/read timeout for voice/engine downloads (per network operation, not per file). */
    public static final int DEFAULT_NETWORK_TIMEOUT_SECONDS = 60;

    private final Engine engine;
    private final String voiceId;
    private final int startupTimeoutSeconds;
    private final int networkTimeoutSeconds;

    public TextToSpeechSettings(Engine engine, String voiceId, int startupTimeoutSeconds) {
        this(engine, voiceId, startupTimeoutSeconds, DEFAULT_NETWORK_TIMEOUT_SECONDS);
    }

    public TextToSpeechSettings(Engine engine, String voiceId, int startupTimeoutSeconds,
                                int networkTimeoutSeconds) {
        this.engine = engine == null ? Engine.WINDOWS : engine;
        this.voiceId = voiceId == null ? "" : voiceId.trim();
        this.startupTimeoutSeconds = startupTimeoutSeconds > 0
                ? startupTimeoutSeconds : DEFAULT_STARTUP_TIMEOUT_SECONDS;
        this.networkTimeoutSeconds = networkTimeoutSeconds > 0
                ? networkTimeoutSeconds : DEFAULT_NETWORK_TIMEOUT_SECONDS;
    }

    public static TextToSpeechSettings defaults() {
        return new TextToSpeechSettings(Engine.WINDOWS, "", DEFAULT_STARTUP_TIMEOUT_SECONDS,
                DEFAULT_NETWORK_TIMEOUT_SECONDS);
    }

    public Engine getEngine() {
        return engine;
    }

    /** @return the selected Piper voice id (see {@link PiperVoiceCatalog}), or "" when none. */
    public String getVoiceId() {
        return voiceId;
    }

    public int getStartupTimeoutSeconds() {
        return startupTimeoutSeconds;
    }

    public int getNetworkTimeoutSeconds() {
        return networkTimeoutSeconds;
    }

    public TextToSpeechSettings withEngine(Engine value) {
        return new TextToSpeechSettings(value, voiceId, startupTimeoutSeconds, networkTimeoutSeconds);
    }

    public TextToSpeechSettings withVoiceId(String value) {
        return new TextToSpeechSettings(engine, value, startupTimeoutSeconds, networkTimeoutSeconds);
    }

    public static Engine parseEngine(String value) {
        if (value == null) {
            return Engine.WINDOWS;
        }
        try {
            return Engine.valueOf(value.trim());
        } catch (IllegalArgumentException invalid) {
            return Engine.WINDOWS;
        }
    }
}
