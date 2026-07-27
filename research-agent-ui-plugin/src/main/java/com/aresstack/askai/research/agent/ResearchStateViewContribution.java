package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentStateSnapshot;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * A specialized view for the {@code research.state} artifact: it visualizes the session's phase, run state and
 * pending approval from the {@link AgentStateSnapshot}. Commit 10 ships a minimal read-only placeholder; the
 * full state-machine visualization arrives in Commit 15. This proves the "specialized artifact view" path
 * (structured artifacts get plugin views; Markdown artifacts use the host's default view).
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
        JPanel panel = new JPanel(new BorderLayout());
        AgentStateSnapshot state = context.getSession() == null ? null : context.getSession().getState();
        String text = state == null
                ? "No active research session."
                : "Phase: " + state.getPhaseLabel() + "   ·   Run: " + state.getRunStateLabel()
                        + (state.hasPendingApproval() ? "   ·   awaiting approval" : "");
        panel.add(new JLabel(text), BorderLayout.NORTH);
        return panel;
    }
}
