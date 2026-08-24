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

    /**
     * REGRESSION (the user's unchecked engine kept coming back): the host hands plugins a
     * SESSION-SCOPING store that freezes every non-global key into the chat at first read — a save
     * made in one chat was invisible in every other, and a chat that had already read the key kept
     * its frozen "no override" forever. Search settings therefore MUST live under the
     * {@code global.} routing prefix, which such wrappers route straight to the shared backing.
     * This test runs the store against a faithful freezing wrapper: if the prefix is ever removed,
     * the frozen tombstone wins again and this fails.
     */
    @Test
    public void aSaveInOneChatReachesEveryOtherChatDespiteFreezingSessionScopes() {
        InMemoryStore shared = new InMemoryStore();
        WorkspaceStateStore chatA = freezingSessionScope(shared);
        WorkspaceStateStore chatB = freezingSessionScope(shared);

        // Chat B reads the settings BEFORE the user changes anything (session create freezes keys).
        assertTrue(LegacyBrowserSearchSettingsStore.loadValues(chatB).isEmpty());

        // The user disables an engine in chat A and saves.
        Map<String, String> values = LegacyBrowserSearchSettingsCodec
                .toValues(LegacyBrowserSearchDefaults.create());
        values.put("navigation.engines", "duckduckgo:off:3,bing:on:3");
        LegacyBrowserSearchSettingsStore.saveValues(chatA, values);

        assertEquals("the unchecked engine survives into the OTHER, already-frozen chat",
                "duckduckgo:off:3,bing:on:3",
                LegacyBrowserSearchSettingsStore.loadValues(chatB).get("navigation.engines"));
        assertEquals("…and into a chat created afterwards",
                "duckduckgo:off:3,bing:on:3",
                LegacyBrowserSearchSettingsStore.loadValues(freezingSessionScope(shared))
                        .get("navigation.engines"));
    }

    /** The host wrapper's documented semantics: freeze-at-first-read, except {@code global.} keys. */
    private static WorkspaceStateStore freezingSessionScope(final InMemoryStore shared) {
        final InMemoryStore session = new InMemoryStore();
        return new WorkspaceStateStore() {
            public String get(String key, String defaultValue) {
                if (key != null && key.startsWith(GLOBAL_KEY_PREFIX)) {
                    return shared.get(key, defaultValue);
                }
                String own = session.get(key, null);
                if (own != null) {
                    return own;
                }
                String inherited = shared.get(key, null);
                String resolved = inherited != null ? inherited : defaultValue;
                if (resolved != null) {
                    session.put(key, resolved);
                }
                return resolved;
            }

            public boolean getBoolean(String key, boolean defaultValue) {
                return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
            }

            public int getInt(String key, int defaultValue) {
                try {
                    return Integer.parseInt(get(key, String.valueOf(defaultValue)));
                } catch (NumberFormatException invalid) {
                    return defaultValue;
                }
            }

            public void put(String key, String value) {
                if (key != null && key.startsWith(GLOBAL_KEY_PREFIX)) {
                    shared.put(key, value);
                    return;
                }
                session.put(key, value);
                shared.put(key, value);
            }

            public void putBoolean(String key, boolean value) {
                put(key, String.valueOf(value));
            }

            public void putInt(String key, int value) {
                put(key, String.valueOf(value));
            }
        };
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
