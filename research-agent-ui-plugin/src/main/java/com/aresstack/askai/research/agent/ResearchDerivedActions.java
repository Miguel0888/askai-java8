package com.aresstack.askai.research.agent;

/**
 * THE application commands for the explicit derived Research actions (issue #33): review the new sources,
 * generate the visualization, generate the outline. Exactly ONE implementation exists per session (the
 * session's own use-case logic from issue #29); the UI buttons and the internal service-MCP endpoint are two
 * ADAPTERS over these commands — never two implementations. Everything stays explicitly user/host-triggered:
 * this port is deliberately NOT offered on the agent-facing research-control endpoint, so the TeamAgent
 * cannot re-acquire the implicit orchestration removed by #29.
 */
public interface ResearchDerivedActions {

    /** Typed outcome: whether the action was accepted (started), with an honest detail when it was not. */
    final class ActionOutcome {
        private final boolean accepted;
        private final String detail;

        private ActionOutcome(boolean accepted, String detail) {
            this.accepted = accepted;
            this.detail = detail == null ? "" : detail;
        }

        public static ActionOutcome accepted(String detail) {
            return new ActionOutcome(true, detail);
        }

        public static ActionOutcome rejected(String reason) {
            return new ActionOutcome(false, reason);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String getDetail() {
            return detail;
        }
    }

    /**
     * Let the TeamAgent review the accepted sources (summary; suggestion refresh stays scoping-only). Runs
     * asynchronously AFTER this explicit request, bracketed by the review_started/review_finished lifecycle.
     */
    ActionOutcome reviewSources();

    /** Generate/regenerate the DERIVED visualization from the CURRENT brief (asynchronous after the call). */
    ActionOutcome generateVisualization();

    /**
     * Rebuild topic discovery + outline from the persisted corpus as ONE explicitly started operation
     * (asynchronous, debounced). #30 may later split this command into separate stages — not here.
     */
    ActionOutcome generateOutline();
}
