package com.aresstack.askai.research.text.opennlp;

import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;

import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Apache OpenNLP adapter for the pipeline's {@link SentenceSegmentationPort}: a trained
 * {@link SentenceModel} (e.g. {@code opennlp-de-sentence} / {@code en-sent.bin}) loaded from a local
 * file — the model is a deployment artifact, never bundled here. The domain and the pipeline never see
 * OpenNLP types; without a model the deterministic {@code RegexSentenceSegmenter} stays the fallback.
 */
public final class OpenNlpSentenceSegmenter implements SentenceSegmentationPort {

    private final SentenceDetectorME detector;

    public OpenNlpSentenceSegmenter(File sentenceModelFile) throws IOException {
        InputStream in = new FileInputStream(sentenceModelFile);
        try {
            this.detector = new SentenceDetectorME(new SentenceModel(in));
        } finally {
            in.close();
        }
    }

    @Override
    public synchronized List<String> segment(String text) {
        // SentenceDetectorME is not thread-safe; segmentation is cheap enough to serialize.
        List<String> sentences = new ArrayList<String>();
        if (text == null || text.trim().isEmpty()) {
            return sentences;
        }
        for (String sentence : detector.sentDetect(text)) {
            String trimmed = sentence.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }
}
