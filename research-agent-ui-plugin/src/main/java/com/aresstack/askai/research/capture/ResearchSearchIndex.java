package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.sources.ResearchSourceRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The indexing boundary: only ACCEPTED sources are ever indexed (never every visited page), and the index is
 * a derived view that can be rebuilt from the source records at any time. Lucene stays behind this port; the
 * productive Lucene adapter is a later slice (see problems.md) — the in-memory adapter below serves tests
 * and the MVP.
 */
public interface ResearchSearchIndex {

    void index(ResearchSourceDocument document);

    void remove(String sourceId);

    void rebuild(Collection<ResearchSourceRecord> sources);

    /** What gets indexed for one accepted source (id + searchable text; never raw HTML). */
    final class ResearchSourceDocument {
        private final String sourceId;
        private final String title;
        private final String text;

        public ResearchSourceDocument(String sourceId, String title, String text) {
            this.sourceId = sourceId;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
        }

        public String getSourceId() { return sourceId; }
        public String getTitle() { return title; }
        public String getText() { return text; }
    }

    /** Deterministic in-memory adapter (substring search); rebuild() proves the derived-view property. */
    final class InMemory implements ResearchSearchIndex {
        private final Map<String, ResearchSourceDocument> byId =
                new LinkedHashMap<String, ResearchSourceDocument>();
        private boolean failNextIndex; // test hook: simulate an index failure

        public synchronized void failNextIndex() {
            failNextIndex = true;
        }

        public synchronized void index(ResearchSourceDocument document) {
            if (failNextIndex) {
                failNextIndex = false;
                throw new IllegalStateException("simulated index failure");
            }
            byId.put(document.getSourceId(), document);
        }

        public synchronized void remove(String sourceId) {
            byId.remove(sourceId);
        }

        public synchronized void rebuild(Collection<ResearchSourceRecord> sources) {
            byId.clear();
            for (ResearchSourceRecord source : sources) {
                byId.put(source.getSourceId(), new ResearchSourceDocument(
                        source.getSourceId(), source.getTitle(), source.getComment()));
            }
        }

        public synchronized int size() {
            return byId.size();
        }

        public synchronized List<String> search(String needle) {
            List<String> hits = new ArrayList<String>();
            String n = needle.toLowerCase(Locale.ROOT);
            for (ResearchSourceDocument d : byId.values()) {
                if (d.getTitle().toLowerCase(Locale.ROOT).contains(n)
                        || d.getText().toLowerCase(Locale.ROOT).contains(n)) {
                    hits.add(d.getSourceId());
                }
            }
            return hits;
        }
    }
}
