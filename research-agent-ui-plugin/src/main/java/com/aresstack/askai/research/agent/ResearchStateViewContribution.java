package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.JComponent;

/**
 * Contributes the read-only {@link ResearchStateView} for the {@code research.state} artifact. The view
 * subscribes to the session's state changes and re-reads the domain snapshot on the EDT via the host
 * {@link UiExecutor}; it removes its listener when the component is removed from the hierarchy so no listener
 * leaks after a plugin disable / agent switch (which also closes the session, halting events).
 */
public final class ResearchStateViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_STATE;
    }

    @Override
    public String getDisplayName() {
        return "State";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        final ResearchStateView view = new ResearchStateView();
        AgentSession session = context.getSession();
        if (!(session instanceof ResearchAgentSession)) {
            return view;
        }
        final ResearchAgentSession research = (ResearchAgentSession) session;
        final UiExecutor uiExecutor = context.getUiExecutor();
        final Runnable refresh = new Runnable() {
            public void run() {
                // Snapshot is read on the caller thread but applied to Swing via the UiExecutor.
                final ResearchStateSnapshot snapshot = research.currentResearchSnapshot();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.setSnapshot(snapshot);
                    }
                });
            }
        };
        view.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                // (Re)shown: re-attach the observer AND re-read the snapshot so a change made while hidden
                // shows immediately instead of staying stale until a restart (addStateListener is addIfAbsent).
                research.addStateListener(refresh);
                refresh.run();
            }

            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }

            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                research.removeStateListener(refresh);
            }
        });
        research.addStateListener(refresh);
        refresh.run(); // initial paint
        return view;
    }
}
