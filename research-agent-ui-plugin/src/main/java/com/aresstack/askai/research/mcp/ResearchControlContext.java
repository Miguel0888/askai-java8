package com.aresstack.askai.research.mcp;

import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.research.sources.ResearchSourceRepository;

/**
 * What the research-control tool handlers operate on. Crucially, {@link #currentPhaseId()} and
 * {@link #currentStateId()} are read AT EXECUTION TIME — every handler re-checks authorization against the
 * live state, because a transition may have happened between tools/list and tools/call. Tool visibility
 * (computed by {@link ResearchToolPolicy}) and tool authorization (checked here) are deliberately separate.
 */
public interface ResearchControlContext {

    String currentPhaseId();

    String currentStateId();

    /** Human-readable status line for research_status (phase / run state / revision / pending approval). */
    String statusLine();

    AgentArtifactStore artifactStore();

    ResearchSourceRepository sourceRepository();

    /**
     * Promote a visited capture to an accepted source (the only path that creates a persistent source —
     * productively backed by the Commit-37 {@code SourceAcceptanceService}).
     * @return the compact acceptance result line (status/source_id/title/passage_count/duplicate,
     *         e.g. {@code status=ACCEPTED source_id=source-1 title="t" passage_count=1 duplicate=false}),
     *         or {@code null} when the capture id is unknown.
     */
    String acceptCapture(String captureId);

    /**
     * As {@link #acceptCapture(String)} but records the USER web-search query that found the capture — used by
     * the internal {@code manual_source_accept} endpoint so "what was already searched" survives a restart.
     * The default ignores the query (agent path / test fakes); the productive context overrides it.
     */
    default String acceptCapture(String captureId, String searchQuery) {
        return acceptCapture(captureId);
    }

    /**
     * As {@link #acceptCapture(String, String)} but also records whether the USER marked the page relevant (the
     * HUD ⭐ toggle). Used by {@code manual_source_accept}. The default ignores the flag (agent path / test
     * fakes); the productive context overrides it.
     */
    default String acceptCapture(String captureId, String searchQuery, boolean userRelevant) {
        return acceptCapture(captureId, searchQuery);
    }

    /**
     * As {@link #acceptCapture(String, String, boolean)} but with the search's language snapshot ("en"/"de";
     * empty = none), stamped onto the knowledge-processing job. The default ignores it (agent path / fakes).
     */
    default String acceptCapture(String captureId, String searchQuery, boolean userRelevant,
                                 String languageCode) {
        return acceptCapture(captureId, searchQuery, userRelevant);
    }

    /**
     * As above but with the manual-search REQUEST id that found the capture — persisted so a review
     * can be scoped to exactly one search. The default ignores it (agent path / fakes).
     */
    default String acceptCapture(String captureId, String searchQuery, boolean userRelevant,
                                 String languageCode, String searchRequestId) {
        return acceptCapture(captureId, searchQuery, userRelevant, languageCode);
    }

    /**
     * Park a reranked search candidate as a scored source BEFORE the page is visited (empty full text, status
     * PARKED). Backed by the same {@code SourceAcceptanceService} as acceptance. The default is a no-op
     * (agent path / test fakes); the productive context overrides it.
     * @return a compact park result line ({@code status=PARKED|ALREADY_PRESENT source_id=…}), or {@code null}.
     */
    default String parkCandidate(String url, String title, String excerpt, double rerankScore,
                                 String searchQuery) {
        return null;
    }

    /** As above but with the manual-search REQUEST id. The default delegates (agent path / fakes). */
    default String parkCandidate(String url, String title, String excerpt, double rerankScore,
                                 String searchQuery, String searchRequestId) {
        return parkCandidate(url, title, excerpt, rerankScore, searchQuery);
    }

    /**
     * How many sources one {@code source_review_context} answer may carry. The default is the documented
     * settings fallback; the productive context serves the USER's configured value — a review bound is
     * configuration, never only a code constant.
     */
    default int reviewContextMaximumSources() {
        return com.aresstack.askai.research.host.ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_SOURCES;
    }

    /** How much of each source's text the review context carries (characters). Same contract as above. */
    default int reviewContextMaximumCharactersPerSource() {
        return com.aresstack.askai.research.host.ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_CHARS;
    }
}
