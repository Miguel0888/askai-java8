package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.sources.SourceQuery;

/**
 * Resolves a visited capture to its accepted source id by matching CANONICAL URLs against the persisted source
 * repository — the same canonicalization the capture store uses, so a source accepted from a page links back to
 * that page's captures. This is persisted provenance (it survives a restart), not an in-memory hint. Returns ""
 * when no accepted source shares the capture's canonical URL (e.g. the capture was never accepted as a source).
 */
public final class CanonicalUrlSourceIdResolver implements CaptureStoreSourceCaptureReader.SourceIdResolver {

    private final ResearchSourceRepository repository;

    public CanonicalUrlSourceIdResolver(ResearchSourceRepository repository) {
        this.repository = repository;
    }

    @Override
    public String resolve(VisitedCapture capture) {
        if (capture == null || repository == null) {
            return "";
        }
        String canonical = capture.getCanonicalUrl();
        if (canonical == null || canonical.isEmpty()) {
            return "";
        }
        for (ResearchSourceRecord record : repository.find(SourceQuery.all())) {
            if (canonical.equals(CaptureStore.canonicalize(record.getUrl()))) {
                return record.getSourceId();
            }
        }
        return "";
    }
}
