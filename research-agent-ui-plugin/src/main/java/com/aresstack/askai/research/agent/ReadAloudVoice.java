package com.aresstack.askai.research.agent;

import com.aresstack.askai.agent.model.speech.SpeechSynthesisPort;

/**
 * The read-aloud VOICE CHOICE in one place, PER LANGUAGE: when the host publishes a
 * {@link SpeechSynthesisPort} with an active MODEL voice for the answer's language (chat settings
 * → Audio &amp; Dictation), it speaks; otherwise — no port (older host), no model voice for that
 * language, or the model voice failed — the plugin's own {@link WindowsSpeech} default speaks
 * instead, ALSO in the answer's language (Windows picks an installed voice of that culture, so
 * English text is never read with a German accent). Read-aloud therefore never goes silent
 * because of a model problem, and the Windows voice stays the zero-setup default per language.
 */
final class ReadAloudVoice {

    /** The plugin-side default voice; productive impl wraps {@link WindowsSpeech}. */
    interface Fallback {
        void speak(String markdown, String languageCode);

        void stop();
    }

    private final Fallback fallback;
    private volatile SpeechSynthesisPort modelVoice;

    /** Productive: the Windows OS voice (culture-matched) as the default. */
    ReadAloudVoice() {
        this(new Fallback() {
            private final WindowsSpeech windows = new WindowsSpeech();

            public void speak(String markdown, String languageCode) {
                windows.speak(markdown, languageCode);
            }

            public void stop() {
                windows.stop();
            }
        });
    }

    ReadAloudVoice(Fallback fallback) {
        this.fallback = fallback;
    }

    /** The host's speech-output service, or null (older host / no service). */
    void setModelVoice(SpeechSynthesisPort port) {
        this.modelVoice = port;
    }

    /**
     * Blocking (call off the EDT): the language's model voice when active, else the default.
     * HARDENED against every failure shape — including linkage/runtime ERRORS from a host/plugin
     * version skew: whatever goes wrong on the model path is logged and the Windows voice speaks.
     * Read-aloud may sound plain, but it must never be silent.
     */
    void speak(String markdown, String languageCode) {
        try {
            SpeechSynthesisPort port = modelVoice;
            if (port != null && port.isModelVoiceActive(languageCode)) {
                fallback.stop(); // never two voices at once
                boolean spoke = port.speak(WindowsSpeech.plainTextForSpeech(markdown), languageCode);
                System.err.println("[read-aloud] model voice (" + languageCode + ") spoke=" + spoke);
                if (spoke) {
                    return;
                }
                // The model voice failed mid-setup (engine gone, audio line busy, …) → default voice.
            }
        } catch (Throwable modelPathBroken) {
            System.err.println("[read-aloud] model-voice path failed (" + languageCode + "): "
                    + modelPathBroken);
        }
        fallback.speak(markdown, languageCode);
    }

    void stop() {
        try {
            SpeechSynthesisPort port = modelVoice;
            if (port != null) {
                port.stop();
            }
        } catch (Throwable modelPathBroken) {
            System.err.println("[read-aloud] model-voice stop failed: " + modelPathBroken);
        }
        fallback.stop();
    }
}
