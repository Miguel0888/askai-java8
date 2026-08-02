package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.JComponent;
import javax.swing.JLabel;

/**
 * Contributes the {@link ResearchBriefView} for the {@code research.brief} artifact (the
 * "Fragestellung" tab). The view re-reads the brief store on session state changes (agent turns may
 * update the working copy) via the host {@link UiExecutor}; the listener is removed when the
 * component leaves the hierarchy so nothing leaks after a plugin disable / agent switch.
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
        AgentSession session = context.getSession();
        if (!(session instanceof ResearchAgentSession)) {
            return new JLabel("The research brief needs a research session.");
        }
        final ResearchAgentSession research = (ResearchAgentSession) session;
        final ResearchBriefView view = new ResearchBriefView(research.getBriefStore());
        final UiExecutor uiExecutor = context.getUiExecutor();
        final Runnable refresh = new Runnable() {
            public void run() {
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.reloadFromStore(); // skipped while the user has unsaved edits
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
            }
        });
        return view;
    }
}
