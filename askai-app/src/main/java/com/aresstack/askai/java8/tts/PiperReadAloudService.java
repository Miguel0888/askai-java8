package com.aresstack.askai.java8.tts;

import com.aresstack.askai.agent.model.speech.SpeechSynthesisPort;
import com.aresstack.askai.java8.text.ChatTextAnalysisService;

import java.util.Collections;
import java.util.List;

/**
 * The host's {@link SpeechSynthesisPort}: the WHOLE read-aloud orchestration lives here. An
 * utterance is first run through the on-demand {@link ChatTextAnalysisService} (settings opt-outs:
 * mixed-language split, sentence-wise synthesis — both default ON): NLP-detected German/English
 * passages are routed to their OWN voice, and synthesis runs sentence by sentence with the pieces
 * played in reading order. Per segment the language's configured Piper model voice speaks when it
 * is installed; otherwise the culture-matched Windows voice does — so a mixed answer flows through
 * both voices seamlessly. Without the NLP models (or with both checkboxes off) everything degrades
 * to the previous single-language behavior. Settings are re-read per call; failures return
 * {@code false} so the plugin's own Windows fallback still guarantees audibility.
 *
 * <p>The analysis service is acquired per utterance (listener token = the utterance) and released
 * afterwards — with the service's grace period, back-to-back answers reuse the loaded models and
 * the engine stops shortly after the last utterance, exactly the manual-service lifecycle.</p>
 */
public final class PiperReadAloudService implements SpeechSynthesisPort {

    /** Grace before the analysis engine unloads after the last utterance (reload avoidance). */
    private static final long ANALYSIS_STOP_GRACE_MILLIS = 60_000L;

    private final TtsSettingsStore settings;
    private final PiperTtsStore store;
    private final ChatTextAnalysisService analysis;
    private final PiperSpeechSynthesizer synthesizer = new PiperSpeechSynthesizer();
    private final WindowsSapiVoice windowsVoice = new WindowsSapiVoice();
    private final Object lock = new Object();
    private long generation;

    public PiperReadAloudService() {
        this(new TtsSettingsStore(), new PiperTtsStore(),
                new ChatTextAnalysisService(
                        new com.aresstack.askai.java8.text.OpenNlpAnalysisEngineLoader(),
                        ANALYSIS_STOP_GRACE_MILLIS));
    }

    /** Visible for tests: explicit stores + analysis service (null = no analysis at all). */
    public PiperReadAloudService(TtsSettingsStore settings, PiperTtsStore store) {
        this(settings, store, null);
    }

    public PiperReadAloudService(TtsSettingsStore settings, PiperTtsStore store,
                                 ChatTextAnalysisService analysis) {
        this.settings = settings;
        this.store = store;
        this.analysis = analysis;
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
        final long myGeneration;
        synchronized (lock) {
            myGeneration = ++generation;
        }
        List<ChatTextAnalysisService.Segment> segments = segments(plainText, languageCode, current);
        boolean anySpoken = false;
        for (ChatTextAnalysisService.Segment segment : segments) {
            for (String chunk : segment.getSentences()) {
                synchronized (lock) {
                    if (generation != myGeneration) {
                        return anySpoken; // stopped (or replaced) — interrupted still counts
                    }
                }
                if (chunk.trim().isEmpty()) {
                    continue;
                }
                anySpoken |= speakChunk(chunk, segment.getLanguageCode(), current);
            }
        }
        return anySpoken || plainText == null || plainText.trim().isEmpty();
    }

    /** One chunk, one voice: the language's Piper model voice when ready, else Windows SAPI. */
    private boolean speakChunk(String chunk, String languageCode, TextToSpeechSettings current) {
        PiperVoice voice = activeVoice(current, languageCode);
        if (voice != null) {
            try {
                PiperSpeechSynthesizer.Utterance utterance = synthesizer.speak(
                        store, voice, chunk, current.getStartupTimeoutSeconds());
                if (utterance.getPcmBytes() > 0) {
                    return true;
                }
                System.err.println("[tts] " + voice.getId() + " produced no audio; piper log: "
                        + utterance.getEngineLogTail());
            } catch (Exception failed) {
                System.err.println("[tts] model voice failed (" + voice.getId() + "): "
                        + failed.getMessage());
            }
            // The model voice failed for THIS chunk — the Windows voice keeps the flow audible.
        }
        String reason = windowsVoice.speak(chunk, languageCode,
                current.getStartupTimeoutSeconds());
        if (!reason.isEmpty()) {
            System.err.println("[tts] windows voice failed: " + reason);
            return false;
        }
        return true;
    }

    /** The utterance's segments: NLP-analysed when requested and available, else one segment. */
    private List<ChatTextAnalysisService.Segment> segments(String plainText, String languageCode,
                                                           TextToSpeechSettings current) {
        boolean wantsAnalysis = current.isMixedLanguageSplit() || current.isSentenceWiseSynthesis();
        if (analysis == null || !wantsAnalysis) {
            return Collections.singletonList(singleSegment(plainText, languageCode));
        }
        Object listener = new Object(); // this utterance IS the service's listener
        analysis.acquire(listener);
        try {
            return analysis.segment(plainText, languageCode,
                    current.isMixedLanguageSplit(), current.isSentenceWiseSynthesis());
        } finally {
            analysis.release(listener); // engine stops after the grace once no one else listens
        }
    }

    private static ChatTextAnalysisService.Segment singleSegment(String text, String language) {
        return new ChatTextAnalysisService.Segment(language,
                Collections.singletonList(text == null ? "" : text));
    }

    @Override
    public void stop() {
        synchronized (lock) {
            generation++; // the chunk loop ends before its next chunk
        }
        synthesizer.stop();
        windowsVoice.stop();
    }

    @Override
    public boolean isReadAloudActiveByDefault() {
        return settings.load().isReadAloudAutoStart();
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
