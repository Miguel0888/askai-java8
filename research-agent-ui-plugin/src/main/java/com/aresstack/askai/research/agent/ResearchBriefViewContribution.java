package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.store.FileResearchBriefStore;

import javax.swing.JComponent;

/**
 * Contributes the "Konzept" view for the {@code research.brief} artifact — the scoping phase's PRIMARY
 * artifact. Since K3 it shows the {@link ConceptPaperView}: mindmap + read-only JSON of the Konzeptpapier
 * (one atomic snapshot per refresh, rendered straight from the session's ConceptBranchService — no second
 * UI model, no JSON in events) plus the legacy brief markdown until K4 retires it. Re-renders on every
 * session state change; the stores are re-read on restore. Read-only, no approval, no phase transition.
 */
public final class ResearchBriefViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_BRIEF;
    }

    @Override
    public String getDisplayName() {
        return "Konzept";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        final ConceptPaperView view = new ConceptPaperView(context.getMarkdownViewFactory());
        AgentSession session = context.getSession();
        if (!(session instanceof ResearchAgentSession)) {
            return view;
        }
        final ResearchAgentSession research = (ResearchAgentSession) session;
        final UiExecutor uiExecutor = context.getUiExecutor();
        final Runnable refresh = new Runnable() {
            public void run() {
                // ONE atomic snapshot per refresh (mindmap + JSON + revision from the same
                // state); the store stays the only truth — no event ever carries the JSON.
                com.aresstack.askai.research.concept.ConceptBranchService service =
                        research.conceptBranchService();
                final com.aresstack.askai.research.concept.ConceptProjection projection =
                        service == null ? null
                                : com.aresstack.askai.research.concept.ConceptProjection
                                        .of(service.snapshot());
                FileResearchBriefStore store = research.researchBriefStore();
                final String brief = store == null ? "" : store.effectiveContent();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.render(projection, brief);
                    }
                });
            }
        };
        view.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                // Tab (re)shown: RE-ATTACH the observer AND RE-READ the current brief from the store, so a
                // brief written while the tab was hidden appears immediately instead of staying blank until
                // an app restart. addStateListener is addIfAbsent, so repeated calls are safe.
                research.addStateListener(refresh);
                refresh.run();
            }

            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }

            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                // Tab hidden/closed: stop live updates so the listener set never grows, but keep the reusable
                // MarkdownView intact for a later re-show (disposing it here would break re-render on return).
                research.removeStateListener(refresh);
            }
        });
        research.addStateListener(refresh);
        refresh.run(); // initial paint (shows persisted working copy on restore)
        return view;
    }
}
