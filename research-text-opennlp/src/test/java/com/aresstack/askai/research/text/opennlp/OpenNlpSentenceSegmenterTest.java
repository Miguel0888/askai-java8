package com.aresstack.askai.research.text.opennlp;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Adapter smoke against a REAL sentence model: trained models are deployment artifacts, so this test
 * runs only when one is provided via {@code askai.opennlp.sentence.model} (skips readably otherwise).
 * The adapter's contract (port shape, trimming, thread confinement) is exercised here; segmentation
 * QUALITY is OpenNLP's concern.
 */
public class OpenNlpSentenceSegmenterTest {

    @Test
    public void segmentsWithAProvidedModel() throws Exception {
        String path = System.getProperty("askai.opennlp.sentence.model",
                System.getenv("ASKAI_OPENNLP_SENTENCE_MODEL"));
        Assume.assumeTrue("SKIPPED: no sentence model provided", path != null
                && new File(path).isFile());
        OpenNlpSentenceSegmenter segmenter = new OpenNlpSentenceSegmenter(new File(path));
        List<String> sentences = segmenter.segment(
                "Smart glasses project images. Battery life is limited.");
        assertTrue("expected at least two sentences, got " + sentences, sentences.size() >= 2);
    }
}
