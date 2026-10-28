package com.aresstack.askai.research.concept;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The deterministic blacklist↔concept reconciliation after a transient boundary (V3 §8):
 * every EXCLUSION IDENTITY is classified against the new head into exactly ONE category —
 * gone / leaf candidate / branch / ambiguous. Extracted from the session so the gate's exact
 * pin ("PlatformIO absent + Debugging leaf → 1 gone, 1 leaf candidate") is unit-testable.
 *
 * <p>The live-gate counting bug this replaces: the session iterated the FLAT blacklist term
 * list, which carries a facet's label AND its id ("Debugging" and "debugging") — both matched
 * the same card case-insensitively, doubling every count and REGISTERING a duplicate
 * candidate. Here an exclusion is one identity (id + label match the same entry), and a node
 * becomes a candidate at most once per run regardless of how many identities hit it.</p>
 */
public final class ConceptBlacklistReconciler {

    /** Receives each unique leaf candidate exactly once per run. */
    public interface CandidateRegistrar {
        void candidate(String exclusionId, String label, String nodeId);
    }

    private ConceptBlacklistReconciler() {
    }

    /**
     * Classify {@code exclusionIdToLabel} (one entry per exclusion identity) against the
     * service's current head and register unique leaf candidates.
     *
     * @return the summary line body, e.g. {@code "1 gone, 1 leaf candidate(s), 0 branch(es),
     *         0 ambiguous"}
     */
    public static String reconcile(ConceptBranchService service,
                                   LinkedHashMap<String, String> exclusionIdToLabel,
                                   CandidateRegistrar registrar) {
        String document = service.snapshot().getDocumentJson();
        List<List<String>> cardPaths = ConceptTopicScanner.collectCardPaths(document);
        int gone = 0;
        int leafCandidates = 0;
        int branches = 0;
        int ambiguous = 0;
        LinkedHashSet<String> candidateNodeIds = new LinkedHashSet<String>();
        for (Map.Entry<String, String> exclusion : exclusionIdToLabel.entrySet()) {
            List<List<String>> matches = new java.util.ArrayList<List<String>>();
            for (List<String> path : cardPaths) {
                String card = path.get(path.size() - 1).trim();
                if (card.equalsIgnoreCase(safe(exclusion.getKey()))
                        || card.equalsIgnoreCase(safe(exclusion.getValue()))) {
                    matches.add(path);
                }
            }
            if (matches.isEmpty()) {
                gone++;
            } else if (matches.size() > 1) {
                ambiguous++;
            } else if (service.isLeafAt(matches.get(0))) {
                String nodeId = service.nodeIdAtPath(matches.get(0));
                if (nodeId != null && candidateNodeIds.add(nodeId)) {
                    registrar.candidate(exclusion.getKey(), exclusion.getValue(), nodeId);
                    leafCandidates++;
                } else if (nodeId != null) {
                    // A second identity hit the same node — one candidate per node, ever.
                    // The exclusion is not "gone"; it simply merges into the existing one.
                }
            } else {
                branches++;
            }
        }
        return gone + " gone, " + leafCandidates + " leaf candidate(s), " + branches
                + " branch(es), " + ambiguous + " ambiguous";
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
