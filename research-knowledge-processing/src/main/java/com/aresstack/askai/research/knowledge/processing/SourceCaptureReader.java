package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.SourceCapture;

/**
 * Reads an accepted capture as the canonical {@link SourceCapture} (with its structural blocks) — the input to
 * the knowledge pipeline. The productive adapter (e.g. VisitedCapture → SourceCapture) lives in the host/plugin
 * layer; this port keeps the worker free of any capture-store or browser detail.
 */
public interface SourceCaptureReader {

    /** @return the capture as a domain SourceCapture, or {@code null} when the capture id is unknown. */
    SourceCapture read(String captureId);
}
