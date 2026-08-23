package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * The legacy "Search URL" override is DEAD, not migrated: it silently replaced the whole engine list
 * (order, fallback endpoints, per-engine result pages, delay) with a single page-1-only engine while
 * everything LOOKED configured. There is no field, no getter, no {@code --search-url} pass-through in
 * the productive spawn anymore — and a persisted leftover value is DESTROYED in the store the moment
 * settings are loaded or saved, so the file cannot keep claiming an override that nothing reads.
 */
public class ResearchRuntimeSettingsMigrationTest {

    private static final class MemoryStore implements WorkspaceStateStore {
        final Map<String, String> values = new HashMap<String, String>();

        public String get(String key, String defaultValue) {
            return values.containsKey(key) ? values.get(key) : defaultValue;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : defaultValue;
        }

        public int getInt(String key, int defaultValue) {
            try {
                return values.containsKey(key) ? Integer.parseInt(values.get(key)) : defaultValue;
            } catch (NumberFormatException invalid) {
                return defaultValue;
            }
        }

        public void put(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, String.valueOf(value));
        }

        public void putInt(String key, int value) {
            values.put(key, String.valueOf(value));
        }
    }

    @Test
    public void aPersistedLegacySearchUrlIsDestroyedOnSave() {
        MemoryStore store = new MemoryStore();
        store.put(ResearchRuntimeSettings.KEY_SEARCH_URL, "https://www.bing.com/search?q={query}");

        ResearchRuntimeSettings.load(store).save(store);

        assertEquals("the poisonous leftover is gone from the store, not merely ignored",
                "", store.get(ResearchRuntimeSettings.KEY_SEARCH_URL, ""));
    }
}
