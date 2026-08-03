package com.aresstack.askai.research.text.opennlp;

import java.io.File;
import java.util.Optional;

/**
 * Resolves a language key (the session's own ISO code, e.g. {@code "en"} / {@code "de"}) to the LOCAL OpenNLP
 * sentence-model artifact deployed for it. Models are deployment artifacts — this catalog only inspects the
 * local filesystem and NEVER downloads. An absent model is an expected {@link Optional#empty()} (the caller may
 * fall back to the regex segmenter); it is not an error.
 */
public interface OpenNlpModelCatalog {

    /** The deployed sentence-model file for the language key, or empty when none is installed (expected). */
    Optional<File> sentenceModel(String languageKey);
}
