package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Session-based settings semantics: a fresh session READS the agent-global template, every WRITE stays in
 * the session scope, two sessions never reconfigure each other, and a restored session (same scope) finds
 * exactly its own values again.
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
    public void aFreshSessionReadsTheAgentTemplate() {
        MemoryStore template = new MemoryStore();
        template.put("research.search.strategy", "API_PROVIDER");
        SessionScopedWorkspaceStateStore store =
                new SessionScopedWorkspaceStateStore(template, new MemoryStore());
        assertEquals("API_PROVIDER", store.get("research.search.strategy", "LEGACY_BROWSER"));
    }

    @Test
    public void writesStayInTheSessionAndNeverTouchTheTemplate() {
        MemoryStore template = new MemoryStore();
        template.put("k", "template");
        MemoryStore session = new MemoryStore();
        SessionScopedWorkspaceStateStore store = new SessionScopedWorkspaceStateStore(template, session);
        store.put("k", "mine");
        assertEquals("mine", store.get("k", ""));
        assertEquals("the template is read-only", "template", template.values.get("k"));
        assertEquals("mine", session.values.get("k"));
    }

    @Test
    public void twoSessionsOfTheSameAgentNeverReconfigureEachOther() {
        MemoryStore template = new MemoryStore();
        template.put("research.search.strategy", "LEGACY_BROWSER");
        SessionScopedWorkspaceStateStore tabA =
                new SessionScopedWorkspaceStateStore(template, new MemoryStore());
        SessionScopedWorkspaceStateStore tabB =
                new SessionScopedWorkspaceStateStore(template, new MemoryStore());

        tabA.put("research.search.strategy", "API_PROVIDER");
        tabA.put("research.search.provider", "BRAVE_SEARCH_API");

        assertEquals("tab A changed", "API_PROVIDER", tabA.get("research.search.strategy", ""));
        assertEquals("tab B is untouched", "LEGACY_BROWSER", tabB.get("research.search.strategy", ""));
        assertEquals("tab B falls back to its default", "",
                tabB.get("research.search.provider", ""));
    }

    @Test
    public void aRestoredSessionFindsExactlyItsOwnValues() {
        MemoryStore template = new MemoryStore();
        MemoryStore persistedSessionScope = new MemoryStore(); // survives, keyed by the stable scope id
        new SessionScopedWorkspaceStateStore(template, persistedSessionScope)
                .putBoolean("research.runtime.llmNarration", true);
        // Restart: a NEW layered store over the SAME persisted session scope.
        SessionScopedWorkspaceStateStore restored =
                new SessionScopedWorkspaceStateStore(template, persistedSessionScope);
        assertTrue(restored.getBoolean("research.runtime.llmNarration", false));
    }
}
