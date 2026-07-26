package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Questing agent list and agent-resolution/fallback logic (pure, no PF4J). */
public class WorkspaceAgentResolutionTest {

    private static PluginCatalogEntry selectable(String id, String name) {
        return PluginCatalogEntry.builder()
                .pluginId(id)
                .descriptor(WorkspacePluginDescriptor.builder().id(id).displayName(name).version("1.0.0").build())
                .compatibility(PluginCompatibility.COMPATIBLE)
                .enabled(true)
                .build();
    }

    private static PluginCatalogEntry incompatible(String id, String name) {
        return PluginCatalogEntry.builder()
                .pluginId(id)
                .descriptor(WorkspacePluginDescriptor.builder().id(id).displayName(name).version("1.0.0").build())
                .compatibility(PluginCompatibility.INCOMPATIBLE_API_VERSION)
                .enabled(true)
                .build();
    }

    @Test
    public void agentListContainsOnlySelectablePluginsWithoutDuplicates() {
        List<PluginCatalogEntry> catalog = new ArrayList<PluginCatalogEntry>();
        catalog.add(selectable("com.aresstack.askai.research", "Research Agent"));
        catalog.add(selectable("com.aresstack.askai.research", "Research Agent"));
        catalog.add(incompatible("com.x.broken", "Broken"));

        List<WorkspaceModeEntry> agents = ChatWorkspaceHostPanel.buildAgents(catalog);
        assertEquals(1, agents.size());
        assertEquals("com.aresstack.askai.research", agents.get(0).getId());
        assertEquals(WorkspaceModeEntry.Kind.PLUGIN, agents.get(0).getKind());
    }

    @Test
    public void resolvesDesiredAgentWhenAvailable() {
        List<WorkspaceModeEntry> agents = ChatWorkspaceHostPanel.buildAgents(java.util.Arrays.asList(
                selectable("a.one", "One"), selectable("a.two", "Two")));
        assertEquals("a.two", ChatWorkspaceHostPanel.resolveQuestingAgent("a.two", agents));
    }

    @Test
    public void fallsBackToFirstAgentWhenDesiredIsGone() {
        List<WorkspaceModeEntry> agents = ChatWorkspaceHostPanel.buildAgents(java.util.Arrays.asList(
                selectable("a.one", "One"), selectable("a.two", "Two")));
        assertEquals("a.one", ChatWorkspaceHostPanel.resolveQuestingAgent("a.gone", agents));
    }

    @Test
    public void resolvesToNullWhenNoAgentsInstalled() {
        assertNull(ChatWorkspaceHostPanel.resolveQuestingAgent("a.one", new ArrayList<WorkspaceModeEntry>()));
        assertNull(ChatWorkspaceHostPanel.resolveQuestingAgent(null, new ArrayList<WorkspaceModeEntry>()));
    }

    @Test
    public void buildAgentsToleratesNullCatalog() {
        assertTrue(ChatWorkspaceHostPanel.buildAgents(null).isEmpty());
    }
}
