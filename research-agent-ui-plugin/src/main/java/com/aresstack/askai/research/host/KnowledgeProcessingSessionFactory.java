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
     * Resolves a research language into the session's sentence segmenter — the seam behind which the host NLP
     * snapshot provider (with its documented regex-fallback / hard-failure semantics) stays. Resolved lazily
     * PER LANGUAGE and cached by the factory below, so a job's immutable language snapshot picks its sentence
     * model without re-loading the artifact per capture.
     */
    interface SentenceSegmenterResolver {
        SessionSentenceSegmenter resolve(String languageCode);
    }

    /** Everything the continuous knowledge capability of one session owns: worker + live projection. */
    static final class KnowledgeSession {
        final KnowledgeProcessingRunner worker;
        final com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner projection;

        KnowledgeSession(KnowledgeProcessingRunner worker,
                         com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner
                                 projection) {
            this.worker = worker;
            this.projection = projection;
        }
    }

    /** A hook fired after every persisted projection rebuild (C5d writes the outline artifact here). */
    interface ProjectionListener {
        void onProjectionUpdated(
                com.aresstack.askai.research.knowledge.live.LiveOutlineProjection projection);

        ProjectionListener NONE = new ProjectionListener() {
            public void onProjectionUpdated(
                    com.aresstack.askai.research.knowledge.live.LiveOutlineProjection projection) {
            }
        };
    }

    /**
     * Build (but do not start) the serial worker for a session's authoritative embedding world. The caller starts
     * the runner once construction has fully succeeded and stops it on session close (Variant B lifted only here).
     */
    static KnowledgeSession buildRunner(File projectDir, String projectId,
                                                 EmbeddingEndpointDescriptor descriptor,
                                                 String sessionLanguageCode,
                                                 final SentenceSegmenterResolver segmenterResolver,
                                                 CaptureStore captures,
                                                 final ResearchSourceRepository sourceRepository,
                                                 FileSourceProcessingQueue queue,
                                                 KnowledgeProcessingSettings settings,
                                                 final java.util.List<String> briefQuestions,
                                                 final ProjectionListener projectionListener) {
        // Embedding: strict batch /api/embed adapter over the SESSION descriptor (its own timeout).
        final HttpEmbeddingPortAdapter embeddings = new HttpEmbeddingPortAdapter(descriptor,
                new UrlConnectionEmbeddingHttpTransport((int) descriptor.timeoutMillis));

        // Sentences: resolved PER JOB LANGUAGE (cached) — a job's immutable language snapshot picks its own
        // sentence model, so the job enqueued after a language switch segments with the NEW language while a
        // running job keeps its instance. Resolution failures surface as that job's stage failure.
        final KnowledgeProcessingSettings s = settings;
        final java.util.Map<String, PassageSegmentation> byLanguage =
                new java.util.concurrent.ConcurrentHashMap<String, PassageSegmentation>();
        final java.util.Map<String, String> descriptionByLanguage =
                new java.util.concurrent.ConcurrentHashMap<String, String>();
        final SourceProcessingWorker.SegmentationFactory segmentationFactory =
                new SourceProcessingWorker.SegmentationFactory() {
                    public PassageSegmentation forLanguage(String languageCode) {
                        String lang = "de".equalsIgnoreCase(languageCode == null ? "" : languageCode.trim())
                                ? "de" : "en";
                        PassageSegmentation cached = byLanguage.get(lang);
                        if (cached == null) {
                            SentenceSegmenterResolver r = segmenterResolver;
                            SessionSentenceSegmenter resolved = r.resolve(lang);
                            cached = new PassageSegmentation(resolved.segmenter, embeddings,
                                    s.segmentationPipelineVersion, lang, s.windowSize, s.boundaryThreshold,
                                    s.minPassageSentences, s.maxPassageSentences);
                            byLanguage.put(lang, cached);
                            descriptionByLanguage.put(lang, resolved.description);
                        }
                        return cached;
                    }
                };
        // Eager fail-fast + the one-time ready line for the SESSION language (the same behavior as before):
        // a broken selected model for the session language still fails the session build, not the first job.
        segmentationFactory.forLanguage(sessionLanguageCode);
        String segmenterDescription = descriptionByLanguage.values().iterator().next();

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

        // One-time readiness line for the live gate: language, the resolved segmenter (model id/version/artifact
        // name — never an absolute path — or the regex reason), and the embedding world.
        System.err.println("[research-knowledge] worker ready project=" + projectId
                + " language=" + sessionLanguageCode
                + " segmenter=" + segmenterDescription
                + " embeddingModel=" + descriptor.modelId
                + " fingerprint=" + descriptor.embeddingFingerprint()
                + " dimension=" + descriptor.embeddingDimension
                + " endpoint=" + descriptor.endpointUrl());

        // C5: the live projection stack — active corpus (persisted passages + vectors, source-filtered) →
        // deterministic clustering → LiveOutlineProjection → rebuildable store. NEVER re-embeds; a corrupt or
        // missing persisted projection only costs a rebuild and never blocks the session start.
        final com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader corpusReader =
                new com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader(
                        repository, vectorStore, projectId, descriptor.embeddingFingerprint());
        final com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader.SourceFilter
                sourceFilter = new com.aresstack.askai.research.knowledge.processing.live
                        .ActiveKnowledgeCorpusReader.SourceFilter() {
                    public boolean includeSource(String sourceId) {
                        com.aresstack.askai.research.sources.ResearchSourceRecord record =
                                sourceRepository.get(sourceId);
                        if (record == null) {
                            return true; // unknown → include (canonical data decides, never a silent drop)
                        }
                        com.aresstack.askai.research.sources.SourceStatus status = record.getStatus();
                        return status != com.aresstack.askai.research.sources.SourceStatus.EXCLUDED
                                && status != com.aresstack.askai.research.sources.SourceStatus.DUPLICATE
                                && status != com.aresstack.askai.research.sources.SourceStatus.SUPERSEDED;
                    }

                    public boolean isUserRelevant(String sourceId) {
                        com.aresstack.askai.research.sources.ResearchSourceRecord record =
                                sourceRepository.get(sourceId);
                        return record != null && record.isUserRelevant();
                    }
                };
        final com.aresstack.askai.research.knowledge.processing.live.FileLiveOutlineProjectionStore
                projectionStore = new com.aresstack.askai.research.knowledge.processing.live
                        .FileLiveOutlineProjectionStore(projectDir);
        final com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder projectionBuilder =
                new com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder();
        final String fingerprint = descriptor.embeddingFingerprint();
        final com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner projection =
                new com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner(
                        new com.aresstack.askai.research.knowledge.processing.live
                                .LiveKnowledgeProjectionRunner.RebuildStep() {
                            public void rebuild() {
                                com.aresstack.askai.research.knowledge.processing.live
                                        .ActiveKnowledgeCorpusReader.Corpus corpus =
                                        corpusReader.read(sourceFilter);
                                com.aresstack.askai.research.knowledge.live.LiveOutlineProjection previous =
                                        projectionStore.load();
                                long nextRevision = (previous == null ? 0L
                                        : previous.getProjectionRevision()) + 1L;
                                com.aresstack.askai.research.knowledge.live.LiveOutlineProjection next =
                                        projectionBuilder.build(nextRevision, fingerprint,
                                                System.currentTimeMillis(), corpus.getPassages(),
                                                corpus.getVectors(), briefQuestions);
                                projectionStore.save(next);
                                System.err.println("[research-knowledge] outline rebuilt revision="
                                        + next.getProjectionRevision()
                                        + " topics=" + next.getTopics().size()
                                        + " sections=" + next.getSections().size());
                                (projectionListener == null ? ProjectionListener.NONE : projectionListener)
                                        .onProjectionUpdated(next);
                            }
                        }, "knowledge-projection-" + projectId, 1500L);

        final SourceProcessingWorker worker = new SourceProcessingWorker(queue, reader, segmentationFactory,
                passageStore, index, generations, projectId, settings.maxProcessingAttempts,
                descriptor.embeddingFingerprint(), diagnosticListener(projectId, descriptionByLanguage));

        KnowledgeProcessingRunner workerRunner = new KnowledgeProcessingRunner(
                new KnowledgeProcessingRunner.ProcessingStep() {
                    public boolean processOne() {
                        return worker.processOne();
                    }
                }, "knowledge-processing-" + projectId, 1000L, 5000L);
        return new KnowledgeSession(workerRunner, projection);
    }

    /**
     * Per-job diagnostics to the app console (the live-gate evidence: captureId, sentence/passage counts,
     * embedding fingerprint, COMPLETED / failure stage). Cheap and side-effect free.
     */
    private static SourceProcessingWorker.Listener diagnosticListener(
            final String projectId, final java.util.Map<String, String> descriptionByLanguage) {
        return new SourceProcessingWorker.Listener() {
            public void onStarted(
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingJob job) {
                // PER-JOB language + segmenter: a mixed language state in a live run is immediately visible.
                String jobLanguage = job.getRequest().getLanguageCode();
                String segmenter = descriptionByLanguage.get(jobLanguage);
                System.err.println("[research-knowledge] processing captureId=" + job.getRequest().getCaptureId()
                        + " project=" + projectId + " language=" + jobLanguage
                        + " segmenter=" + (segmenter == null ? "(resolved on first use)" : segmenter)
                        + " fingerprint=" + job.getRequest().getEmbeddingModelFingerprint());
            }

            public void onCompleted(
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingJob job,
                    int sentenceCount, int passageCount) {
                System.err.println("[research-knowledge] COMPLETED captureId=" + job.getRequest().getCaptureId()
                        + " sentences=" + (sentenceCount == RESUMED ? "resumed" : Integer.toString(sentenceCount))
                        + " passages=" + passageCount
                        + " fingerprint=" + job.getRequest().getEmbeddingModelFingerprint());
            }

            public void onFailed(
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingJob job,
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingFailure failure) {
                System.err.println("[research-knowledge] " + failure.getStage() + "_FAILED captureId="
                        + job.getRequest().getCaptureId() + " retryable=" + failure.isRetryable()
                        + " reason=" + failure.getReason());
            }
        };
    }
}
