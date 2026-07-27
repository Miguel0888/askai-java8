package com.aresstack.askai.research.sources;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifacts;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/** Contributes the structured {@link ResearchSourcesView} for the {@code research.sources} artifact. */
public final class ResearchSourcesViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_SOURCES;
    }

    @Override
    public String getDisplayName() {
        return "Sources";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        AgentSession session = context.getSession();
        if (session instanceof ResearchAgentSession) {
            ResearchSourceRepository repository = ((ResearchAgentSession) session).getSourceRepository();
            return new ResearchSourcesView(repository, ResearchSourcesView.demoKnownSections());
        }
        JPanel placeholder = new JPanel(new BorderLayout());
        placeholder.add(new JLabel("No source repository available."), BorderLayout.NORTH);
        return placeholder;
    }
}
