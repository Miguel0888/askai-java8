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
     * Promote a visited capture to an accepted source (the only path that creates a persistent source).
     * @return the new sourceId, or {@code null} when the capture id is unknown.
     */
    String acceptCapture(String captureId);
}
