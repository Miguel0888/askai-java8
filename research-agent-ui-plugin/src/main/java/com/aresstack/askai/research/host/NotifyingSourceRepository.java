package com.aresstack.askai.research.host;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.sources.SourceUpdate;
import com.aresstack.askai.research.sources.SourceUpdateResult;

import java.util.List;

/**
 * Decorates the session's source repository so a SUCCESSFUL update (the sources tab's Save/Exclude/⭐, all of
 * which change what the ACTIVE projection corpus may include) marks the outline dirty. It deliberately does
 * not rebuild the outline immediately; the next research-run completion barrier owns the single rebuild.
 * Reads delegate untouched; a failed/conflicted update fires nothing.
 */
final class NotifyingSourceRepository implements ResearchSourceRepository {

    interface SourceChangeListener {
        void sourceChanged(String sourceId);

        SourceChangeListener NONE = new SourceChangeListener() {
            public void sourceChanged(String sourceId) {
            }
        };
    }

    private final ResearchSourceRepository delegate;
    private final SourceChangeListener listener;

    NotifyingSourceRepository(ResearchSourceRepository delegate, SourceChangeListener listener) {
        this.delegate = delegate;
        this.listener = listener == null ? SourceChangeListener.NONE : listener;
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
                listener.sourceChanged(sourceId);
            } catch (RuntimeException never) {
                // a dirty marker must never fail the user's save
            }
        }
        return result;
    }
}
