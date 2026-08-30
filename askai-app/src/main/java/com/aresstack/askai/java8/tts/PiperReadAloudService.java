package com.aresstack.askai.java8.tts;

import com.aresstack.askai.agent.model.speech.SpeechSynthesisPort;

/**
 * The host's {@link SpeechSynthesisPort} over the Piper store + settings, PER LANGUAGE: active for
 * a language only when the user explicitly selected a Piper voice for it (chat settings → Audio
 * &amp; Dictation) AND engine + voice are installed. Settings are re-read per call — a change in
 * the dialog affects the very next speak, no restart. Failures return {@code false} so callers
 * fall back to their default voice (read-aloud must never go silent because of a model problem).
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
    public boolean isModelVoiceActive(String languageCode) {
        return activeVoice(settings.load(), languageCode) != null;
    }

    @Override
    public String describeActiveVoice(String languageCode) {
        PiperVoice voice = activeVoice(settings.load(), languageCode);
        return voice == null ? "" : voice.toString();
    }

    @Override
    public boolean speak(String plainText, String languageCode) {
        TextToSpeechSettings current = settings.load();
        PiperVoice voice = activeVoice(current, languageCode);
        if (voice == null) {
            return false;
        }
        try {
            PiperSpeechSynthesizer.Utterance utterance = synthesizer.speak(
                    store, voice, plainText, current.getStartupTimeoutSeconds());
            System.err.println("[tts] " + voice.getId() + ": " + utterance);
            if (utterance.getPcmBytes() == 0) {
                // The engine ran but said NOTHING — that must fall back audibly, never end silent.
                System.err.println("[tts] no audio produced; piper log: "
                        + utterance.getEngineLogTail());
                return false;
            }
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

    private PiperVoice activeVoice(TextToSpeechSettings current, String languageCode) {
        TextToSpeechSettings.Selection selection = current.selectionFor(languageCode);
        if (selection.getEngine() != TextToSpeechSettings.Engine.PIPER) {
            return null;
        }
        PiperVoice voice = PiperVoiceCatalog.findById(selection.getVoiceId());
        // The selected voice must MATCH the language — a stale cross-language id never speaks.
        return voice != null && voice.getLanguageCode().equals(languageCode)
                && store.isReadyToSpeak(voice) ? voice : null;
    }
}
