package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.backend.ScopingAssistantUpdate;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import javax.swing.JComponent;
import java.util.function.Consumer;

/**
 * The scoping controls as a COMPOSER ACCESSORY (above the composer), not a hidden artifact view. It wraps the
 * reusable {@link ScopingSupportView}, keeps it in sync with the live session (latest projection + scoping-only
 * visibility) via the session's state listener, and is disposed by the host on session/agent/tab change — an
 * explicit lifecycle instead of an AncestorListener. A TAG CLICK submits the query as an immediate search turn
 * (result depth from the configured search settings, default 10); the agent's best query is surfaced only as
 * the chat composer's PLACEHOLDER through the host-provided sink.
 */
final class ScopingComposerAccessory implements ComposerAccessory {

    private final ResearchAgentSession research;
    private final ScopingSupportView view;
    private final Runnable refresh;

    private volatile Consumer<String> placeholderSink;

    ScopingComposerAccessory(final ResearchAgentSession research, final UiExecutor uiExecutor) {
        this.research = research;
        this.view = new ScopingSupportView();
        this.view.setSearchAction(new Consumer<String>() {
            public void accept(String query) {
                // USER-SERVICE, not a chat turn: a yellow suggestion runs a manual web search directly. It must
                // NOT go through ChatSubmissionTarget.submitText — that would disguise a phase-independent
                // service as an agent prompt and couple the search to the phase/turn availability.
                // Show the click as a TENTATIVE user statement (mermaid block + "?") so it is visible in the
                // chat without the agent later mistaking it for a binding request.
                research.echoTentativeSuggestion(query);
                research.requestManualWebSearch(query);
            }
        });
        this.view.setContinueAction(new Runnable() {
            public void run() {
                // The ONLY trigger of the SCOPING → OUTLINE transition: approve the brief, then advance.
                // All phase rules live in the session/state machine — the button carries none of them.
                research.approveScopingBriefAndContinue();
            }
        });
        this.refresh = new Runnable() {
            public void run() {
                final boolean scoping = ResearchStateIds.SCOPING.equals(
                        research.currentResearchSnapshot().getCurrentPhaseId());
                // Drop suggestions whose query a user search already covered, so a searched (clicked) tag
                // disappears after the search and the same/covered query is never re-offered; the tags then
                // re-arrange in the flow layout.
                final ScopingAssistantUpdate projection =
                        withoutSearched(research.latestScopingProjection());
                // Re-derive the enablement from the LIVE session on every state change (phase, brief, busy) in
                // ONE evaluation: an empty reason means ready; otherwise it is the disabled button's tooltip.
                final String unavailableReason = research.scopingApprovalUnavailableReason();
                final boolean canContinue = unavailableReason.isEmpty();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.setVisible(scoping); // shown only in scoping; hidden elsewhere
                        if (scoping && projection != null) {
                            view.apply(projection);
                        }
                        view.setContinueEnabled(canContinue);
                        view.setContinueTooltip(canContinue
                                ? "Fragestellung freigeben und zur Gliederung (OUTLINE) wechseln"
                                : unavailableReason);
                        pushPlaceholder(scoping, projection);
                    }
                });
            }
        };
        research.addStateListener(refresh);
        refresh.run(); // initial paint
    }

    public JComponent getComponent() {
        return view;
    }

    @Override
    public void bindPlaceholderSink(Consumer<String> sink) {
        this.placeholderSink = sink;
        refresh.run(); // push the current query into the freshly bound composer placeholder
    }

    /** The agent's best query lives ONLY in the composer placeholder — nowhere else in the UI. */
    private void pushPlaceholder(boolean scoping, ScopingAssistantUpdate projection) {
        Consumer<String> sink = placeholderSink;
        if (sink == null) {
            return;
        }
        String query = null;
        if (scoping && projection != null && !projection.getSearchSuggestions().isEmpty()) {
            query = projection.getSearchSuggestions().get(0).getQuery();
        }
        sink.accept(query == null || query.trim().isEmpty() ? null : query.trim());
    }

    /** A copy of the projection with already-searched queries removed (null-safe; identity when none drop). */
    private ScopingAssistantUpdate withoutSearched(ScopingAssistantUpdate projection) {
        if (projection == null) {
            return null;
        }
        java.util.List<ScopingAssistantUpdate.Suggestion> kept =
                new java.util.ArrayList<ScopingAssistantUpdate.Suggestion>();
        for (ScopingAssistantUpdate.Suggestion suggestion : projection.getSearchSuggestions()) {
            if (!research.wasManuallySearched(suggestion.getQuery())) {
                kept.add(suggestion);
            }
        }
        if (kept.size() == projection.getSearchSuggestions().size()) {
            return projection; // nothing searched yet → unchanged
        }
        return new ScopingAssistantUpdate(projection.getPhaseId(), kept,
                projection.getAdviceRecommendation(), projection.getAdviceReason());
    }

    public void dispose() {
        research.removeStateListener(refresh);
        view.dispose();
    }
}
