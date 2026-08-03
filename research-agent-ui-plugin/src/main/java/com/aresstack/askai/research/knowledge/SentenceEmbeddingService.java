package com.aresstack.askai.research.knowledge;

import java.util.List;

/**
 * Embeds detected sentences for semantic passage building (§7). Reuses the centrally configured embedding
 * model — there is NO second research-specific model stack. Every result carries its {@link EmbeddingMetadata}
 * so vectors of different fingerprints/dimensions are never mixed.
 */
public interface SentenceEmbeddingService {

    List<EmbeddedSentence> embed(List<DetectedSentence> sentences);

    /** The space these embeddings live in (for provenance + idempotency fingerprints). */
    EmbeddingMetadata metadata();
}
