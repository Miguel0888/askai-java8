package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.backend.ScopingAssistantUpdate;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import javax.swing.JComponent;

/**
 * The scoping controls as a COMPOSER ACCESSORY (above the composer), not a hidden artifact view. It wraps the
 * reusable {@link ScopingSupportView}, keeps it in sync with the live session (latest projection + scoping-only
 * visibility) via the session's state listener, and is disposed by the host on session/agent/tab change — an
 * explicit lifecycle instead of an AncestorListener. Phase changes within the session drive visibility here
 * without any rebuild, so the user's query draft survives a phase round-trip.
 */
final class ScopingComposerAccessory implements ComposerAccessory {

    private final ResearchAgentSession research;
    private final ScopingSupportView view;
    private final Runnable refresh;

    ScopingComposerAccessory(ResearchAgentSession research, final UiExecutor uiExecutor,
                             MarkdownViewFactory markdownViewFactory) {
        this.research = research;
        this.view = new ScopingSupportView(markdownViewFactory);
        this.refresh = new Runnable() {
            public void run() {
                final boolean scoping = ResearchStateIds.SCOPING.equals(
                        research.currentResearchSnapshot().getCurrentPhaseId());
                final ScopingAssistantUpdate projection = research.latestScopingProjection();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.setVisible(scoping); // shown only in scoping; hidden elsewhere
                        if (scoping && projection != null) {
                            view.apply(projection);
                        }
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

    public void dispose() {
        research.removeStateListener(refresh);
        view.dispose();
    }
}
