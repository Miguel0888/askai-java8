package com.aresstack.askai.research.store;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The DERIVED vector projection of the fence posts — cache, never domain state. The canonical truth
 * is the {@link ResearchScopeDraft}'s anchors; this index only remembers which semantic text was
 * embedded with which model so unchanged posts are not embedded again. Deliberately dumb:
 * <pre>
 *   draft anchor present, textHash equal, modelFingerprint equal, dimension sane → reuse vector
 *   anything else                                                               → re-embed
 * </pre>
 * A missing or unreadable file is NOT an error: every vector is reproducible from the draft — the
 * index is rebuilt completely (loudly logged, never silently wrong).
 */
public final class ScopeAnchorVectorIndex {

    /** The embedding seam — batch in, vectors out, in input order. Z3 adapts the real EmbeddingPort. */
    public interface AnchorEmbedder {
        List<float[]> embed(List<String> semanticTexts);
    }

    private static final class Entry {
        final String semanticTextHash;
        final String modelFingerprint;
        final float[] vector;

        Entry(String semanticTextHash, String modelFingerprint, float[] vector) {
            this.semanticTextHash = semanticTextHash;
            this.modelFingerprint = modelFingerprint;
            this.vector = vector;
        }
    }

    private final File file;

    public ScopeAnchorVectorIndex(File file) {
        this.file = file;
    }

    /**
     * The evaluator-ready vectors for every anchor of the draft, in draft order — reusing cached
     * vectors where text and model are unchanged, re-embedding the rest and persisting the refreshed
     * index atomically. Anchors with an EMPTY semantic text are skipped (nothing to embed).
     */
    public synchronized List<ScopeFenceEvaluator.AnchorVector> vectorsFor(
            ResearchScopeDraft draft, String modelFingerprint, AnchorEmbedder embedder)
            throws IOException {
        Map<String, Entry> cached = load();
        List<ScopeAnchor> toEmbed = new ArrayList<ScopeAnchor>();
        for (ScopeAnchor anchor : draft.getAnchors()) {
            if (anchor.getSemanticText().isEmpty()) {
                continue;
            }
            Entry entry = cached.get(anchor.getAnchorId());
            boolean reusable = entry != null
                    && entry.semanticTextHash.equals(hash(anchor.getSemanticText()))
                    && entry.modelFingerprint.equals(modelFingerprint)
                    && entry.vector.length > 0;
            if (!reusable) {
                toEmbed.add(anchor);
            }
        }
        if (!toEmbed.isEmpty()) {
            List<String> texts = new ArrayList<String>();
            for (ScopeAnchor anchor : toEmbed) {
                texts.add(anchor.getSemanticText());
            }
            List<float[]> vectors = embedder.embed(texts);
            if (vectors == null || vectors.size() != texts.size()) {
                throw new IOException("embedder returned " + (vectors == null ? 0 : vectors.size())
                        + " vectors for " + texts.size() + " anchor texts");
            }
            for (int index = 0; index < toEmbed.size(); index++) {
                ScopeAnchor anchor = toEmbed.get(index);
                cached.put(anchor.getAnchorId(), new Entry(hash(anchor.getSemanticText()),
                        modelFingerprint, vectors.get(index)));
            }
        }
        // Prune orphans (anchors that left the draft) and persist the refreshed projection.
        Map<String, Entry> pruned = new LinkedHashMap<String, Entry>();
        List<ScopeFenceEvaluator.AnchorVector> result =
                new ArrayList<ScopeFenceEvaluator.AnchorVector>();
        for (ScopeAnchor anchor : draft.getAnchors()) {
            Entry entry = cached.get(anchor.getAnchorId());
            if (entry == null) {
                continue; // empty semantic text — nothing embeddable
            }
            pruned.put(anchor.getAnchorId(), entry);
            result.add(new ScopeFenceEvaluator.AnchorVector(
                    anchor.getAnchorId(), anchor.getMembership(), entry.vector));
        }
        save(pruned);
        return result;
    }

    private Map<String, Entry> load() {
        Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
        if (file == null || !file.isFile()) {
            return entries;
        }
        try {
            JsonObject document = JsonParser
                    .parseString(StoreIo.readUtf8(file)).getAsJsonObject();
            for (JsonElement element : document.getAsJsonArray("entries")) {
                JsonObject entry = element.getAsJsonObject();
                JsonArray values = entry.getAsJsonArray("vector");
                float[] vector = new float[values.size()];
                for (int index = 0; index < values.size(); index++) {
                    vector[index] = values.get(index).getAsFloat();
                }
                entries.put(entry.get("anchorId").getAsString(), new Entry(
                        entry.get("semanticTextHash").getAsString(),
                        entry.get("modelFingerprint").getAsString(), vector));
            }
        } catch (RuntimeException | IOException unreadable) {
            // The index is a rebuildable projection: an unreadable file means a full re-embed, loudly.
            System.err.println("[scope-anchors] vector index unreadable (" + file + "): "
                    + unreadable.getMessage() + " — rebuilding all anchor vectors");
            entries.clear();
        }
        return entries;
    }

    private void save(Map<String, Entry> entries) throws IOException {
        JsonObject document = new JsonObject();
        JsonArray array = new JsonArray();
        for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
            Entry entry = mapEntry.getValue();
            JsonObject item = new JsonObject();
            item.addProperty("anchorId", mapEntry.getKey());
            item.addProperty("semanticTextHash", entry.semanticTextHash);
            item.addProperty("modelFingerprint", entry.modelFingerprint);
            item.addProperty("dimension", entry.vector.length);
            JsonArray vector = new JsonArray();
            for (float value : entry.vector) {
                vector.add(value);
            }
            item.add("vector", vector);
            array.add(item);
        }
        document.add("entries", array);
        StoreIo.atomicWrite(file, document.toString());
    }

    /** SHA-256 of the semantic text — the staleness detector for MEANING changes. */
    static String hash(String semanticText) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(semanticText.getBytes(StoreIo.UTF8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                int value = b & 0xff;
                if (value < 0x10) {
                    hex.append('0');
                }
                hex.append(Integer.toHexString(value));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
