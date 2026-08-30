package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import java.util.List;
import java.util.function.Consumer;

/**
 * The generic phase-bound surface BELOW the composer (the spot the old "Technical details" strip
 * occupied). Per slice only Phase 1 has content — the blacklist strip ({@link
 * ResearchBlacklistPanel}); in every other phase the surface disappears completely (no empty
 * placeholder). Visibility and data follow the session's state listener, exactly like the scoping
 * accessory above the composer; add/remove intents run through the session's ONE scope path.
 */
final class ResearchPhaseAccessory implements ComposerAccessory {

    private final ResearchAgentSession research;
    private final ResearchBlacklistPanel view;
    private final Runnable refresh;

    ResearchPhaseAccessory(final ResearchAgentSession research, final UiExecutor uiExecutor) {
        this.research = research;
        this.view = new ResearchBlacklistPanel();
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
                        if (scoping) {
                            view.setExclusions(exclusions);
                        }
                    }
                });
            }
        };
        research.addStateListener(refresh);
        refresh.run(); // initial paint
    }

    /** A refused add/remove must stay visible — the chips would otherwise silently lie. */
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
        return Placement.BELOW_COMPOSER;
    }

    public void dispose() {
        research.removeStateListener(refresh);
    }
}
