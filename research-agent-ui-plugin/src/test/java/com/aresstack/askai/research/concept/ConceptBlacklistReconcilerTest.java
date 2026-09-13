package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.store.FileConceptStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The gate's exact reconciliation pin: with PlatformIO gone and Debugging present as a leaf,
 * the summary is "1 gone, 1 leaf candidate(s)" — the live run once reported 2/2 because the
 * flat blacklist carried a facet's label AND id and both matched case-insensitively, doubling
 * every count and registering a duplicate candidate.
 */
public class ConceptBlacklistReconcilerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private ConceptBranchService seeded() throws Exception {
        ConceptBranchService service =
                new ConceptBranchService(new FileConceptStore(temp.newFolder("concept")));
        service.addNode(Collections.<String>emptyList(), "Grundlagen");
        service.addNode(Collections.singletonList("Grundlagen"), "Toolchain und Setup");
        service.addNode(Collections.singletonList("Grundlagen"), "Debugging");
        return service;
    }

    private final List<String> candidates = new ArrayList<String>();
    private final ConceptBlacklistReconciler.CandidateRegistrar registrar =
            new ConceptBlacklistReconciler.CandidateRegistrar() {
                public void candidate(String exclusionId, String label, String nodeId) {
                    candidates.add(exclusionId + ":" + label + ":" + nodeId);
                }
            };

    @Test
    public void oneGoneAndOnePresentLeafCountExactlyOnceEach() throws Exception {
        ConceptBranchService service = seeded();
        LinkedHashMap<String, String> exclusions = new LinkedHashMap<String, String>();
        exclusions.put("platformio", "PlatformIO"); // not in the concept anymore
        exclusions.put("debugging", "Debugging");   // present as a leaf
        assertEquals("1 gone, 1 leaf candidate(s), 0 branch(es), 0 ambiguous",
                ConceptBlacklistReconciler.reconcile(service, exclusions, registrar));
        assertEquals("exactly ONE candidate, never a duplicate", 1, candidates.size());
        assertTrue(candidates.get(0).startsWith("debugging:Debugging:"));
    }

    @Test
    public void idAndLabelOfOneExclusionNeverDoubleCountAndNodesDedupeAcrossIdentities() throws Exception {
        ConceptBranchService service = seeded();
        // The id "debugging" AND the label "Debugging" hit the SAME card — one identity, one
        // category; a second identity hitting the same node registers no second candidate.
        LinkedHashMap<String, String> exclusions = new LinkedHashMap<String, String>();
        exclusions.put("debugging", "Debugging");
        exclusions.put("Debugging", "Debugging"); // a not-deduped plain string, worst case
        String summary = ConceptBlacklistReconciler.reconcile(service, exclusions, registrar);
        assertTrue(summary, summary.startsWith("0 gone, 1 leaf candidate(s)"));
        assertEquals(1, candidates.size());
    }

    @Test
    public void branchesAndAmbiguousMatchesAreTheirOwnCategoriesAndNeverCandidates() throws Exception {
        ConceptBranchService service = seeded();
        service.addNode(java.util.Arrays.asList("Grundlagen", "Toolchain und Setup"), "IDE");
        // A second "Debugging" card elsewhere makes the term ambiguous.
        service.addNode(Collections.<String>emptyList(), "Praxis");
        service.addNode(Collections.singletonList("Praxis"), "Debugging");
        LinkedHashMap<String, String> exclusions = new LinkedHashMap<String, String>();
        exclusions.put("toolchain-und-setup", "Toolchain und Setup"); // a BRANCH now
        exclusions.put("debugging", "Debugging");                     // ambiguous now
        assertEquals("0 gone, 0 leaf candidate(s), 1 branch(es), 1 ambiguous",
                ConceptBlacklistReconciler.reconcile(service, exclusions, registrar));
        assertTrue(candidates.isEmpty());
    }
}
