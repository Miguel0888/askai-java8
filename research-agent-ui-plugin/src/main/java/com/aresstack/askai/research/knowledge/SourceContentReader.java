package com.aresstack.askai.research.knowledge;

/**
 * Reads the immutable capture's already-clean text into {@link ExtractedContent} (§5, stage "Capture-Text
 * lesen" → "Text normalisieren"). A port so the worker never touches the capture store directly.
 */
public interface SourceContentReader {

    /** @return the extracted content for a capture, or {@code null} when the capture is unknown. */
    ExtractedContent read(String captureId);
}
