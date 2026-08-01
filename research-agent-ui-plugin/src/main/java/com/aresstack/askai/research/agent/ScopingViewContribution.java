package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.backend.ScopingAssistantUpdate;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import javax.swing.JComponent;

/**
 * Contributes the {@link ScopingSupportView} for the {@code research.scoping} artifact: the exploration map +
 * search suggestions for the SCOPING phase. It subscribes to session state changes, re-reads the latest
 * projection ({@link ResearchAgentSession#latestScopingProjection()}) and re-applies it on the EDT; the panel
 * is shown ONLY while the active phase is scoping and reappears with its last projection on return. The
 * Markdown/Mermaid rendering is the host's (via {@link ArtifactViewContext#getMarkdownViewFactory()}), so the
 * plugin never depends on the app's renderer. The listener + the host MarkdownView are released when the view
 * leaves the hierarchy.
 */
public final class ScopingViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_SCOPING;
    }

    @Override
    public String getDisplayName() {
        return "Exploration";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        AgentSession session = context.getSession();
        final ScopingSupportView view = new ScopingSupportView(context.getMarkdownViewFactory());
        if (!(session instanceof ResearchAgentSession)) {
            return view;
        }
        final ResearchAgentSession research = (ResearchAgentSession) session;
        final UiExecutor uiExecutor = context.getUiExecutor();
        final Runnable refresh = new Runnable() {
            public void run() {
                final boolean inScoping = ResearchStateIds.SCOPING.equals(
                        research.currentResearchSnapshot().getCurrentPhaseId());
                final ScopingAssistantUpdate projection = research.latestScopingProjection();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.setVisible(inScoping); // shown only in scoping; hidden elsewhere
                        if (inScoping && projection != null) {
                            view.apply(projection);
                        }
                    }
                });
            }
        };
        research.addStateListener(refresh);
        view.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
            }

            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }

            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                research.removeStateListener(refresh);
                view.dispose();
            }
        });
        refresh.run(); // initial paint
        return view;
    }
}
