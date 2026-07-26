package com.aresstack.askai.research.plugin;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * PF4J plugin entry point. It does no work and creates no Swing components on start/stop — the workspace UI
 * is built lazily by {@link ResearchWorkspaceFactory} on the EDT when a workspace is opened.
 */
public final class ResearchPlugin extends Plugin {

    public ResearchPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }
}
