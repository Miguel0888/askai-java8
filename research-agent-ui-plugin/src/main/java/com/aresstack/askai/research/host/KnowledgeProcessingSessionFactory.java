package com.aresstack.askai.research.host;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.capture.CanonicalUrlSourceIdResolver;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.capture.CaptureStoreSourceCaptureReader;
import com.aresstack.askai.research.knowledge.PassageSegmentation;
import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;
import com.aresstack.askai.research.knowledge.processing.FilePassageVectorStore;
import com.aresstack.askai.research.knowledge.processing.FileResearchProjectRepository;
import com.aresstack.askai.research.knowledge.processing.FileSourceProcessingQueue;
import com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingRunner;
import com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingSettings;
import com.aresstack.askai.research.knowledge.processing.RepositoryIndexableGenerationSource;
import com.aresstack.askai.research.knowledge.processing.ResearchProjectPassageStore;
import com.aresstack.askai.research.knowledge.processing.SourceCaptureReader;
import com.aresstack.askai.research.knowledge.processing.SourceProcessingWorker;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.embedding.UrlConnectionEmbeddingHttpTransport;
import com.aresstack.askai.research.knowledge.processing.index.SemanticKnowledgeIndex;
import com.aresstack.askai.research.knowledge.lucene.CompositeSemanticKnowledgeIndex;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.text.opennlp.DirectoryOpenNlpModelCatalog;
import com.aresstack.askai.research.text.opennlp.OpenNlpModelResolver;

import java.io.File;

/**
 * The composition root for the continuous knowledge worker of ONE productive research session (§13): it wires
 * the concrete adapters — HTTP embedding, OpenNLP sentence resolver, PassageSegmentation, the file-backed passage
 * store + canonical vectors, the Lucene+cosine {@link SemanticKnowledgeIndex} and the persisted-generation resume
 * source — into a {@link SourceProcessingWorker}, and hands back a NOT-yet-started {@link KnowledgeProcessingRunner}.
 * No algorithm lives here; the {@code ProductiveResearchBackendFactory} stays a composition root and delegates the
 * knowledge wiring to this small class.
 */
final class KnowledgeProcessingSessionFactory {

    private KnowledgeProcessingSessionFactory() {
    }

    /**
     * Build (but do not start) the serial worker for a session's authoritative embedding world. The caller starts
     * the runner once construction has fully succeeded and stops it on session close (Variant B lifted only here).
     */
    static KnowledgeProcessingRunner buildRunner(File projectDir, String projectId,
                                                 EmbeddingEndpointDescriptor descriptor, String languageCode,
                                                 File openNlpModelsDir, CaptureStore captures,
                                                 ResearchSourceRepository sourceRepository,
                                                 FileSourceProcessingQueue queue,
                                                 KnowledgeProcessingSettings settings) {
        // Embedding: strict batch /api/embed adapter over the SESSION descriptor (its own timeout).
        HttpEmbeddingPortAdapter embeddings = new HttpEmbeddingPortAdapter(descriptor,
                new UrlConnectionEmbeddingHttpTransport((int) descriptor.timeoutMillis));

        // Sentences: the deployed OpenNLP model for the session language, else the deterministic regex fallback.
        SentenceSegmentationPort segmenter =
                new OpenNlpModelResolver(new DirectoryOpenNlpModelCatalog(openNlpModelsDir))
                        .segmenterFor(languageCode);

        PassageSegmentation segmentation = new PassageSegmentation(segmenter, embeddings,
                settings.segmentationPipelineVersion, settings.windowSize, settings.boundaryThreshold,
                settings.minPassageSentences, settings.maxPassageSentences);

        // Canonical persistence (source of truth) + rebuildable index projection.
        FileResearchProjectRepository repository = new FileResearchProjectRepository(projectDir);
        FilePassageVectorStore vectorStore = new FilePassageVectorStore(projectDir);
        ResearchProjectPassageStore passageStore =
                new ResearchProjectPassageStore(repository, projectId, vectorStore);
        SemanticKnowledgeIndex index = new CompositeSemanticKnowledgeIndex(projectDir, projectId);
        RepositoryIndexableGenerationSource generations =
                new RepositoryIndexableGenerationSource(repository, vectorStore, projectId);

        SourceCaptureReader reader = new CaptureStoreSourceCaptureReader(captures,
                new CanonicalUrlSourceIdResolver(sourceRepository));

        final SourceProcessingWorker worker = new SourceProcessingWorker(queue, reader, segmentation,
                passageStore, index, generations, projectId, settings.maxProcessingAttempts,
                descriptor.embeddingFingerprint(), SourceProcessingWorker.Listener.NONE);

        return new KnowledgeProcessingRunner(new KnowledgeProcessingRunner.ProcessingStep() {
            public boolean processOne() {
                return worker.processOne();
            }
        }, "knowledge-processing-" + projectId, 1000L, 5000L);
    }
}
