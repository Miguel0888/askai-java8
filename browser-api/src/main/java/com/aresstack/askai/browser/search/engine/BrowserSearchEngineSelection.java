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

    /**
     * One line of the user's engine list: which engine, whether it takes part, how many result pages,
     * and how long to pause before every FURTHER request to this engine (anti-captcha pacing).
     */
    public static final class Entry {

        /** Result pages fetched per engine when the configuration names none — a SETTING's fallback. */
        public static final int DEFAULT_RESULT_PAGES = 3;

        /** No extra pause between requests to the same engine unless the user asks for one. */
        public static final int DEFAULT_DELAY_MILLIS = 0;

        private final String engineId;
        private final boolean enabled;
        private final int resultPages;
        private final int delayMillis;

        public Entry(String engineId, boolean enabled) {
            this(engineId, enabled, DEFAULT_RESULT_PAGES, DEFAULT_DELAY_MILLIS);
        }

        public Entry(String engineId, boolean enabled, int resultPages) {
            this(engineId, enabled, resultPages, DEFAULT_DELAY_MILLIS);
        }

        public Entry(String engineId, boolean enabled, int resultPages, int delayMillis) {
            if (engineId == null || engineId.trim().isEmpty()) {
                throw new IllegalArgumentException("engine id must not be empty");
            }
            this.engineId = engineId.trim();
            this.enabled = enabled;
            this.resultPages = resultPages > 0 ? resultPages : DEFAULT_RESULT_PAGES;
            this.delayMillis = delayMillis > 0 ? delayMillis : DEFAULT_DELAY_MILLIS;
        }

        public String getEngineId() {
            return engineId;
        }

        public boolean isEnabled() {
            return enabled;
        }

        /** How many result pages of this engine one search fetches (always >= 1). */
        public int getResultPages() {
            return resultPages;
        }

        /** Pause before every request to this engine after the first, in milliseconds (0 = off). */
        public int getDelayMillis() {
            return delayMillis;
        }
    }

    // ------------------------------------------------------------------ delay <-> seconds text

    /**
     * The delay as the seconds text the user reads and writes: up to three decimals, trailing zeros
     * (and a bare decimal point) removed — {@code 1500 -> "1.5"}, {@code 250 -> "0.25"},
     * {@code 2000 -> "2"}, {@code 0 -> "0"}.
     */
    public static String formatDelaySeconds(int delayMillis) {
        String text = String.format(java.util.Locale.ROOT, "%.3f", delayMillis / 1000.0);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** Seconds text (decimal point or comma) back to milliseconds; unreadable or negative means OFF. */
    public static int parseDelayMillis(String secondsText) {
        if (secondsText == null) {
            return Entry.DEFAULT_DELAY_MILLIS;
        }
        try {
            double seconds = Double.parseDouble(secondsText.trim().replace(',', '.'));
            return seconds > 0 ? (int) Math.round(seconds * 1000.0) : Entry.DEFAULT_DELAY_MILLIS;
        } catch (NumberFormatException invalid) {
            return Entry.DEFAULT_DELAY_MILLIS;
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
     * {@code "duckduckgo:on:3,bing:off:3:1.5"} — order carries the priority, so the encoding is a
     * LIST, not a map; the third part is the engine's result-page count, the fourth its request delay
     * in seconds (up to three decimals; a delay of 0 is simply not written).
     */
    public String encodeEntries() {
        StringBuilder sb = new StringBuilder();
        for (Entry entry : entries) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getEngineId()).append(':').append(entry.isEnabled() ? "on" : "off")
                    .append(':').append(entry.getResultPages());
            if (entry.getDelayMillis() > 0) {
                sb.append(':').append(formatDelaySeconds(entry.getDelayMillis()));
            }
        }
        return sb.toString();
    }

    /** The configured result-page count for this engine (the entry's value, else the default). */
    public int resultPagesFor(String engineId) {
        for (Entry entry : entries) {
            if (entry.getEngineId().equals(engineId)) {
                return entry.getResultPages();
            }
        }
        return Entry.DEFAULT_RESULT_PAGES;
    }

    /** The configured pause before repeat requests to this engine, in milliseconds (0 = off). */
    public int delayMillisFor(String engineId) {
        for (Entry entry : entries) {
            if (entry.getEngineId().equals(engineId)) {
                return entry.getDelayMillis();
            }
        }
        return Entry.DEFAULT_DELAY_MILLIS;
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
            // "id" | "id:on" | "id:on:3" | "id:on:3:1.5" — older, shorter configurations keep the
            // documented defaults for the parts they do not carry.
            String[] parts = text.split(":");
            String id = parts[0].trim();
            boolean enabled = parts.length < 2 || !"off".equalsIgnoreCase(parts[1].trim());
            int pages = Entry.DEFAULT_RESULT_PAGES;
            if (parts.length >= 3) {
                try {
                    pages = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException invalid) {
                    pages = Entry.DEFAULT_RESULT_PAGES;
                }
            }
            int delayMillis = parts.length >= 4 ? parseDelayMillis(parts[3])
                    : Entry.DEFAULT_DELAY_MILLIS;
            if (!id.isEmpty()) {
                entries.add(new Entry(id, enabled, pages, delayMillis));
            }
        }
        return entries;
    }
}
