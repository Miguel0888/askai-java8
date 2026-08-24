package com.aresstack.askai.research.scope;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.embedding.UrlConnectionEmbeddingHttpTransport;
import com.aresstack.askai.research.store.ScopeAnchorVectorIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * The sweep's embedder, BOUND AT CONSTRUCTION to one immutable {@link EmbeddingEndpointDescriptor}
 * — this is how Z3b-3's snapshot invariant is actually guaranteed: the adapter underneath holds the
 * frozen descriptor, so a global embedding-model hot-reload between the 45-115s generation and the
 * embed batch CANNOT switch the vector world mid-run (there is nothing mutable to switch). Serves
 * both roles of one snapshot: the anchor index's {@link ScopeAnchorVectorIndex.AnchorEmbedder}
 * (persistent anchor vectors) and the sweep's {@link ScopeSweepService.SweepEmbedder} (transient
 * mission/probe/control vectors) — one world, by the same object.
 */
public final class EmbeddingSnapshotSweepEmbedder
        implements ScopeSweepService.SweepEmbedder, ScopeAnchorVectorIndex.AnchorEmbedder {

    private final EmbeddingEndpointDescriptor descriptor;
    private final HttpEmbeddingPortAdapter adapter;

    public EmbeddingSnapshotSweepEmbedder(EmbeddingEndpointDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        this.descriptor = descriptor;
        this.adapter = new HttpEmbeddingPortAdapter(descriptor,
                new UrlConnectionEmbeddingHttpTransport((int) descriptor.timeoutMillis));
    }

    @Override
    public String modelFingerprint() {
        return descriptor.embeddingFingerprint();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<float[]>();
        for (EmbeddingPort.EmbeddingVector vector : adapter.embed(texts)) {
            vectors.add(vector.getValues());
        }
        return vectors;
    }
}
