package com.aresstack.askai.plugin.pf4j.api;

import com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor;
import com.aresstack.askai.plugin.api.agent.AgentSessionFactory;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;

import org.pf4j.ExtensionPoint;

import java.util.List;

/**
 * The PF4J extension point a <em>chat agent</em> plugin implements. An agent extends the shared AskAI chat
 * rather than replacing it: it describes itself, creates isolated sessions, and contributes slash commands and
 * artifact views. It must be stateless — host services are supplied later at
 * {@link AgentSessionFactory#create}. This lives alongside {@link WorkspacePluginExtension}; a single plugin
 * may expose both during the migration.
 */
public interface AgentPluginExtension extends ExtensionPoint {

    AgentPluginDescriptor getAgentDescriptor();

    AgentSessionFactory getSessionFactory();

    /** Slash commands this agent contributes to the shared composer; never {@code null} (may be empty). */
    List<ChatCommandContribution> getChatCommands();

    /** Views for the agent's structured (non-Markdown) artifacts; never {@code null} (may be empty). */
    List<ArtifactViewContribution> getArtifactViews();
}
