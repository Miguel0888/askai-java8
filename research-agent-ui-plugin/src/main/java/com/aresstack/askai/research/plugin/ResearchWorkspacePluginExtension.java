package com.aresstack.askai.research.plugin;

import com.aresstack.askai.plugin.api.WorkspaceFactory;
import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;
import com.aresstack.askai.plugin.pf4j.api.WorkspacePluginExtension;

import org.pf4j.Extension;

/** The stateless PF4J extension the host discovers; it only describes the plugin and hands out a factory. */
@Extension
public final class ResearchWorkspacePluginExtension implements WorkspacePluginExtension {

    @Override
    public WorkspacePluginDescriptor getDescriptor() {
        return ResearchPluginDescriptor.create();
    }

    @Override
    public WorkspaceFactory getWorkspaceFactory() {
        return new ResearchWorkspaceFactory();
    }
}
