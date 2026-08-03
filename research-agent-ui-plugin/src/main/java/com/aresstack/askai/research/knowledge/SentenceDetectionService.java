package com.aresstack.askai.research.knowledge;

import java.util.List;

/**
 * Stage 1 of segmentation (§6): linguistic sentence-boundary detection. The productive adapter wraps Apache
 * OpenNLP; the OpenNLP model is selectable via its own factory/config and no OpenNLP type leaks past this
 * port. Sentence detection does NOT decide the final semantic passages — that is the segmenter's job.
 */
public interface SentenceDetectionService {

    List<DetectedSentence> detectSentences(ExtractedContent content);
}
