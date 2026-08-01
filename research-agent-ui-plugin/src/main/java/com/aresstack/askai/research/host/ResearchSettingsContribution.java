package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSettingsContribution;
import com.aresstack.askai.research.agent.ResearchAgentSession;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;

/**
 * The research agent's settings pages in the HOST's gear menu: the EXISTING runtime and browser-search
 * panels, moved (not reinvented) out of the artifact area — settings are not work products. The panels
 * work on the SESSION's state store, so every chat tab configures exactly its own research session; the
 * central AskAI model selection and the provider credential files stay where they are.
 */
public final class ResearchSettingsContribution implements AgentSettingsContribution {

    @Override
    public String getDisplayName() {
        return "Research Agent";
    }

    @Override
    public JComponent createSettingsComponent(AgentSession session) {
        if (!(session instanceof ResearchAgentSession)) {
            return null; // not this plugin's session → the host omits the category
        }
        ResearchAgentSession research = (ResearchAgentSession) session;
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Runtime", new ResearchRuntimeSettingsPanel(research.getHostStateStore()));
        tabs.addTab("Search", new LegacyBrowserSearchSettingsPanel(research.getHostStateStore(),
                research.getActiveSearchProfile()));
        return tabs;
    }
}
