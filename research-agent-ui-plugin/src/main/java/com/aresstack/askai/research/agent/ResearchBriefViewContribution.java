package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.store.FileResearchBriefStore;

import javax.swing.JComponent;

/**
 * Contributes the "Fragestellung" view for the {@code research.brief} artifact — the scoping phase's PRIMARY
 * artifact. It reads the brief working copy from the session's {@link FileResearchBriefStore} (the single
 * source of truth) and re-renders on every session state change; the store is re-read on restore, so the same
 * persisted content reappears after a tab/phase switch. Read-only, no approval, no phase transition. The
 * listener + host MarkdownView are released when the view leaves the hierarchy.
 */
public final class ResearchBriefViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_BRIEF;
    }

    @Override
    public String getDisplayName() {
        return "Fragestellung";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        final ResearchBriefView view = new ResearchBriefView(context.getMarkdownViewFactory());
        AgentSession session = context.getSession();
        if (!(session instanceof ResearchAgentSession)) {
            return view;
        }
        final ResearchAgentSession research = (ResearchAgentSession) session;
        final UiExecutor uiExecutor = context.getUiExecutor();
        final Runnable refresh = new Runnable() {
            public void run() {
                FileResearchBriefStore store = research.researchBriefStore();
                final String content = store == null ? "" : store.effectiveContent();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.render(content);
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
        refresh.run(); // initial paint (shows persisted working copy on restore)
        return view;
    }
}
