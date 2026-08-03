package com.aresstack.askai.research.knowledge;

/**
 * The normalized plain text of a capture, the input to sentence detection (§5, §6). Produced by a
 * {@link SourceContentReader} from the immutable capture; carries the identity (capture/source) and, when the
 * extractor could recover it, an optional leading heading path for structural context. No HTML, no library
 * types.
 */
public final class ExtractedContent {

    private final String captureId;
    private final String sourceId;
    private final String text;
    private final String contentHash;

    public ExtractedContent(String captureId, String sourceId, String text, String contentHash) {
        this.captureId = captureId == null ? "" : captureId;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.text = text == null ? "" : text;
        this.contentHash = contentHash == null ? "" : contentHash;
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

    public String getContentHash() {
        return contentHash;
    }
}
