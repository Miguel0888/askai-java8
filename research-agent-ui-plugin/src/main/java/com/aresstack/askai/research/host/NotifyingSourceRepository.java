package com.aresstack.askai.research.host;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.sources.SourceUpdate;
import com.aresstack.askai.research.sources.SourceUpdateResult;

import java.util.List;

/**
 * Decorates the session's source repository so a SUCCESSFUL update (the sources tab's Save/Exclude/⭐, all of
 * which change what the ACTIVE projection corpus may include) NOTIFIES an observer — issue #29: this only lets
 * an open Outline tab re-check its staleness metadata; it NEVER triggers a topic/outline rebuild. Reads
 * delegate untouched; a failed/conflicted update fires nothing. The notifier is best-effort: its absence is a
 * no-op and its failure never fails the user's save.
 */
final class NotifyingSourceRepository implements ResearchSourceRepository {

    private final ResearchSourceRepository delegate;
    private final Runnable changeNotifier;

    NotifyingSourceRepository(ResearchSourceRepository delegate, Runnable changeNotifier) {
        this.delegate = delegate;
        this.changeNotifier = changeNotifier;
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
        if (result.getStatus() == SourceUpdateResult.Status.UPDATED && changeNotifier != null) {
            try {
                changeNotifier.run();
            } catch (RuntimeException never) {
                // a staleness notification must never fail the user's save
            }
        }
        return result;
    }
}
