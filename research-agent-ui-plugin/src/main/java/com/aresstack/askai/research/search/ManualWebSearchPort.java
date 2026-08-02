package com.aresstack.askai.research.search;

/**
 * A USER-triggered web search as a plain application service — the third interaction kind, distinct from an
 * agent chat turn ({@code ChatSubmissionTarget.submitText}) and from a workflow/state-machine command. The user
 * may invoke it directly at any time; it starts NO agent turn, writes NO chat message merely because of the
 * click, and drives NO state-machine transition. It is deliberately PHASE-INDEPENDENT: the port itself must not
 * gate on {@code phase == SCOPING} / {@code phase == RESEARCH}. (The agent's own web search is a separate path —
 * an MCP tool offered only in phases whose assistant profile allows it — that ultimately reaches the SAME
 * search backend service; the port and that tool are two front doors, never two provider implementations.)
 *
 * <p>Slice S1 introduces only this seam and rewires the yellow scoping suggestions onto it; the productive
 * execution (reusing the runtime {@code SearchStrategy} over a typed service transport) arrives in slice S2.</p>
 */
public interface ManualWebSearchPort {

    /**
     * Run a user-triggered web search for {@code request}. Provider, language, country and result count come
     * from the existing research/search configuration — never from the calling UI — so the request carries only
     * the query. Progress, results and errors surface through the existing research activity surfaces (S2).
     */
    void search(ManualWebSearchRequest request);
}
