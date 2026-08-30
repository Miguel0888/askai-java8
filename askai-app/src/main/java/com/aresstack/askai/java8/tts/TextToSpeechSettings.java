package com.aresstack.askai.java8.tts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted speech-output (read-aloud) settings, PER LANGUAGE — like the per-language NLP models:
 * German and English each get their own engine/voice choice, so an English answer is never read
 * with a German voice. The per-language default is deliberately {@link Engine#WINDOWS} — the OS
 * voice needs no download and always works; a Piper MODEL voice is an explicit opt-in (chat
 * settings → Audio &amp; Dictation). Kept OUT of {@code AppConfiguration} on purpose: these
 * settings live in their own small properties file (see {@link TtsSettingsStore}).
 */
public final class TextToSpeechSettings {

    public enum Engine {
        /** The Windows OS voice (System.Speech / SAPI) — the zero-setup default per language. */
        WINDOWS,
        /** A locally installed Piper model voice — runs entirely on the CPU. */
        PIPER
    }

    /** One language's choice: the engine, and (for PIPER) the selected voice id. */
    public static final class Selection {
        public static final Selection WINDOWS_DEFAULT = new Selection(Engine.WINDOWS, "");

        private final Engine engine;
        private final String voiceId;

        public Selection(Engine engine, String voiceId) {
            this.engine = engine == null ? Engine.WINDOWS : engine;
            this.voiceId = voiceId == null ? "" : voiceId.trim();
        }

        public Engine getEngine() {
            return engine;
        }

        public String getVoiceId() {
            return voiceId;
        }
    }

    /** The languages a voice can be selected for (ISO-639-1), in display order. */
    public static final String[] LANGUAGE_CODES = {"de", "en"};

    /** Upper bound for waiting on the FIRST audio byte (engine start + model load), not playback. */
    public static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 60;
    /** Connect/read timeout for voice/engine downloads (per network operation, not per file). */
    public static final int DEFAULT_NETWORK_TIMEOUT_SECONDS = 60;

    private final Map<String, Selection> byLanguage;
    private final int startupTimeoutSeconds;
    private final int networkTimeoutSeconds;

    public TextToSpeechSettings(Map<String, Selection> byLanguage, int startupTimeoutSeconds,
                                int networkTimeoutSeconds) {
        Map<String, Selection> selections = new LinkedHashMap<String, Selection>();
        if (byLanguage != null) {
            selections.putAll(byLanguage);
        }
        this.byLanguage = Collections.unmodifiableMap(selections);
        this.startupTimeoutSeconds = startupTimeoutSeconds > 0
                ? startupTimeoutSeconds : DEFAULT_STARTUP_TIMEOUT_SECONDS;
        this.networkTimeoutSeconds = networkTimeoutSeconds > 0
                ? networkTimeoutSeconds : DEFAULT_NETWORK_TIMEOUT_SECONDS;
    }

    public static TextToSpeechSettings defaults() {
        return new TextToSpeechSettings(null, DEFAULT_STARTUP_TIMEOUT_SECONDS,
                DEFAULT_NETWORK_TIMEOUT_SECONDS);
    }

    /** @return this language's choice; unknown/unset languages are the Windows default. */
    public Selection selectionFor(String languageCode) {
        Selection selection = languageCode == null ? null : byLanguage.get(languageCode.trim());
        return selection == null ? Selection.WINDOWS_DEFAULT : selection;
    }

    public TextToSpeechSettings withSelection(String languageCode, Engine engine, String voiceId) {
        Map<String, Selection> selections = new LinkedHashMap<String, Selection>(byLanguage);
        selections.put(languageCode, new Selection(engine, voiceId));
        return new TextToSpeechSettings(selections, startupTimeoutSeconds, networkTimeoutSeconds);
    }

    /** Visible for the store: every explicitly configured language. */
    Map<String, Selection> selections() {
        return byLanguage;
    }

    public int getStartupTimeoutSeconds() {
        return startupTimeoutSeconds;
    }

    public int getNetworkTimeoutSeconds() {
        return networkTimeoutSeconds;
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
