package com.aresstack.askai.research.knowledge;

/**
 * A semantic passage — a real project fact (§9), NOT a topic and NOT a section. It is a contiguous span of one
 * immutable {@code SourceCapture} whose sentences share a meaning, produced by the {@link
 * SemanticPassageSegmenter}. Its identity ({@code passageId}) is fachlich and must not depend on a Lucene
 * document id. It carries the pipeline/embedding provenance so a version change yields a fresh derivable
 * passage rather than a silent mismatch. The embedding vector may be stored separately; this record references
 * its space via the metadata fields.
 */
public final class Passage {

    private final String passageId;
    private final String captureId;
    private final String sourceId;
    private final String text;
    private final String textHash;
    private final int startOffset;
    private final int endOffset;
    private final int firstSentenceIndex;
    private final int lastSentenceIndex;
    private final StructuralContext structuralContext;
    private final String segmentationPipelineVersion;
    private final EmbeddingMetadata embeddingMetadata;

    private Passage(Builder b) {
        this.passageId = b.passageId;
        this.captureId = str(b.captureId);
        this.sourceId = str(b.sourceId);
        this.text = str(b.text);
        this.textHash = str(b.textHash);
        this.startOffset = b.startOffset;
        this.endOffset = b.endOffset;
        this.firstSentenceIndex = b.firstSentenceIndex;
        this.lastSentenceIndex = b.lastSentenceIndex;
        this.structuralContext = b.structuralContext == null ? StructuralContext.NONE : b.structuralContext;
        this.segmentationPipelineVersion = str(b.segmentationPipelineVersion);
        this.embeddingMetadata = b.embeddingMetadata;
    }

    private static String str(String v) {
        return v == null ? "" : v;
    }

    public String getPassageId() {
        return passageId;
    }

    public String getCaptureId() {
        return captureId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getText() {
        return text;
    }

    public String getTextHash() {
        return textHash;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public int getFirstSentenceIndex() {
        return firstSentenceIndex;
    }

    public int getLastSentenceIndex() {
        return lastSentenceIndex;
    }

    public StructuralContext getStructuralContext() {
        return structuralContext;
    }

    public String getSegmentationPipelineVersion() {
        return segmentationPipelineVersion;
    }

    /** The embedding space of this passage's vector, or {@code null} when no vector has been attached yet. */
    public EmbeddingMetadata getEmbeddingMetadata() {
        return embeddingMetadata;
    }

    public static Builder builder(String passageId) {
        return new Builder(passageId);
    }

    public static final class Builder {
        private final String passageId;
        private String captureId;
        private String sourceId;
        private String text;
        private String textHash;
        private int startOffset;
        private int endOffset;
        private int firstSentenceIndex;
        private int lastSentenceIndex;
        private StructuralContext structuralContext;
        private String segmentationPipelineVersion;
        private EmbeddingMetadata embeddingMetadata;

        private Builder(String passageId) {
            if (passageId == null || passageId.trim().isEmpty()) {
                throw new IllegalArgumentException("passageId must not be empty");
            }
            this.passageId = passageId;
        }

        public Builder captureId(String v) { this.captureId = v; return this; }
        public Builder sourceId(String v) { this.sourceId = v; return this; }
        public Builder text(String v) { this.text = v; return this; }
        public Builder textHash(String v) { this.textHash = v; return this; }
        public Builder startOffset(int v) { this.startOffset = v; return this; }
        public Builder endOffset(int v) { this.endOffset = v; return this; }
        public Builder firstSentenceIndex(int v) { this.firstSentenceIndex = v; return this; }
        public Builder lastSentenceIndex(int v) { this.lastSentenceIndex = v; return this; }
        public Builder structuralContext(StructuralContext v) { this.structuralContext = v; return this; }
        public Builder segmentationPipelineVersion(String v) {
            this.segmentationPipelineVersion = v;
            return this;
        }
        public Builder embeddingMetadata(EmbeddingMetadata v) { this.embeddingMetadata = v; return this; }

        public Passage build() {
            return new Passage(this);
        }
    }
}
