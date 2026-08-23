package com.aresstack.askai.browser.search.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WHICH engines are used and IN WHICH ORDER, plus how they are worked through. This replaces the old
 * "primary engine plus fallback templates" idea: there is no privileged engine and no second-class one,
 * only an ordered set of enabled engines and a strategy.
 * <p>
 * The order is the execution order — what the user arranges is what happens, not a hint.
 */
public final class BrowserSearchEngineSelection {

    /** One line of the user's engine list: which engine, and whether it takes part at all. */
    public static final class Entry {
        private final String engineId;
        private final boolean enabled;

        public Entry(String engineId, boolean enabled) {
            if (engineId == null || engineId.trim().isEmpty()) {
                throw new IllegalArgumentException("engine id must not be empty");
            }
            this.engineId = engineId.trim();
            this.enabled = enabled;
        }

        public String getEngineId() {
            return engineId;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    private final List<Entry> entries;
    private final EngineAcquisitionMode mode;

    public BrowserSearchEngineSelection(List<Entry> entries, EngineAcquisitionMode mode) {
        this.entries = Collections.unmodifiableList(new ArrayList<Entry>(
                entries == null ? Collections.<Entry>emptyList() : entries));
        this.mode = mode == null ? EngineAcquisitionMode.FIRST_USABLE : mode;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public EngineAcquisitionMode getMode() {
        return mode;
    }

    /**
     * The engines to actually visit, in order. Entries whose id the catalog does not know are skipped:
     * a configuration written by a newer build must not make this one search with nothing.
     */
    public List<BrowserSearchEngine> resolvedEnabledEngines() {
        List<BrowserSearchEngine> resolved = new ArrayList<BrowserSearchEngine>();
        for (Entry entry : entries) {
            if (!entry.isEnabled()) {
                continue;
            }
            BrowserSearchEngine engine = BrowserSearchEngineCatalog.byId(entry.getEngineId());
            if (engine != null) {
                resolved.add(engine);
            }
        }
        return resolved;
    }

    // ------------------------------------------------------------------ flat-string form (settings codec)

    /**
     * {@code "duckduckgo:on,bing:off"} — order carries the priority, so the encoding is a LIST, not a map.
     */
    public String encodeEntries() {
        StringBuilder sb = new StringBuilder();
        for (Entry entry : entries) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getEngineId()).append(':').append(entry.isEnabled() ? "on" : "off");
        }
        return sb.toString();
    }

    /** Parse the flat form; unreadable pieces are skipped rather than failing the whole configuration. */
    public static List<Entry> parseEntries(String encoded) {
        List<Entry> entries = new ArrayList<Entry>();
        if (encoded == null || encoded.trim().isEmpty()) {
            return entries;
        }
        for (String piece : encoded.split(",")) {
            String text = piece.trim();
            if (text.isEmpty()) {
                continue;
            }
            int colon = text.lastIndexOf(':');
            String id = colon < 0 ? text : text.substring(0, colon).trim();
            boolean enabled = colon < 0 || !"off".equalsIgnoreCase(text.substring(colon + 1).trim());
            if (!id.isEmpty()) {
                entries.add(new Entry(id, enabled));
            }
        }
        return entries;
    }
}
