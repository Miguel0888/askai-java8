package com.aresstack.askai.research.agent;

import com.aresstack.askai.agent.model.speech.SpeechSynthesisPort;

/**
 * The read-aloud VOICE CHOICE in one place: when the host publishes a {@link SpeechSynthesisPort}
 * with an active MODEL voice (chat settings → Audio &amp; Dictation), answers are spoken with it;
 * otherwise — no port (older host), no model voice selected, or the model voice failed — the
 * plugin's own {@link WindowsSpeech} default speaks instead. Read-aloud therefore never goes
 * silent because of a model problem, and the Windows voice stays the zero-setup default.
 */
final class ReadAloudVoice {

    /** The plugin-side default voice; productive impl wraps {@link WindowsSpeech}. */
    interface Fallback {
        void speak(String markdown);

        void stop();
    }

    private final Fallback fallback;
    private volatile SpeechSynthesisPort modelVoice;

    /** Productive: the Windows OS voice as the default. */
    ReadAloudVoice() {
        this(new Fallback() {
            private final WindowsSpeech windows = new WindowsSpeech();

            public void speak(String markdown) {
                windows.speak(markdown);
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

    /** Blocking (call off the EDT): model voice when active, otherwise the default voice. */
    void speak(String markdown) {
        SpeechSynthesisPort port = modelVoice;
        if (port != null && port.isModelVoiceActive()) {
            fallback.stop(); // never two voices at once
            if (port.speak(WindowsSpeech.plainTextForSpeech(markdown))) {
                return;
            }
            // The model voice failed mid-setup (engine gone, audio line busy, …) → default voice.
        }
        fallback.speak(markdown);
    }

    void stop() {
        SpeechSynthesisPort port = modelVoice;
        if (port != null) {
            port.stop();
        }
        fallback.stop();
    }
}
