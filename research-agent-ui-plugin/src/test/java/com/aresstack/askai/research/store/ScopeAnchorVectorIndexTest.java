package com.aresstack.askai.research.store;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;
import com.aresstack.askai.research.domain.scope.ScopePatchOperations;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The vector index is a DUMB, rebuildable cache: unchanged posts are never embedded twice, a
 * MEANING change re-embeds exactly that post, a model change re-embeds everything, and a deleted or
 * corrupt index file merely triggers a full rebuild — the draft stays the only truth.
 */
public class ScopeAnchorVectorIndexTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Deterministic fake embedder that counts every embedded text. */
    private static final class CountingEmbedder implements ScopeAnchorVectorIndex.AnchorEmbedder {
        final List<String> embedded = new ArrayList<String>();

        public List<float[]> embed(List<String> semanticTexts) {
            List<float[]> vectors = new ArrayList<float[]>();
            for (String text : semanticTexts) {
                embedded.add(text);
                vectors.add(new float[]{text.length(), 1f});
            }
            return vectors;
        }
    }

    private static ResearchScopeDraft draft() {
        ResearchScopeDraft.Builder builder = ResearchScopeDraft.builder();
        ScopePatchOperations.addFacet("helme", "Sensorhelme", "").applyTo(builder);
        ScopePatchOperations.addFacet("gas", "Gasdetektion", "").applyTo(builder);
        ScopePatchOperations.excludeFacet("fitness", "").applyTo(builder);
        return builder.build();
    }

    @Test
    public void unchangedPostsAreEmbeddedExactlyOnce() throws Exception {
        File file = new File(folder.getRoot(), "scope-anchor-vectors.json");
        ScopeAnchorVectorIndex index = new ScopeAnchorVectorIndex(file);
        CountingEmbedder embedder = new CountingEmbedder();

        List<ScopeFenceEvaluator.AnchorVector> first =
                index.vectorsFor(draft(), "model-a", embedder);
        assertEquals("all three posts embedded once", 3, embedder.embedded.size());
        assertEquals(3, first.size());
        assertEquals(ScopeAnchor.Membership.OUT, first.get(2).membership);

        List<ScopeFenceEvaluator.AnchorVector> second =
                index.vectorsFor(draft(), "model-a", embedder);
        assertEquals("nothing changed — nothing re-embedded", 3, embedder.embedded.size());
        assertEquals(3, second.size());
    }

    @Test
    public void aMeaningChangeReembedsExactlyThatPost() throws Exception {
        File file = new File(folder.getRoot(), "vectors.json");
        ScopeAnchorVectorIndex index = new ScopeAnchorVectorIndex(file);
        CountingEmbedder embedder = new CountingEmbedder();
        index.vectorsFor(draft(), "model-a", embedder);
        embedder.embedded.clear();

        ResearchScopeDraft refined = draft();
        refined = refined.toBuilder()
                .putAnchor(refined.anchorOf("gas").withSemanticText(
                        "Wearables zur Gasdetektion im industriellen Arbeitsschutz"))
                .build();
        index.vectorsFor(refined, "model-a", embedder);

        assertEquals("only the refined post is stale", 1, embedder.embedded.size());
        assertTrue(embedder.embedded.get(0).contains("Gasdetektion im industriellen"));
    }

    @Test
    public void aMembershipChangeReembedsNothing() throws Exception {
        ScopeAnchorVectorIndex index =
                new ScopeAnchorVectorIndex(new File(folder.getRoot(), "vectors.json"));
        CountingEmbedder embedder = new CountingEmbedder();
        ResearchScopeDraft before = draft();
        index.vectorsFor(before, "model-a", embedder);
        embedder.embedded.clear();

        ResearchScopeDraft.Builder builder = before.toBuilder();
        ScopePatchOperations.confirmFacet("gas", "User: ja").applyTo(builder);
        List<ScopeFenceEvaluator.AnchorVector> vectors =
                index.vectorsFor(builder.build(), "model-a", embedder);

        assertEquals("the text (and vector) stayed valid — no re-embedding", 0,
                embedder.embedded.size());
        assertEquals(ScopeAnchor.Membership.IN, vectors.get(1).membership);
    }

    @Test
    public void aModelChangeRebuildsEverything() throws Exception {
        ScopeAnchorVectorIndex index =
                new ScopeAnchorVectorIndex(new File(folder.getRoot(), "vectors.json"));
        CountingEmbedder embedder = new CountingEmbedder();
        index.vectorsFor(draft(), "model-a", embedder);
        embedder.embedded.clear();

        index.vectorsFor(draft(), "model-b", embedder);
        assertEquals("a new embedding world re-embeds every post", 3, embedder.embedded.size());
    }

    @Test
    public void aCorruptOrMissingIndexIsJustAFullRebuild() throws Exception {
        File file = new File(folder.getRoot(), "vectors.json");
        ScopeAnchorVectorIndex index = new ScopeAnchorVectorIndex(file);
        CountingEmbedder embedder = new CountingEmbedder();
        index.vectorsFor(draft(), "model-a", embedder);
        embedder.embedded.clear();

        // Corrupt the derived file — the canonical draft is untouched, so this must simply rebuild.
        java.io.FileOutputStream out = new java.io.FileOutputStream(file);
        out.write("not json".getBytes("UTF-8"));
        out.close();
        List<ScopeFenceEvaluator.AnchorVector> vectors =
                index.vectorsFor(draft(), "model-a", embedder);

        assertEquals(3, embedder.embedded.size());
        assertEquals(3, vectors.size());
    }
}
