package com.aresstack.askai.research.review;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceStatus;

import java.util.Collection;

/**
 * WHICH material a review would be about, expressed as a value that can be compared and persisted: the
 * number of reviewable sources plus the newest capture timestamp among them.
 * <p>
 * It is DERIVED from the persisted source files, never counted along the way, so it survives a restart and
 * cannot drift from the truth on disk. Two revisions are compared by equality rather than by order on
 * purpose — a source that gets excluded changes what a review would be about just as much as one that gets
 * added, and neither is "newer" than the other.
 */
public final class SourceCorpusRevision {

    /** Nothing to review: the empty corpus, and the state a project starts in. */
    public static final SourceCorpusRevision EMPTY = new SourceCorpusRevision(0, 0L);

    private final int reviewableCount;
    private final long latestCapturedAt;

    public SourceCorpusRevision(int reviewableCount, long latestCapturedAt) {
        this.reviewableCount = reviewableCount;
        this.latestCapturedAt = latestCapturedAt;
    }

    /**
     * The revision of a source collection. PARKED candidates (written before the page was read) and sources
     * the user or the pipeline has taken out (EXCLUDED/DUPLICATE/SUPERSEDED) are not material to review.
     */
    public static SourceCorpusRevision of(Collection<ResearchSourceRecord> sources) {
        if (sources == null) {
            return EMPTY;
        }
        int count = 0;
        long latest = 0L;
        for (ResearchSourceRecord source : sources) {
            if (source == null || !isReviewable(source.getStatus())) {
                continue;
            }
            count++;
            if (source.getCapturedAt() > latest) {
                latest = source.getCapturedAt();
            }
        }
        return new SourceCorpusRevision(count, latest);
    }

    private static boolean isReviewable(SourceStatus status) {
        return status == SourceStatus.NEW
                || status == SourceStatus.REVIEWED
                || status == SourceStatus.ACCEPTED;
    }

    public int getReviewableCount() {
        return reviewableCount;
    }

    public long getLatestCapturedAt() {
        return latestCapturedAt;
    }

    public boolean isEmpty() {
        return reviewableCount == 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SourceCorpusRevision)) {
            return false;
        }
        SourceCorpusRevision that = (SourceCorpusRevision) other;
        return reviewableCount == that.reviewableCount && latestCapturedAt == that.latestCapturedAt;
    }

    @Override
    public int hashCode() {
        return 31 * reviewableCount + (int) (latestCapturedAt ^ (latestCapturedAt >>> 32));
    }

    @Override
    public String toString() {
        return "SourceCorpusRevision{count=" + reviewableCount + ", latest=" + latestCapturedAt + "}";
    }
}
