package com.aresstack.askai.research.store;

import java.io.File;

/**
 * A single research project on disk. Layout (relative paths under the project root):
 *
 * <pre>
 * project/
 * ├── artifacts/   outline.md, document.md (+ .meta); legacy projects may still hold concept.md,
 * │                research-notes.md, findings.md, draft.md, final.md — kept untouched, never re-tabbed
 * ├── sources/     &lt;sourceId&gt;.properties
 * └── state/       research-session.json
 * </pre>
 *
 * Markdown files stay human-readable; sources and state are structured. Multiple projects are isolated by
 * their root directory. Everything is created lazily on first write.
 */
public final class ResearchProjectStore {

    private final File root;
    private final FileArtifactStore artifactStore;
    private final FileResearchSourceRepository sourceRepository;
    private final SessionStateFileStore sessionStateStore;

    public ResearchProjectStore(File projectRoot) {
        this.root = projectRoot;
        this.artifactStore = new FileArtifactStore(new File(projectRoot, "artifacts"));
        this.sourceRepository = new FileResearchSourceRepository(new File(projectRoot, "sources"));
        this.sessionStateStore = new SessionStateFileStore(new File(projectRoot, "state"));
    }

    public File getRoot() {
        return root;
    }

    public FileArtifactStore artifacts() {
        return artifactStore;
    }

    public FileResearchSourceRepository sources() {
        return sourceRepository;
    }

    public SessionStateFileStore sessionState() {
        return sessionStateStore;
    }
}
