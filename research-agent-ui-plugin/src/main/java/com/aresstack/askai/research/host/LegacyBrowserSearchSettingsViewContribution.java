package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifacts;

import javax.swing.JComponent;
import javax.swing.JLabel;

/**
 * Contributes the {@link LegacyBrowserSearchSettingsPanel} as the {@code research.search.settings}
 * view. Saved changes apply to NEW research sessions; the running session keeps (and displays) its
 * immutable settings snapshot.
 */
public final class LegacyBrowserSearchSettingsViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_SEARCH_SETTINGS;
    }

    @Override
    public String getDisplayName() {
        return "Search Settings";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        AgentSession session = context.getSession();
        if (!(session instanceof ResearchAgentSession)) {
            return new JLabel("Search settings are only available for research sessions.");
        }
        ResearchAgentSession research = (ResearchAgentSession) session;
        return new LegacyBrowserSearchSettingsPanel(research.getHostStateStore(),
                research.getActiveSearchProfile());
    }
}
