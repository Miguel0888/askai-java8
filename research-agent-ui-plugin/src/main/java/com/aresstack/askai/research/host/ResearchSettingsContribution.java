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
        final SearchProviderCardsPanel searchProviders =
                new SearchProviderCardsPanel(research.getHostStateStore());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Runtime", new ResearchRuntimeSettingsPanel(research.getHostStateStore()));
        // The provider PICKER + each provider's own settings card (browser default, full DataForSEO
        // editor). The old shared engine/locale fields are gone — each provider owns its full config.
        tabs.addTab("Search", searchProviders);
        tabs.addTab("Browser SERP", new LegacyBrowserSearchSettingsPanel(research.getHostStateStore(),
                research.getActiveSearchProfile()));
        // The DataForSEO draft + provider selection persist when the gear dialog is disposed; unsaved
        // provider drafts are dropped. The host's settings dialog does not expose an explicit Save hook,
        // so the panel commits on removal from the hierarchy (dialog close).
        tabs.addHierarchyListener(new java.awt.event.HierarchyListener() {
            public void hierarchyChanged(java.awt.event.HierarchyEvent event) {
                if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                        && !searchProviders.isShowing()) {
                    searchProviders.save();
                }
            }
        });
        return tabs;
    }
}
