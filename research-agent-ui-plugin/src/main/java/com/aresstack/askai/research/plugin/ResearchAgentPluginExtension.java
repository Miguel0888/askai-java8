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
        // Settings are NOT artifacts: runtime + search configuration moved to the gear-menu settings
        // contribution below; the artifact area holds work products only.
        // The "Visualisierung" drawer tab is GONE: the sources mindmap lives behind the square
        // toolbar button next to the Websuche (and /map) as a transcript overlay instead.
        return Arrays.<ArtifactViewContribution>asList(
                new com.aresstack.askai.research.agent.ResearchBriefViewContribution(),
                new com.aresstack.askai.research.agent.ResearchOutlineViewContribution(),
                new ResearchSourcesViewContribution(),
                new ResearchStateViewContribution());
    }

    @Override
    public List<com.aresstack.askai.plugin.api.agent.AgentSettingsContribution>
            getSettingsContributions() {
        return java.util.Collections
                .<com.aresstack.askai.plugin.api.agent.AgentSettingsContribution>singletonList(
                        new com.aresstack.askai.research.host.ResearchSettingsContribution());
    }

    @Override
    public List<com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution>
            getComposerAccessories() {
        // ABOVE the composer: the scoping controls (map + suggestions + query). OVER the
        // transcript's top: the phase-bound surface (Phase 1 = the out-of-scope sky; other
        // phases show nothing).
        return java.util.Arrays
                .<com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution>asList(
                        new com.aresstack.askai.research.agent.ScopingComposerAccessoryContribution(),
                        new com.aresstack.askai.research.agent.ResearchPhaseAccessoryContribution());
    }

    @Override
    public javax.swing.Icon getMenuIcon() {
        // The research flask BRANDS the workspace hamburger while this agent is active; the host
        // shows the plain hamburger again on hover so the click's function stays obvious.
        return new com.aresstack.askai.research.agent.ResearchPhaseToolbarContribution.FlaskIcon();
    }

    @Override
    public List<com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution>
            getToolbarContributions() {
        // The session language switch lives in the drawer's CHATS FOOTER, the phase selector holds
        // the CENTERED spot, and the "Websuche" tag (+ mindmap button) trails at the far right.
        return java.util.Arrays
                .<com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution>asList(
                        new com.aresstack.askai.research.agent.ResearchLanguageToolbarContribution(),
                        new com.aresstack.askai.research.agent.ResearchPhaseToolbarContribution(),
                        new com.aresstack.askai.research.agent.ResearchWebSearchToolbarContribution());
    }
}
