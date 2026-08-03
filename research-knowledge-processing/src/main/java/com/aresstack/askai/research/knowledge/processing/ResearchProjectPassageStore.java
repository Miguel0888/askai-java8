package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.ResearchProjectRepository;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;

import java.util.List;

/**
 * The productive {@link PassageStore}: records one processed capture's sentences and passages onto the
 * {@code research-domain} {@link ResearchProject} aggregate and saves it through the {@link
 * ResearchProjectRepository}. The project directory stays the single source of truth; embedding/index views are
 * rebuildable projections and are NOT written here.
 *
 * <p>ONE capture = ONE commit: a store loads the current project, records this capture + its sentences +
 * passages (all record-ops are idempotent — re-storing the same derivation changes nothing), and saves. The
 * file repository writes the capture's whole generation and then atomically swaps that capture's active pointer,
 * so the capture's derived data becomes visible at once and a crash never leaves new-sentences-with-old-passages.
 * Other captures already in the project are re-serialized unchanged (no-op writes), never re-committed as new.</p>
 */
public final class ResearchProjectPassageStore implements PassageStore {

    private final ResearchProjectRepository repository;
    private final String projectId;

    public ResearchProjectPassageStore(ResearchProjectRepository repository, String projectId) {
        if (repository == null) {
            throw new IllegalArgumentException("repository is required");
        }
        this.repository = repository;
        this.projectId = projectId;
    }

    @Override
    public void store(SourceCapture capture, List<Sentence> sentences, List<Passage> passages) {
        ResearchProject project = repository.load(projectId);
        project.recordSourceCapture(capture);
        project.recordSentences(sentences);
        project.recordPassages(passages);
        repository.save(project); // per-capture generation + atomic active-pointer swap = the one commit point
    }
}
