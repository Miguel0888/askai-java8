package com.aresstack.askai.research.plugin;

import com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor;
import com.aresstack.askai.plugin.api.agent.AgentSessionFactory;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;
import com.aresstack.askai.research.agent.ResearchAgentSessionFactory;
import com.aresstack.askai.research.agent.ResearchChatCommands;
import com.aresstack.askai.research.agent.ResearchStateViewContribution;
import com.aresstack.askai.research.sources.ResearchSourcesViewContribution;

import org.pf4j.Extension;

import java.util.Arrays;
import java.util.List;

/**
 * The new-model PF4J extension: the research agent extends the shared chat instead of replacing it. It lives
 * the sole entry point for the research agent: the standalone workspace shell and its
 * {@code WorkspacePluginExtension} were removed in Commit 17. Plugin id and JAR are unchanged.
 */
@Extension
public final class ResearchAgentPluginExtension implements AgentPluginExtension {

    /** The stable Research Agent id (also the plugin id). */
    public static final String AGENT_ID = ResearchPluginDescriptor.PLUGIN_ID;

    @Override
    public AgentPluginDescriptor getAgentDescriptor() {
        return AgentPluginDescriptor.builder()
                .id(AGENT_ID)
                .displayName("Research Agent")
                .description("Structured research assistant: outline, sources, findings, draft and final document")
                .version("0.1.0")
                .pluginApiVersion(1)
                .provider("AresStack")
                .displayOrder(10)
                .build();
    }

    @Override
    public AgentSessionFactory getSessionFactory() {
        return new ResearchAgentSessionFactory();
    }

    @Override
    public List<ChatCommandContribution> getChatCommands() {
        return ResearchChatCommands.all();
    }

    @Override
    public List<ArtifactViewContribution> getArtifactViews() {
        return Arrays.<ArtifactViewContribution>asList(
                new ResearchSourcesViewContribution(),
                new ResearchStateViewContribution());
    }
}
