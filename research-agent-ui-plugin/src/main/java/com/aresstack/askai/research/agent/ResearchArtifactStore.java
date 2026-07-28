package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link AgentArtifactStore} for the research clickdummy: one Markdown document per artifact id,
 * each with a monotonic revision. Optimistic locking rejects writes whose {@code expectedRevision} is stale,
 * so the user's editor and the (future) agent tools cannot silently overwrite each other. A later slice can
 * swap this for a project-file-backed store without touching the views.
 */
public final class ResearchArtifactStore implements AgentArtifactStore {

    private final Map<String, Entry> entries = new HashMap<String, Entry>();

    public ResearchArtifactStore() {
        // Deliberately EMPTY: no pre-invented sample artifacts. Every document starts at revision 0
        // with no content and only ever contains what the user or the agent actually produced —
        // a fabricated outline presented for "approval" is exactly the clickdummy behavior this
        // store must never cause again.
    }

    @Override
    public ArtifactContent read(String artifactId) {
        Entry entry = entries.get(artifactId);
        return entry == null ? new ArtifactContent("", 0L) : new ArtifactContent(entry.markdown, entry.revision);
    }

    @Override
    public ArtifactWriteResult replace(String artifactId, long expectedRevision, String markdown) {
        Entry entry = entries.get(artifactId);
        long current = entry == null ? 0L : entry.revision;
        if (expectedRevision != current) {
            return ArtifactWriteResult.conflict(entry == null ? "" : entry.markdown, current);
        }
        long next = current + 1L;
        entries.put(artifactId, new Entry(markdown == null ? "" : markdown, next));
        return ArtifactWriteResult.ok(next);
    }

    private static final class Entry {
        private final String markdown;
        private final long revision;

        private Entry(String markdown, long revision) {
            this.markdown = markdown;
            this.revision = revision;
        }
    }
}
