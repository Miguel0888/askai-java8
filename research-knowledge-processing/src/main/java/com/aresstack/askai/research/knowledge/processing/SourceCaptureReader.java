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

    /**
     * As {@link #read(String)} but with the job's ACCEPTED source id, so an implementation can fall back to
     * the DURABLE source record when the transient in-memory capture is gone (issue #29: a delayed,
     * user-triggered segmentation must survive a session restart). Default: source-id-blind read.
     */
    default SourceCapture read(String captureId, String sourceId) {
        return read(captureId);
    }
}
