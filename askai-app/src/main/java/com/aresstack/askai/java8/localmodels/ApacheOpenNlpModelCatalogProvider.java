package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.agent.model.nlp.NlpCapability;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The curated catalog of official Apache OpenNLP 1.5 sentence-detection models — the ONLY source this build
 * offers for install. opennlp-tools 1.9.4 (the Java-8 line) is compatible with the 1.5 models; the newer 2.x
 * (UD) models require Java 11+ and are deliberately NOT offered here.
 *
 * <p>The logical curated source is {@code https://opennlp.sourceforge.net/models-1.5/}; the concrete download
 * targets are the SourceForge {@code .../download} URLs (redirects to a mirror are followed). The SHA-256 and
 * EXACT byte size below were verified once by downloading each file and loading it with OpenNLP 1.9.4 — the
 * installer accepts ONLY these exact artifacts (no TOFU, no arbitrary mirrors, no search results). Apache also
 * hosts a 1.5 set at nightlies.apache.org; a second source stays a DELIBERATE future mirror, not a silent
 * fallback — one pinned source per version.</p>
 */
public final class ApacheOpenNlpModelCatalogProvider implements NlpModelCatalogProvider {

    private static final String OPENNLP = "opennlp";
    private static final String VERSION_1_5 = "1.5";
    private static final String COMPATIBLE_RUNTIME = "1.9.4";

    private final List<NlpModelCatalogEntry> entries = Collections.unmodifiableList(Arrays.asList(
            new NlpModelCatalogEntry(
                    "apache-opennlp/sentence-de", NlpCapability.SENTENCE_DETECTION, "de",
                    OPENNLP, VERSION_1_5, COMPATIBLE_RUNTIME,
                    "https://sourceforge.net/projects/opennlp/files/models-1.5/de-sent.bin/download",
                    "de-sent.bin",
                    "e850b3a7939c5c95051451cc391df65968faa2feb0374d150fdf6c57af36be37", 45782L),
            new NlpModelCatalogEntry(
                    "apache-opennlp/sentence-en", NlpCapability.SENTENCE_DETECTION, "en",
                    OPENNLP, VERSION_1_5, COMPATIBLE_RUNTIME,
                    "https://sourceforge.net/projects/opennlp/files/models-1.5/en-sent.bin/download",
                    "en-sent.bin",
                    "bd6adffc85d66ccffd09ad1545ab798248193672c4da5c6669150e6a3b35e5b1", 98533L)));

    @Override
    public List<NlpModelCatalogEntry> availableModels() {
        return entries;
    }
}
