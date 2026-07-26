package com.aresstack.askai.plugin.pf4j.api;

import com.aresstack.askai.plugin.api.WorkspaceFactory;
import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;

import org.pf4j.ExtensionPoint;

/**
 * The single PF4J extension point a workspace plugin implements. It must be stateless: it only describes the
 * plugin and hands out a {@link WorkspaceFactory}. Host services are supplied later, at
 * {@code WorkspaceFactory.createWorkspace(...)}, never in the extension constructor.
 */
public interface WorkspacePluginExtension extends ExtensionPoint {

    WorkspacePluginDescriptor getDescriptor();

    WorkspaceFactory getWorkspaceFactory();
}
