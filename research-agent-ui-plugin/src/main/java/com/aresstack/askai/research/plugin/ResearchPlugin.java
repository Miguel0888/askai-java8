package com.aresstack.askai.research.plugin;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * PF4J plugin entry point. It does no work and creates no Swing components on start/stop — the research agent
 * is exposed via {@link ResearchAgentPluginExtension}; its UI (chat activity + artifact views) is built lazily
 * by the host when the agent session is activated.
 */
public final class ResearchPlugin extends Plugin {

    public ResearchPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }
}
