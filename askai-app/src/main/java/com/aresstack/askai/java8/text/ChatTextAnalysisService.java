package com.aresstack.askai.java8.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ON-DEMAND chat-text analysis service: splits a message into sentences and detects the
 * language of each, so read-aloud can route German and English passages to their own voices and
 * synthesize sentence by sentence. Lifecycle like a manually started Windows service: it does NOT
 * run permanently — a feature that needs it {@link #acquire(Object) acquires} it with a listener
 * token (the first listener starts the engine, i.e. loads the OpenNLP models), and the engine
 * stops only after the LAST listener {@link #release(Object) released} (with a short grace period
 * so back-to-back utterances do not reload the models every time).
 *
 * <p>Degradation is built in, never an error: without a loaded engine (models not installed, or
 * no listener) {@link #segment} returns the whole text as ONE segment in the fallback language —
 * exactly the pre-analysis behavior.</p>
 */
public final class ChatTextAnalysisService {

    /** One same-language run of the text, in reading order. */
    public static final class Segment {
        private final String languageCode;
        private final List<String> sentences;

        public Segment(String languageCode, List<String> sentences) {
            this.languageCode = languageCode;
            this.sentences = Collections.unmodifiableList(new ArrayList<String>(sentences));
        }

        public String getLanguageCode() {
            return languageCode;
        }

        /** The chunks to synthesize, in order (one per sentence, or one joined chunk). */
        public List<String> getSentences() {
            return sentences;
        }
    }

    /** Detects a sentence's language: ISO-639-1 ("de"/"en") or "" when unknown/unsupported. */
    public interface LanguageDetector {
        String detectLanguage(String sentence);
    }

    /** Splits text into sentences (reading order, no empties). */
    public interface SentenceSplitter {
        List<String> split(String text);
    }

    /** The loaded analysis engine; closed when the service stops. */
    public interface Engine {
        /** @return the detector, or null when the language-detection model is not installed. */
        LanguageDetector languageDetector();

        /** @return the splitter for this language — never null (a naive fallback is fine). */
        SentenceSplitter splitterFor(String languageCode);

        void close();
    }

    /** Loads the engine from the installed models when the first listener arrives. */
    public interface EngineLoader {
        Engine load();
    }

    private final EngineLoader loader;
    private final long stopGraceMillis;
    private final Map<Object, Boolean> listeners = new IdentityHashMap<Object, Boolean>();
    private Engine engine;
    private long stopGeneration;

    public ChatTextAnalysisService(EngineLoader loader, long stopGraceMillis) {
        this.loader = loader;
        this.stopGraceMillis = Math.max(0, stopGraceMillis);
    }

    /** Register a listener; the FIRST one starts the engine (loads the installed models). */
    public synchronized void acquire(Object listener) {
        listeners.put(listener, Boolean.TRUE);
        stopGeneration++; // any pending grace-stop is void — the service is wanted again
        if (engine == null) {
            try {
                engine = loader.load();
            } catch (RuntimeException brokenModels) {
                System.err.println("[text-analysis] engine load failed: " + brokenModels);
                engine = null; // degrade: segment() falls back to the single-segment path
            }
        }
    }

    /** Deregister; after the LAST listener the engine stops (after the grace period). */
    public synchronized void release(Object listener) {
        listeners.remove(listener);
        if (!listeners.isEmpty()) {
            return;
        }
        final long myGeneration = ++stopGeneration;
        if (stopGraceMillis == 0) {
            stopNow(myGeneration);
            return;
        }
        Thread stopper = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(stopGraceMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                stopNow(myGeneration);
            }
        }, "askai-text-analysis-stop");
        stopper.setDaemon(true);
        stopper.start();
    }

    private synchronized void stopNow(long myGeneration) {
        if (myGeneration != stopGeneration || !listeners.isEmpty()) {
            return; // re-acquired (or superseded) during the grace period — keep running
        }
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    public synchronized boolean isRunning() {
        return engine != null;
    }

    public synchronized boolean isLanguageDetectionAvailable() {
        return engine != null && engine.languageDetector() != null;
    }

    /**
     * Split {@code text} into same-language segments (and optionally sentences).
     *
     * @param detectLanguages when false, NLP language detection is not used AT ALL (the whole
     *                        text stays in the fallback language)
     * @param splitSentences  when false, each segment carries ONE joined chunk instead of
     *                        per-sentence chunks
     */
    public synchronized List<Segment> segment(String text, String fallbackLanguage,
                                              boolean detectLanguages, boolean splitSentences) {
        String value = text == null ? "" : text.trim();
        List<Segment> single = Collections.singletonList(
                new Segment(fallbackLanguage, Collections.singletonList(value)));
        if (value.isEmpty()) {
            return single;
        }
        if (engine == null || (!detectLanguages && !splitSentences)) {
            return single; // service not running (models missing) or nothing requested → as before
        }
        List<String> sentences = engine.splitterFor(fallbackLanguage).split(value);
        if (sentences.isEmpty()) {
            return single;
        }
        LanguageDetector detector = detectLanguages ? engine.languageDetector() : null;
        List<Segment> segments = new ArrayList<Segment>();
        String currentLanguage = null;
        List<String> currentSentences = new ArrayList<String>();
        for (String sentence : sentences) {
            String detected = detector == null ? "" : detector.detectLanguage(sentence);
            String language = detected == null || detected.isEmpty() ? fallbackLanguage : detected;
            if (currentLanguage != null && !language.equals(currentLanguage)) {
                segments.add(new Segment(currentLanguage, chunks(currentSentences, splitSentences)));
                currentSentences = new ArrayList<String>();
            }
            currentLanguage = language;
            currentSentences.add(sentence);
        }
        segments.add(new Segment(currentLanguage, chunks(currentSentences, splitSentences)));
        return segments;
    }

    private static List<String> chunks(List<String> sentences, boolean splitSentences) {
        if (splitSentences) {
            return sentences;
        }
        StringBuilder joined = new StringBuilder();
        for (String sentence : sentences) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(sentence);
        }
        return Collections.singletonList(joined.toString());
    }
}
