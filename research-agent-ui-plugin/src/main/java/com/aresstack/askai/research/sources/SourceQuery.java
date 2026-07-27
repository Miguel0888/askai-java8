package com.aresstack.askai.research.sources;

/**
 * A filter over sources: free text (matched against title/origin/url/author) and an optional status. A
 * {@code null} status means "any". Kept intentionally small; richer querying can be added behind the port.
 */
public final class SourceQuery {

    private final String text;
    private final SourceStatus status;

    public SourceQuery(String text, SourceStatus status) {
        this.text = text == null ? "" : text.trim();
        this.status = status;
    }

    public static SourceQuery all() {
        return new SourceQuery("", null);
    }

    public String getText() {
        return text;
    }

    public SourceStatus getStatus() {
        return status;
    }

    public boolean matches(ResearchSourceRecord record) {
        if (status != null && record.getStatus() != status) {
            return false;
        }
        if (text.isEmpty()) {
            return true;
        }
        String needle = text.toLowerCase();
        return contains(record.getTitle(), needle) || contains(record.getOrigin(), needle)
                || contains(record.getUrl(), needle) || contains(record.getAuthor(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }
}
