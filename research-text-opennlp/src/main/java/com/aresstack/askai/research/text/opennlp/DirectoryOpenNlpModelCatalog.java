package com.aresstack.askai.research.text.opennlp;

import java.io.File;
import java.util.Optional;

/**
 * The productive {@link OpenNlpModelCatalog}: sentence models live as deployment artifacts under a CONFIGURABLE
 * directory, named {@code sentence-<languageKey>.bin} (e.g. {@code sentence-en.bin}, {@code sentence-de.bin}).
 * Resolution is purely a local file existence check — no network, no classpath scan, no guessing. A missing
 * directory or a missing file for the language is simply {@link Optional#empty()} (the regex fallback applies).
 */
public final class DirectoryOpenNlpModelCatalog implements OpenNlpModelCatalog {

    private final File modelsDir;

    public DirectoryOpenNlpModelCatalog(File modelsDir) {
        this.modelsDir = modelsDir;
    }

    /** The conventional artifact file name for a language key (public so deployment tooling can match it). */
    public static String fileName(String languageKey) {
        return "sentence-" + normalize(languageKey) + ".bin";
    }

    @Override
    public Optional<File> sentenceModel(String languageKey) {
        String key = normalize(languageKey);
        if (key.isEmpty() || modelsDir == null) {
            return Optional.empty();
        }
        File candidate = new File(modelsDir, fileName(key));
        return candidate.isFile() ? Optional.of(candidate) : Optional.<File>empty();
    }

    private static String normalize(String languageKey) {
        return languageKey == null ? "" : languageKey.trim().toLowerCase();
    }
}
