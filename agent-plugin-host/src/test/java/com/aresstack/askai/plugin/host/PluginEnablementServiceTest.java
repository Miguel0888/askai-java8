package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Enable/disable persists by stable id and a disabled plugin drops out of the selectable agent list. */
public class PluginEnablementServiceTest {

    @Test
    public void enablesByDefaultAndPersistsDisableAcrossInstances() {
        MapStore store = new MapStore();
        PluginEnablementService a = new PluginEnablementService(store);
        assertTrue(a.isEnabled("com.x.research"));

        a.setEnabled("com.x.research", false);
        assertFalse(a.isEnabled("com.x.research"));

        // A fresh instance over the same store still sees it disabled.
        assertFalse(new PluginEnablementService(store).isEnabled("com.x.research"));

        a.setEnabled("com.x.research", true);
        assertTrue(new PluginEnablementService(store).isEnabled("com.x.research"));
    }

    @Test
    public void disabledCatalogEntryIsNotOfferedAsAnAgent() {
        List<PluginCatalogEntry> catalog = new ArrayList<PluginCatalogEntry>();
        catalog.add(PluginCatalogEntry.builder()
                .pluginId("com.x.research")
                .descriptor(WorkspacePluginDescriptor.builder()
                        .id("com.x.research").displayName("Research Agent").version("1.0.0").build())
                .compatibility(PluginCompatibility.COMPATIBLE)
                .enabled(false) // user disabled it
                .build());

        List<WorkspaceModeEntry> agents = ChatWorkspaceHostPanel.buildAgents(catalog);
        assertTrue("a disabled plugin must not appear as an agent", agents.isEmpty());
    }

    private static final class MapStore implements WorkspaceStateStore {
        private final Map<String, String> values = new HashMap<String, String>();

        public String get(String key, String defaultValue) {
            String v = values.get(key);
            return v == null ? defaultValue : v;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            String v = values.get(key);
            return v == null ? defaultValue : Boolean.parseBoolean(v);
        }

        public int getInt(String key, int defaultValue) {
            String v = values.get(key);
            try {
                return v == null ? defaultValue : Integer.parseInt(v);
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        public void put(String key, String value) {
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
        }

        public void putBoolean(String key, boolean value) {
            put(key, Boolean.toString(value));
        }

        public void putInt(String key, int value) {
            put(key, Integer.toString(value));
        }
    }
}
