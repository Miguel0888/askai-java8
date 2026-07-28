package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Global settings persistence: only real overrides are stored (values equal to the default keep
 * following default improvements), an override to the EMPTY string stays representable, and every
 * save bumps the revision that new sessions freeze into their snapshot.
 */
public class LegacyBrowserSearchSettingsStoreTest {

    private final WorkspaceStateStore store = new InMemoryStore();

    @Test
    public void storesOnlyRealOverridesAndBumpsTheRevision() {
        assertEquals(0L, LegacyBrowserSearchSettingsStore.revision(store));
        Map<String, String> values = LegacyBrowserSearchSettingsCodec
                .toValues(LegacyBrowserSearchDefaults.create());
        values.put("captcha.challengeProbeIntervalMillis", "2500");
        values.put("navigation.language", ""); // equals the default → NOT an override
        LegacyBrowserSearchSettingsStore.saveValues(store, values);

        Map<String, String> loaded = LegacyBrowserSearchSettingsStore.loadValues(store);
        assertEquals("2500", loaded.get("captcha.challengeProbeIntervalMillis"));
        assertFalse("default-equal values must not become overrides",
                loaded.containsKey("navigation.language"));
        assertEquals(1L, LegacyBrowserSearchSettingsStore.revision(store));

        // Override to the EMPTY string (clearing a non-empty default) must survive the roundtrip.
        values.put("consent.positiveButtonTexts", "");
        LegacyBrowserSearchSettingsStore.saveValues(store, values);
        loaded = LegacyBrowserSearchSettingsStore.loadValues(store);
        assertTrue(loaded.containsKey("consent.positiveButtonTexts"));
        assertEquals("", loaded.get("consent.positiveButtonTexts"));
        assertEquals(2L, LegacyBrowserSearchSettingsStore.revision(store));
    }

    static final class InMemoryStore implements WorkspaceStateStore {
        private final Map<String, String> map = new HashMap<String, String>();

        public String get(String key, String defaultValue) {
            return map.containsKey(key) ? map.get(key) : defaultValue;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            String v = map.get(key);
            return v == null ? defaultValue : Boolean.parseBoolean(v);
        }

        public int getInt(String key, int defaultValue) {
            try {
                return map.containsKey(key) ? Integer.parseInt(map.get(key)) : defaultValue;
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        public void put(String key, String value) {
            map.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            map.put(key, String.valueOf(value));
        }

        public void putInt(String key, int value) {
            map.put(key, String.valueOf(value));
        }
    }
}
