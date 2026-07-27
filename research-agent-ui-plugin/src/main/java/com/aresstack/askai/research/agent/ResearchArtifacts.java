package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The research agent's artifact catalog. Documents (outline, concept, notes, findings, draft, final) are plain
 * Markdown so the KI and the user share one editable format and the host can render them with its default
 * Markdown view. Sources and the state visualization are structured and get their own specialized views.
 *
 * <p>Type ids: {@code "markdown"} (host default view), {@code "research.sources"} and {@code "research.state"}
 * (plugin views). This is the single source of the artifact list in the new agent model.</p>
 */
public final class ResearchArtifacts {

    public static final String TYPE_MARKDOWN = "markdown";
    public static final String TYPE_SOURCES = "research.sources";
    public static final String TYPE_STATE = "research.state";

    private ResearchArtifacts() {
    }

    public static List<AgentArtifact> all() {
        List<AgentArtifact> list = new ArrayList<AgentArtifact>();
        list.add(markdown("outline", "Outline", "outline.md"));
        list.add(markdown("concept", "Concept", "concept.md"));
        list.add(markdown("research-notes", "Research Notes", "research-notes.md"));
        list.add(markdown("findings", "Findings", "findings.md"));
        list.add(markdown("draft", "Draft", "draft.md"));
        list.add(markdown("final", "Final Document", "final.md"));
        list.add(new Artifact("sources", "Sources", TYPE_SOURCES, ""));
        list.add(new Artifact("state", "State", TYPE_STATE, ""));
        return Collections.unmodifiableList(list);
    }

    private static AgentArtifact markdown(String id, String displayName, String relativePath) {
        return new Artifact(id, displayName, TYPE_MARKDOWN, relativePath);
    }

    /** Immutable {@link AgentArtifact}. Revision is a placeholder until the artifact store lands (Commit 13). */
    private static final class Artifact implements AgentArtifact {
        private final String id;
        private final String displayName;
        private final String typeId;
        private final String relativePath;

        private Artifact(String id, String displayName, String typeId, String relativePath) {
            this.id = id;
            this.displayName = displayName;
            this.typeId = typeId;
            this.relativePath = relativePath;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getArtifactTypeId() {
            return typeId;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public long getRevision() {
            return 0L;
        }
    }
}
