package com.aresstack.askai.research.text.opennlp;

import java.io.File;
import java.util.Optional;

/**
 * An {@link OpenNlpModelCatalog} backed by ONE already-resolved artifact for ONE language — the host has already
 * selected and validated the model (existence + checksum) and hands its path here. This keeps
 * {@code research-text-opennlp} purely path/artifact oriented: it knows nothing of AskAI settings, the NLP model
 * store or the snapshot provider — only a language key and a file.
 */
public final class SingleArtifactOpenNlpModelCatalog implements OpenNlpModelCatalog {

    private final String languageKey;
    private final File artifact;

    public SingleArtifactOpenNlpModelCatalog(String languageKey, File artifact) {
        this.languageKey = normalize(languageKey);
        this.artifact = artifact;
    }

    @Override
    public Optional<File> sentenceModel(String languageKey) {
        if (artifact == null || !normalize(languageKey).equals(this.languageKey)) {
            return Optional.empty();
        }
        return artifact.isFile() ? Optional.of(artifact) : Optional.<File>empty();
    }

    private static String normalize(String languageKey) {
        return languageKey == null ? "" : languageKey.trim().toLowerCase();
    }
}
