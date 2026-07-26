package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The mode list always leads with Normal Chat, de-duplicates, and keeps an active-but-vanished plugin. */
public class ChatWorkspaceHostModesTest {

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
    public void normalChatIsAlwaysFirstAndOnlyByDefault() {
        List<WorkspaceMode> modes = ChatWorkspaceHostPanel.buildModes(new ArrayList<PluginCatalogEntry>(),
                null, WorkspaceMode.NORMAL_CHAT_ID);
        assertEquals(1, modes.size());
        assertEquals(WorkspaceMode.NORMAL_CHAT_ID, modes.get(0).getId());
    }

    @Test
    public void onlySelectablePluginsAppear() {
        List<PluginCatalogEntry> catalog = new ArrayList<PluginCatalogEntry>();
        catalog.add(selectable("com.x.research", "Research Agent"));
        catalog.add(incompatible("com.x.broken", "Broken"));

        List<WorkspaceMode> modes = ChatWorkspaceHostPanel.buildModes(catalog, null,
                WorkspaceMode.NORMAL_CHAT_ID);
        assertEquals(2, modes.size());
        assertEquals(WorkspaceMode.NORMAL_CHAT_ID, modes.get(0).getId());
        assertEquals("com.x.research", modes.get(1).getId());
        assertEquals("Research Agent", modes.get(1).getDisplayName());
    }

    @Test
    public void anActivePluginIsRetainedEvenIfItLeavesTheCatalog() {
        List<WorkspaceMode> modes = ChatWorkspaceHostPanel.buildModes(new ArrayList<PluginCatalogEntry>(),
                "com.x.research", "com.x.research");
        assertTrue(modes.stream().anyMatch(m -> "com.x.research".equals(m.getId())));
    }

    @Test
    public void noDuplicateEntriesOnRepeatedCatalog() {
        List<PluginCatalogEntry> catalog = new ArrayList<PluginCatalogEntry>();
        catalog.add(selectable("com.x.research", "Research Agent"));
        catalog.add(selectable("com.x.research", "Research Agent"));
        List<WorkspaceMode> modes = ChatWorkspaceHostPanel.buildModes(catalog, "com.x.research",
                "com.x.research");
        int count = 0;
        for (WorkspaceMode m : modes) {
            if ("com.x.research".equals(m.getId())) {
                count++;
            }
        }
        assertEquals(1, count);
    }
}
