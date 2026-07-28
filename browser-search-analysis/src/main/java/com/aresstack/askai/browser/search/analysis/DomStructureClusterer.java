package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.DomStructureSignature;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups sibling containers into clusters of the SAME simplified block shape. Two signatures count
 * as the same shape when their tag-token similarity (Dice coefficient) reaches the configured
 * {@code resultBlockSimilarityThreshold} — small per-block differences (an optional date line, a
 * missing sitelink row) must not break the repetition signal.
 */
public final class DomStructureClusterer {

    private final double similarityThreshold;

    public DomStructureClusterer(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    /** Clusters in input order; each cluster keeps its members in sibling order. */
    public List<List<RenderedContainerDescriptor>> cluster(
            List<RenderedContainerDescriptor> siblings) {
        List<List<RenderedContainerDescriptor>> clusters =
                new ArrayList<List<RenderedContainerDescriptor>>();
        List<DomStructureSignature> representatives = new ArrayList<DomStructureSignature>();
        for (RenderedContainerDescriptor sibling : siblings) {
            int match = -1;
            for (int i = 0; i < representatives.size(); i++) {
                if (similarity(representatives.get(i), sibling.structureSignature)
                        >= similarityThreshold) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                clusters.add(new ArrayList<RenderedContainerDescriptor>());
                representatives.add(sibling.structureSignature);
                match = clusters.size() - 1;
            }
            clusters.get(match).add(sibling);
        }
        return clusters;
    }

    /** Dice coefficient over tag-token multisets (0..1); identical signatures are 1. */
    static double similarity(DomStructureSignature a, DomStructureSignature b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        Map<String, Integer> tokensA = tokens(a.value);
        Map<String, Integer> tokensB = tokens(b.value);
        int intersection = 0;
        int totalA = 0;
        int totalB = 0;
        for (Map.Entry<String, Integer> entry : tokensA.entrySet()) {
            totalA += entry.getValue();
            Integer other = tokensB.get(entry.getKey());
            if (other != null) {
                intersection += Math.min(entry.getValue(), other);
            }
        }
        for (int count : tokensB.values()) {
            totalB += count;
        }
        return totalA + totalB == 0 ? 0 : (2.0 * intersection) / (totalA + totalB);
    }

    private static Map<String, Integer> tokens(String signature) {
        Map<String, Integer> tokens = new HashMap<String, Integer>();
        for (String token : signature.split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                Integer count = tokens.get(token);
                tokens.put(token, count == null ? 1 : count + 1);
            }
        }
        return tokens;
    }
}
