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
                final ScopingAssistantUpdate projection = research.latestScopingProjection();
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

    public void dispose() {
        research.removeStateListener(refresh);
        view.dispose();
    }
}
