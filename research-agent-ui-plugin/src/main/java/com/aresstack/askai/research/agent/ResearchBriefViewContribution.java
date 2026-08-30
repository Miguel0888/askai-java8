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
        // The manual ⟳ runs exactly the same re-read as every listener — never a second path.
        view.setRefreshAction(refresh);
        view.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                // Tab (re)shown: RE-ATTACH the observers AND RE-READ, so content written while
                // the tab was hidden appears immediately. Both adds are addIfAbsent-safe.
                research.addStateListener(refresh);
                attachConceptListener(research, refresh);
                refresh.run();
            }

            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }

            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                // Tab hidden/closed: stop live updates so the listener sets never grow, but keep
                // the reusable MarkdownViews intact for a later re-show.
                research.removeStateListener(refresh);
                com.aresstack.askai.research.concept.ConceptBranchService service =
                        research.conceptBranchService();
                if (service != null) {
                    service.removeChangeListener(refresh);
                }
            }
        });
        research.addStateListener(refresh);
        // Subscribe DIRECTLY at the service — the single shared truth notifies on every applied
        // edit, with no delegation chain in between (the live gate caught the long chain
        // dropping updates: the JSON view sat on rev 1 while the agent committed rev 3).
        attachConceptListener(research, refresh);
        refresh.run(); // initial paint (shows persisted working copy on restore)
        return view;
    }

    private static void attachConceptListener(ResearchAgentSession research, Runnable refresh) {
        com.aresstack.askai.research.concept.ConceptBranchService service =
                research.conceptBranchService();
        if (service != null) {
            service.addChangeListener(refresh);
        }
    }
}
