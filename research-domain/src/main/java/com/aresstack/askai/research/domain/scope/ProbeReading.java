package com.aresstack.askai.research.domain.scope;

/**
 * The RAW measurement of one probe in a sweep — two orthogonal axes, exactly as agreed:
 * <pre>
 *   mission relevance:   is this plausibly part of the coarse mission at all?
 *   fence relationship:  is it already represented by the negotiated posts?
 * </pre>
 * "Far from the fence" alone is mathematically identical to "completely off-topic" — only the
 * COMBINATION (relevant to the mission AND badly explained by the fence) marks a plausible missing
 * post. The category is an advisory bucket, never a decision; there is deliberately NO coverage
 * percentage anywhere.
 */
public final class ProbeReading {

    /** Advisory buckets. IRRELEVANT is kept for transparency; it never reaches the agent. */
    public enum Category {
        /** Mission-relevant and clearly near an existing IN post — usually uninteresting. */
        KNOWN,
        /** Mission-relevant but clearly near a negotiated OUT post — drift detection material. */
        EXCLUDED,
        /** Mission-relevant and similarly plausible on both sides — ideal for ONE user question. */
        BOUNDARY,
        /** Mission-relevant yet unusually far from EVERY post — the actual hole finder. */
        UNEXPLORED,
        /** Not plausibly part of the mission — however novel, never an interesting hole. */
        IRRELEVANT
    }

    private final ScopeProbe probe;
    private final double missionRelevance;
    private final ScopeFenceEvaluator.Reading fenceReading;
    private final double knownSimilarity;
    private final Category category;

    public ProbeReading(ScopeProbe probe, double missionRelevance,
                        ScopeFenceEvaluator.Reading fenceReading, Category category) {
        if (probe == null) {
            throw new IllegalArgumentException("probe must not be null");
        }
        if (fenceReading == null) {
            throw new IllegalArgumentException("fenceReading must not be null");
        }
        this.probe = probe;
        this.missionRelevance = missionRelevance;
        this.fenceReading = fenceReading;
        this.knownSimilarity = Math.max(fenceReading.nearestInSimilarity,
                Math.max(fenceReading.nearestOutSimilarity,
                        fenceReading.nearestProvisionalSimilarity));
        this.category = category == null ? Category.IRRELEVANT : category;
    }

    public ScopeProbe getProbe() {
        return probe;
    }

    public double getMissionRelevance() {
        return missionRelevance;
    }

    public ScopeFenceEvaluator.Reading getFenceReading() {
        return fenceReading;
    }

    /** {@code max cos} to ANY post (IN, OUT or PROVISIONAL) — how well the fence explains this probe. */
    public double getKnownSimilarity() {
        return knownSimilarity;
    }

    /**
     * {@code 1 - knownSimilarity}: novelty RELATIVE TO THE FENCE — never truth, never "missing scope"
     * by itself, and never comparable across embedding models without calibration.
     */
    public double getNovelty() {
        return 1.0d - knownSimilarity;
    }

    public Category getCategory() {
        return category;
    }
}
