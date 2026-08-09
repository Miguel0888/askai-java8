package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.visualize.VisualizationProjection;
import com.aresstack.askai.research.visualize.VisualizationStatus;

import javax.swing.JComponent;

/**
 * Contributes the "Visualisierung" view for the {@code research.visualization} artifact — a DERIVED view of
 * the research brief, not a source-of-truth artifact. It reads the session's latest
 * {@link VisualizationProjection} and re-renders on every session state change (the projection is refreshed
 * lazily by the host-side visualizer). Read-only, no approval, no phase transition. The listener + host
 * MarkdownView are released when the view leaves the hierarchy.
 */
public final class ResearchVisualizationViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_VISUALIZATION;
    }

    @Override
    public String getDisplayName() {
        return "Visualisierung";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        final ResearchVisualizationView view = new ResearchVisualizationView(context.getMarkdownViewFactory());
        AgentSession session = context.getSession();
        if (!(session instanceof ResearchAgentSession)) {
            return view;
        }
        final ResearchAgentSession research = (ResearchAgentSession) session;
        final UiExecutor uiExecutor = context.getUiExecutor();
        // Issue #29: the button is the ONLY generation trigger — opening/refreshing this view never is.
        // Issue #33: the button is an ADAPTER over the session's derived-action command (the service MCP
        // invokes the same command) — no action logic lives in the view.
        view.setGenerateAction(new Runnable() {
            public void run() {
                research.derivedActions().generateVisualization();
            }
        });
        final Runnable refresh = new Runnable() {
            public void run() {
                final VisualizationProjection projection = research.latestVisualization();
                final VisualizationStatus status = research.visualizationStatus();
                final boolean stale = research.visualizationStale();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.render(status, projection, stale);
                    }
                });
            }
        };
        view.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                // (Re)shown: re-attach the observer AND re-read the latest projection so an update made while
                // hidden shows immediately instead of staying stale until a restart (addIfAbsent-safe).
                research.addStateListener(refresh);
                refresh.run();
            }

            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }

            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                // Stop live updates while hidden, but keep the reusable MarkdownView intact for a later
                // re-show (disposing it here would break re-render on return).
                research.removeStateListener(refresh);
            }
        });
        research.addStateListener(refresh);
        refresh.run(); // initial paint (placeholder until a visualization exists)
        return view;
    }
}
