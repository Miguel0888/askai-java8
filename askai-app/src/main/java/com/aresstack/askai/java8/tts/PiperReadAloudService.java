package com.aresstack.askai.java8.tts;

import com.aresstack.askai.agent.model.speech.SpeechSynthesisPort;

/**
 * The host's {@link SpeechSynthesisPort} over the Piper store + settings: active only when the
 * user explicitly selected a Piper voice (chat settings → Audio &amp; Dictation) AND engine + voice
 * are installed. Settings are re-read per call — a change in the dialog affects the very next
 * speak, no restart. Failures return {@code false} so callers fall back to their default voice
 * (read-aloud must never go silent because of a model problem).
 */
public final class PiperReadAloudService implements SpeechSynthesisPort {

    private final TtsSettingsStore settings;
    private final PiperTtsStore store;
    private final PiperSpeechSynthesizer synthesizer = new PiperSpeechSynthesizer();

    public PiperReadAloudService() {
        this(new TtsSettingsStore(), new PiperTtsStore());
    }

    /** Visible for tests: explicit stores. */
    public PiperReadAloudService(TtsSettingsStore settings, PiperTtsStore store) {
        this.settings = settings;
        this.store = store;
    }

    @Override
    public boolean isModelVoiceActive() {
        return activeVoice() != null;
    }

    @Override
    public String describeActiveVoice() {
        PiperVoice voice = activeVoice();
        return voice == null ? "" : voice.toString();
    }

    @Override
    public boolean speak(String plainText) {
        TextToSpeechSettings current = settings.load();
        PiperVoice voice = activeVoice(current);
        if (voice == null) {
            return false;
        }
        try {
            synthesizer.speak(store, voice, plainText, current.getStartupTimeoutSeconds());
            return true;
        } catch (Exception failed) {
            System.err.println("[tts] model voice failed (" + voice.getId() + "): "
                    + failed.getMessage());
            return false;
        }
    }

    @Override
    public void stop() {
        synthesizer.stop();
    }

    private PiperVoice activeVoice() {
        return activeVoice(settings.load());
    }

    private PiperVoice activeVoice(TextToSpeechSettings current) {
        if (current.getEngine() != TextToSpeechSettings.Engine.PIPER) {
            return null;
        }
        PiperVoice voice = PiperVoiceCatalog.findById(current.getVoiceId());
        return voice != null && store.isReadyToSpeak(voice) ? voice : null;
    }
}
