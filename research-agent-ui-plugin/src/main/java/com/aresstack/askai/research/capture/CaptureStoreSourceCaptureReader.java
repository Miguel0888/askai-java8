package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.processing.SourceCaptureReader;

/**
 * The productive {@link SourceCaptureReader}: reads an accepted capture from the per-session {@link CaptureStore}
 * and maps it losslessly to the canonical {@link SourceCapture} via {@link VisitedCaptureSourceCaptureAdapter}.
 * It resolves the capture's owning source id through an injected {@link SourceIdResolver} (provenance only) so
 * the reader stays free of the source repository. An unknown capture id yields {@code null}, which the worker
 * treats as a permanent failure (the capture is gone from the bounded store).
 */
public final class CaptureStoreSourceCaptureReader implements SourceCaptureReader {

    /** Resolves the accepted source id that owns a visited capture; "" when it cannot (yet) be linked. */
    public interface SourceIdResolver {
        String resolve(VisitedCapture capture);

        SourceIdResolver NONE = new SourceIdResolver() {
            public String resolve(VisitedCapture capture) {
                return "";
            }
        };
    }

    private final CaptureStore captures;
    private final SourceIdResolver sourceIds;

    public CaptureStoreSourceCaptureReader(CaptureStore captures, SourceIdResolver sourceIds) {
        this.captures = captures;
        this.sourceIds = sourceIds == null ? SourceIdResolver.NONE : sourceIds;
    }

    @Override
    public SourceCapture read(String captureId) {
        VisitedCapture capture = captures == null ? null : captures.get(captureId);
        if (capture == null) {
            return null;
        }
        String sourceId = sourceIds.resolve(capture);
        return VisitedCaptureSourceCaptureAdapter.toSourceCapture(capture, sourceId == null ? "" : sourceId);
    }
}
