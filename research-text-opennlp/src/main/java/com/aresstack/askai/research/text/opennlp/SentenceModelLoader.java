package com.aresstack.askai.research.text.opennlp;

import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;

import java.io.File;
import java.io.IOException;

/**
 * The narrow seam that turns a local model file into a neutral {@link SentenceSegmentationPort}. Isolated so
 * the {@link OpenNlpModelResolver}'s catalog/caching/fallback logic is unit-testable WITHOUT a real (large,
 * binary) OpenNLP model — a corrupt model surfaces as an {@link IOException} here, which the resolver maps to a
 * typed {@link OpenNlpModelException}. The productive loader builds an {@link OpenNlpSentenceSegmenter}.
 */
public interface SentenceModelLoader {

    /** @throws IOException when the model file is missing, corrupt or an unsupported format. */
    SentenceSegmentationPort load(File modelFile) throws IOException;

    /** The productive loader over the real OpenNLP {@link OpenNlpSentenceSegmenter}. */
    static SentenceModelLoader openNlp() {
        return new SentenceModelLoader() {
            public SentenceSegmentationPort load(File modelFile) throws IOException {
                return new OpenNlpSentenceSegmenter(modelFile);
            }
        };
    }
}
