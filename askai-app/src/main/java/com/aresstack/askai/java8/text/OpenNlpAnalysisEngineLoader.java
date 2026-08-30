package com.aresstack.askai.java8.text;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpModelDescriptor;
import com.aresstack.askai.java8.localmodels.LocalNlpModelStore;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The productive {@link ChatTextAnalysisService.EngineLoader}: loads whatever is INSTALLED in the
 * dedicated NLP store (Models → Setup → NLP) — the langdetect model for language identification
 * and the per-language sentence models. Everything is optional and degrades honestly: no
 * langdetect → no language detection ({@code languageDetector() == null}); no sentence model for a
 * language → a naive punctuation splitter. Detected languages are constrained to the read-aloud
 * pair de/en — anything else counts as "unknown" and stays in the fallback language.
 */
public final class OpenNlpAnalysisEngineLoader implements ChatTextAnalysisService.EngineLoader {

    private final LocalNlpModelStore store;

    public OpenNlpAnalysisEngineLoader() {
        this(new LocalNlpModelStore());
    }

    public OpenNlpAnalysisEngineLoader(LocalNlpModelStore store) {
        this.store = store;
    }

    @Override
    public ChatTextAnalysisService.Engine load() {
        opennlp.tools.langdetect.LanguageDetectorME detectorMe = null;
        final Map<String, opennlp.tools.sentdetect.SentenceDetectorME> sentenceByLanguage =
                new HashMap<String, opennlp.tools.sentdetect.SentenceDetectorME>();
        for (NlpModelDescriptor descriptor : store.listInstalled()) {
            try {
                if (descriptor.getCapability() == NlpCapability.LANGUAGE_DETECTION
                        && detectorMe == null) {
                    detectorMe = new opennlp.tools.langdetect.LanguageDetectorME(
                            new opennlp.tools.langdetect.LanguageDetectorModel(
                                    new File(descriptor.getArtifactPath())));
                } else if (descriptor.getCapability() == NlpCapability.SENTENCE_DETECTION) {
                    sentenceByLanguage.put(descriptor.getLanguageCode(),
                            new opennlp.tools.sentdetect.SentenceDetectorME(
                                    new opennlp.tools.sentdetect.SentenceModel(
                                            new File(descriptor.getArtifactPath()))));
                }
            } catch (Exception unloadable) {
                System.err.println("[text-analysis] model " + descriptor.getModelId()
                        + " could not be loaded: " + unloadable.getMessage());
            }
        }
        final opennlp.tools.langdetect.LanguageDetectorME detector = detectorMe;
        return new ChatTextAnalysisService.Engine() {
            public ChatTextAnalysisService.LanguageDetector languageDetector() {
                if (detector == null) {
                    return null;
                }
                return new ChatTextAnalysisService.LanguageDetector() {
                    public String detectLanguage(String sentence) {
                        // Short fragments are unreliable — leave them in the fallback language.
                        if (sentence == null || sentence.trim().length() < 12) {
                            return "";
                        }
                        String iso3 = detector.predictLanguage(sentence).getLang();
                        if ("deu".equals(iso3)) {
                            return "de";
                        }
                        if ("eng".equals(iso3)) {
                            return "en";
                        }
                        return ""; // outside the read-aloud pair → fallback language
                    }
                };
            }

            public ChatTextAnalysisService.SentenceSplitter splitterFor(String languageCode) {
                final opennlp.tools.sentdetect.SentenceDetectorME splitter =
                        sentenceByLanguage.get(languageCode);
                if (splitter != null) {
                    return new ChatTextAnalysisService.SentenceSplitter() {
                        public List<String> split(String text) {
                            List<String> sentences = new ArrayList<String>();
                            for (String sentence : splitter.sentDetect(text)) {
                                if (!sentence.trim().isEmpty()) {
                                    sentences.add(sentence.trim());
                                }
                            }
                            return sentences;
                        }
                    };
                }
                return NAIVE_SPLITTER; // no model for this language → punctuation heuristic
            }

            public void close() {
                sentenceByLanguage.clear(); // the models become collectable; nothing holds files
            }
        };
    }

    /** Sentence-ish split on terminal punctuation — the model-less fallback. */
    static final ChatTextAnalysisService.SentenceSplitter NAIVE_SPLITTER =
            new ChatTextAnalysisService.SentenceSplitter() {
                private final Pattern boundary = Pattern.compile("(?<=[.!?])\\s+");

                public List<String> split(String text) {
                    List<String> sentences = new ArrayList<String>();
                    for (String sentence : boundary.split(text)) {
                        if (!sentence.trim().isEmpty()) {
                            sentences.add(sentence.trim());
                        }
                    }
                    return sentences;
                }
            };
}
