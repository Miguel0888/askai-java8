package com.aresstack.askai.research.host;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationException;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshot;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.nlp.NlpModelDescriptor;
import com.aresstack.askai.research.knowledge.RegexSentenceSegmenter;
import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;
import com.aresstack.askai.research.text.opennlp.OpenNlpModelResolver;
import com.aresstack.askai.research.text.opennlp.SentenceModelLoader;
import com.aresstack.askai.research.text.opennlp.SingleArtifactOpenNlpModelCatalog;

import java.io.File;
import java.util.Optional;

/**
 * Resolves the SESSION's authoritative language into a {@link SentenceSegmentationPort} via the host
 * {@link NlpConfigurationSnapshotProvider} — resolved ONCE per session/processing configuration, never per capture,
 * and never from the current global settings or a store scan in the plugin. The concrete OpenNLP loading stays in
 * {@code research-text-opennlp} (the plugin only hands it a descriptor's artifact path).
 *
 * <p>Semantics (as specified): {@code MODEL_NOT_CONFIGURED}/{@code MODEL_NOT_INSTALLED} (and no provider) →
 * deterministic regex fallback; {@code ARTIFACT_MISSING}/{@code CHECKSUM_MISMATCH} → HARD failure; a present but
 * corrupt/unloadable model → the hard {@code OpenNlpModelException} from the resolver — never a silent regex.</p>
 */
final class SessionSentenceSegmenter {

    final SentenceSegmentationPort segmenter;
    /** For the diagnostic "worker ready" line — model id/version/artifact-name or the regex reason. No paths. */
    final String description;

    private SessionSentenceSegmenter(SentenceSegmentationPort segmenter, String description) {
        this.segmenter = segmenter;
        this.description = description;
    }

    static SessionSentenceSegmenter resolve(NlpConfigurationSnapshotProvider provider, String languageCode) {
        return resolve(provider, languageCode, SentenceModelLoader.openNlp());
    }

    /** Testable: inject the model loader so a valid/corrupt artifact is exercised without a real .bin. */
    static SessionSentenceSegmenter resolve(NlpConfigurationSnapshotProvider provider, String languageCode,
                                            SentenceModelLoader loader) {
        if (provider == null) {
            return regex(NlpConfigurationException.Reason.MODEL_NOT_CONFIGURED);
        }
        NlpConfigurationSnapshot snapshot;
        try {
            snapshot = provider.resolve(NlpCapability.SENTENCE_DETECTION, languageCode);
        } catch (NlpConfigurationException ex) {
            if (ex.allowsRegexFallback()) {
                return regex(ex.getReason());
            }
            // ARTIFACT_MISSING / CHECKSUM_MISMATCH: a selected model is broken/tampered — fail hard, no regex.
            throw new IllegalStateException("the selected NLP sentence model is unusable ("
                    + ex.getReason() + "): " + ex.getMessage(), ex);
        }
        NlpModelDescriptor descriptor = snapshot.getDescriptor();
        File artifact = new File(descriptor.getArtifactPath());
        // A present-but-corrupt model throws OpenNlpModelException here (hard) — never a silent regex.
        Optional<SentenceSegmentationPort> port = new OpenNlpModelResolver(
                new SingleArtifactOpenNlpModelCatalog(languageCode, artifact), loader)
                .openNlpSegmenterFor(languageCode);
        if (!port.isPresent()) {
            // The provider verified the artifact exists, so this is an unexpected race, not a "no model" state.
            throw new IllegalStateException("the resolved NLP sentence model artifact vanished for language '"
                    + languageCode + "'");
        }
        return new SessionSentenceSegmenter(port.get(),
                "OpenNLP(modelId=" + descriptor.getModelId() + ", version=" + descriptor.getVersion()
                        + ", artifact=" + artifact.getName() + ")");
    }

    private static SessionSentenceSegmenter regex(NlpConfigurationException.Reason reason) {
        return new SessionSentenceSegmenter(new RegexSentenceSegmenter(),
                "regex-fallback(reason=" + reason + ")");
    }
}
