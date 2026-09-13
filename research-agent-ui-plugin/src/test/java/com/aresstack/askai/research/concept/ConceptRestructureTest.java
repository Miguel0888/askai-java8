package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.store.FileConceptStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Zielbild slice 2 — the bite-wise restructuring set: rename at any depth (never a deletion),
 * rewrite ONLY terminal branches (bottom-up rule), delete ONLY leaves/terminal branches (a
 * small model must never vaporise a deep subtree in one call).
 */
public class ConceptRestructureTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private ConceptBranchService seeded() throws Exception {
        ConceptBranchService service =
                new ConceptBranchService(new FileConceptStore(temp.newFolder("concept")));
        assertTrue(service.replaceDocument("{\"title\":\"\",\"subtitle\":\"\",\"concept\":["
                + "{\"Buch\":[{\"Setup\":[{\"Arduino\":[],\"ESP-IDF\":[]}],"
                + "\"Praxis\":[]}]}]}", 0).isApplied());
        return service;
    }

    @Test
    public void renameKeepsChildrenAndPositionAndGuardsAmbiguity() throws Exception {
        ConceptBranchService service = seeded();
        assertTrue(service.renameNode(Arrays.asList("Buch", "Setup"),
                "ESP32-Entwicklung").isApplied());
        String document = service.snapshot().getDocumentJson();
        assertTrue(document, document.contains("ESP32-Entwicklung"));
        assertTrue("children survive the rename", document.contains("Arduino"));
        assertTrue("order is structure — the renamed card stays FIRST, before Praxis",
                document.indexOf("ESP32-Entwicklung") < document.indexOf("Praxis"));

        assertFalse("a sibling with the target name makes addressing ambiguous",
                service.renameNode(Arrays.asList("Buch", "ESP32-Entwicklung"), "Praxis")
                        .isApplied());
        assertFalse("the surface itself is never renamed",
                service.renameNode(Collections.<String>emptyList(), "X").isApplied());
    }

    @Test
    public void rewriteReplacesATerminalBranchAndRefusesDeepStructure() throws Exception {
        ConceptBranchService service = seeded();
        // "Setup" is terminal (Arduino/ESP-IDF are leaves) — the designed cleanup spot.
        assertTrue(service.rewriteTerminalBranch(Arrays.asList("Buch", "Setup"),
                Arrays.asList("Arduino", "Debugging")).isApplied());
        String document = service.snapshot().getDocumentJson();
        assertTrue(document.contains("Debugging"));
        assertFalse("the omitted leaf left the STORED concept", document.contains("ESP-IDF"));

        // "Buch" has deeper structure — one-step rewrites are refused, work bottom-up.
        ConceptBranchService.EditResult deep = service.rewriteTerminalBranch(
                Collections.singletonList("Buch"), Collections.singletonList("X"));
        assertFalse(deep.isApplied());
        assertTrue(deep.getDiagnostic().describeForModel(),
                deep.getDiagnostic().describeForModel().contains("bottom-up"));
    }

    @Test
    public void deleteAcceptsLeavesAndTerminalBranchesOnly() throws Exception {
        ConceptBranchService service = seeded();
        // While Setup (a branch with children) exists, Buch is DEEP — refused.
        ConceptBranchService.EditResult refused =
                service.deleteTerminalBranch(Collections.singletonList("Buch"));
        assertFalse("deep structures are never deleted in one call", refused.isApplied());
        assertTrue(refused.getDiagnostic().describeForModel(),
                refused.getDiagnostic().describeForModel().contains("bottom-up"));

        assertTrue("a leaf is deletable",
                service.deleteTerminalBranch(
                        Arrays.asList("Buch", "Setup", "ESP-IDF")).isApplied());
        assertTrue("a terminal branch is deletable",
                service.deleteTerminalBranch(Arrays.asList("Buch", "Setup")).isApplied());
        // After the bottom-up work Buch only holds the Praxis leaf — NOW it is terminal.
        assertTrue("bottom-up made the former deep card deletable",
                service.deleteTerminalBranch(Collections.singletonList("Buch")).isApplied());
    }
}
