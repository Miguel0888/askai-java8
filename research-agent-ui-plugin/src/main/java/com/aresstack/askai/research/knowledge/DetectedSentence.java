package com.aresstack.askai.research.knowledge;

/**
 * One sentence found by the linguistic sentence-boundary detection (stage 1, §6). It carries offsets back into
 * the original extracted text so a later passage can point at the exact source span. OpenNLP (or any other
 * detector) is an infrastructure detail behind {@link SentenceDetectionService}; this type never leaks a
 * library type.
 */
public final class DetectedSentence {

    private final int sentenceIndex;
    private final String text;
    private final int startOffset;
    private final int endOffset;

    public DetectedSentence(int sentenceIndex, String text, int startOffset, int endOffset) {
        this.sentenceIndex = sentenceIndex;
        this.text = text == null ? "" : text;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public int getSentenceIndex() {
        return sentenceIndex;
    }

    public String getText() {
        return text;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }
}
