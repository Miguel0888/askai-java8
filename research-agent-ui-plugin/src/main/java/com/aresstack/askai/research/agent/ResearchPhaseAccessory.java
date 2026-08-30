package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import java.util.List;
import java.util.function.Consumer;

/**
 * The generic phase-bound surface, third pass: no strip near the composer anymore — Phase 1 shows
 * the {@link ResearchOutOfScopeSky} as a see-through layer over the TOP of the transcript
 * ({@code TRANSCRIPT_OVERLAY} placement); in every other phase the sky disappears completely (the
 * chat keeps its full height, no leftover surface). Visibility and data follow the session's state
 * listener exactly like before; add/remove intents run through the session's ONE scope path.
 */
final class ResearchPhaseAccessory implements ComposerAccessory {

    private final ResearchAgentSession research;
    private final ResearchOutOfScopeSky view;
    private final Runnable refresh;

    ResearchPhaseAccessory(final ResearchAgentSession research, final UiExecutor uiExecutor) {
        this.research = research;
        this.view = new ResearchOutOfScopeSky();
        this.view.setAddAction(new Consumer<String>() {
            public void accept(String text) {
                reportRejection(research.addScopeExclusion(text));
            }
        });
        this.view.setRemoveAction(new Consumer<String>() {
            public void accept(String text) {
                reportRejection(research.removeScopeExclusion(text));
            }
        });
        this.refresh = new Runnable() {
            public void run() {
                final boolean scoping = ResearchStateIds.SCOPING.equals(
                        research.currentResearchSnapshot().getCurrentPhaseId());
                final List<String> exclusions = scoping
                        ? research.currentScopeDraft().getExclusions()
                        : java.util.Collections.<String>emptyList();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.setVisible(scoping);
                        view.setExclusions(exclusions);
                    }
                });
            }
        };
        research.addStateListener(refresh);
        refresh.run(); // initial paint
    }

    /** A refused add/remove must stay visible — the sky would otherwise silently lie. */
    private void reportRejection(String reason) {
        if (reason != null) {
            JOptionPane.showMessageDialog(view, reason, "Ausschluss nicht übernommen",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    public JComponent getComponent() {
        return view;
    }

    @Override
    public Placement getPlacement() {
        return Placement.TRANSCRIPT_OVERLAY;
    }

    public void dispose() {
        research.removeStateListener(refresh);
    }
}
