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

    /**
     * Settings pages for the host's gear menu at the chat composer — rendered ONLY while this agent is the
     * selected one of the current chat tab, with that tab's live session. Session-based by contract:
     * the component works on the session's values, never on agent-global state. Default: none.
     */
    default List<com.aresstack.askai.plugin.api.agent.AgentSettingsContribution> getSettingsContributions() {
        return java.util.Collections.emptyList();
    }

    /**
     * Persistent components shown ABOVE the chat composer while this agent is the active one of the current
     * tab (e.g. active-phase controls). Generic and session-based; the host builds and disposes them on
     * session/agent/tab change. Default: none.
     */
    default List<com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution>
            getComposerAccessories() {
        return java.util.Collections.emptyList();
    }
}
