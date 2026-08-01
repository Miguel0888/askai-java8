package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Session-based settings, LAST-SETTING-WINS for new chats: a NEW chat starts with the user's latest
 * choice, an EXISTING chat is frozen at first use and never reconfigured from outside, and a restored
 * chat finds exactly its own values again.
 */
public class SessionScopedWorkspaceStateStoreTest {

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
            } catch (NumberFormatException ex) {
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
    public void aNewChatStartsWithTheUsersLastSetting() {
        MemoryStore template = new MemoryStore();
        SessionScopedWorkspaceStateStore tabA =
                new SessionScopedWorkspaceStateStore(template, new MemoryStore());
        tabA.put("research.search.strategy", "API_PROVIDER");

        // A chat opened AFTERWARDS starts from the user's latest choice (write-through template).
        SessionScopedWorkspaceStateStore newChat =
                new SessionScopedWorkspaceStateStore(template, new MemoryStore());
        assertEquals("API_PROVIDER", newChat.get("research.search.strategy", "LEGACY_BROWSER"));
    }

    @Test
    public void anExistingChatKeepsItsSettingsWhenAnotherChatChangesThem() {
        MemoryStore template = new MemoryStore();
        SessionScopedWorkspaceStateStore oldChat =
                new SessionScopedWorkspaceStateStore(template, new MemoryStore());
        // The old chat resolved its value once (browser default) — frozen from now on.
        assertEquals("LEGACY_BROWSER", oldChat.get("research.search.strategy", "LEGACY_BROWSER"));

        SessionScopedWorkspaceStateStore otherChat =
                new SessionScopedWorkspaceStateStore(template, new MemoryStore());
        otherChat.put("research.search.strategy", "API_PROVIDER");

        assertEquals("the old chat keeps ITS settings", "LEGACY_BROWSER",
                oldChat.get("research.search.strategy", "LEGACY_BROWSER"));
    }

    @Test
    public void writesReachTheSessionAndTheTemplate() {
        MemoryStore template = new MemoryStore();
        MemoryStore session = new MemoryStore();
        new SessionScopedWorkspaceStateStore(template, session).put("k", "mine");
        assertEquals("mine", session.values.get("k"));
        assertEquals("the last setting becomes the default for new chats", "mine",
                template.values.get("k"));
    }

    @Test
    public void aRestoredChatFindsExactlyItsOwnValues() {
        MemoryStore template = new MemoryStore();
        MemoryStore persistedSessionScope = new MemoryStore(); // survives, keyed by the stable scope id
        new SessionScopedWorkspaceStateStore(template, persistedSessionScope)
                .putBoolean("research.runtime.llmNarration", true);
        // Meanwhile the user changed the setting elsewhere — the template moved on.
        template.put("research.runtime.llmNarration", "false");
        // Restart: a NEW layered store over the SAME persisted session scope → its own value wins.
        SessionScopedWorkspaceStateStore restored =
                new SessionScopedWorkspaceStateStore(template, persistedSessionScope);
        assertTrue(restored.getBoolean("research.runtime.llmNarration", false));
    }

    @Test
    public void theSearchSourceDefaultIsTheBrowserNeverARestApi() {
        // Nothing configured anywhere → the shipped default is the browser SERP path.
        SessionScopedWorkspaceStateStore fresh =
                new SessionScopedWorkspaceStateStore(new MemoryStore(), new MemoryStore());
        assertEquals("LEGACY_BROWSER", fresh.get("research.search.strategy", "LEGACY_BROWSER"));
        assertEquals("", fresh.get("research.search.provider", ""));
    }
}
