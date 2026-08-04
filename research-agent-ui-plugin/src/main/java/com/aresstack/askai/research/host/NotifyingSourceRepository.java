package com.aresstack.askai.research.host;

import com.aresstack.askai.research.knowledge.processing.live.KnowledgeProjectionInvalidator;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.sources.SourceUpdate;
import com.aresstack.askai.research.sources.SourceUpdateResult;

import java.util.List;

/**
 * Decorates the session's source repository so a SUCCESSFUL update (the sources tab's Save/Exclude/⭐, all of
 * which change what the ACTIVE projection corpus may include) invalidates the live knowledge projection —
 * debounced downstream, never a rebuild per keystroke. Reads delegate untouched; a failed/conflicted update
 * fires nothing. The invalidator is best-effort: its absence (knowledge capability unavailable) is a no-op.
 */
final class NotifyingSourceRepository implements ResearchSourceRepository {

    private final ResearchSourceRepository delegate;
    private final KnowledgeProjectionInvalidator invalidator;

    NotifyingSourceRepository(ResearchSourceRepository delegate, KnowledgeProjectionInvalidator invalidator) {
        this.delegate = delegate;
        this.invalidator = invalidator == null ? KnowledgeProjectionInvalidator.NONE : invalidator;
    }

    @Override
    public List<ResearchSourceRecord> find(SourceQuery query) {
        return delegate.find(query);
    }

    @Override
    public ResearchSourceRecord get(String sourceId) {
        return delegate.get(sourceId);
    }

    @Override
    public SourceUpdateResult update(String sourceId, long expectedRevision, SourceUpdate update) {
        SourceUpdateResult result = delegate.update(sourceId, expectedRevision, update);
        if (result.getStatus() == SourceUpdateResult.Status.UPDATED) {
            try {
                invalidator.sourceRelevanceChanged(sourceId);
            } catch (RuntimeException never) {
                // a projection trigger must never fail the user's save
            }
        }
        return result;
    }
}
