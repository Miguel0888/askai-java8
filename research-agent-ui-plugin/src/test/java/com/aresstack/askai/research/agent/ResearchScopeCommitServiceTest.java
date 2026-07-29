package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.store.MetadataLoadResult;
import com.aresstack.askai.research.store.ResearchProjectContext;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Commit 1b: the scope commit is FAIL-CLOSED. Every write result is judged; a failed metadata,
 * concept or outline write yields a typed failure and (in the session) neither auto-advance nor an
 * approval — while a fully successful commit persists metadata + both documents.
 */
public class ResearchScopeCommitServiceTest {

    private static ResearchProjectContext context(File dir) {
        return ResearchProjectContext.open("proj-1", dir);
    }

    private static ConfirmedResearchScope scope() {
        return new ConfirmedResearchScope("How does PF4J isolation work?",
                Arrays.asList("classloading"), "# Concept\n", "# Outline\n");
    }

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("askai-scope-commit").toFile();
    }

    @Test
    public void aFullySuccessfulCommitPersistsMetadataAndBothDocuments() throws Exception {
        File dir = tempDir();
        ResearchProjectContext context = context(dir);
        ResearchScopeCommitService.ScopeCommitResult result =
                new ResearchScopeCommitService(context).commit(scope());
        assertTrue(result.getDetail(), result.isSuccess());

        MetadataLoadResult metadata = context.getMetadataStore().load("proj-1");
        assertEquals(MetadataLoadResult.Status.LOADED, metadata.getStatus());
        assertEquals("How does PF4J isolation work?",
                metadata.getMetadata().getResearchQuestion());
        assertEquals("# Concept\n", context.getArtifactStore().read("concept").getMarkdown());
        assertEquals("# Outline\n", context.getArtifactStore().read("outline").getMarkdown());
    }

    @Test
    public void aRejectedConceptWriteStopsBeforeTheOutline() throws Exception {
        File dir = tempDir();
        ResearchProjectContext context = context(dir);
        // Sabotage: concept.md becomes a NON-EMPTY directory, so the atomic write must fail.
        File artifacts = new File(dir, "artifacts");
        assertTrue(artifacts.mkdirs());
        File conceptAsDir = new File(artifacts, "concept.md");
        assertTrue(conceptAsDir.mkdirs());
        assertTrue(new File(conceptAsDir, "blocker").createNewFile());

        ResearchScopeCommitService.ScopeCommitResult result =
                new ResearchScopeCommitService(context).commit(scope());
        assertFalse(result.isSuccess());
        assertEquals(ResearchScopeCommitService.Status.CONCEPT_FAILED, result.getStatus());
        assertEquals("the outline was never attempted after the concept failure",
                "", context.getArtifactStore().read("outline").getMarkdown());
        // The metadata write preceded the failure — the commit is fail-closed, not atomic (a
        // later project commit marker will harden crash consistency across files).
    }

    @Test
    public void aRejectedOutlineWriteIsItsOwnTypedFailure() throws Exception {
        File dir = tempDir();
        ResearchProjectContext context = context(dir);
        File artifacts = new File(dir, "artifacts");
        assertTrue(artifacts.mkdirs());
        File outlineAsDir = new File(artifacts, "outline.md");
        assertTrue(outlineAsDir.mkdirs());
        assertTrue(new File(outlineAsDir, "blocker").createNewFile());

        ResearchScopeCommitService.ScopeCommitResult result =
                new ResearchScopeCommitService(context).commit(scope());
        assertFalse(result.isSuccess());
        assertEquals(ResearchScopeCommitService.Status.OUTLINE_FAILED, result.getStatus());
    }

    @Test
    public void unwritableMetadataFailsTheCommitTyped() throws Exception {
        File dir = tempDir();
        ResearchProjectContext context = context(dir);
        File metadataAsDir = new File(dir, "project.properties");
        assertTrue(metadataAsDir.mkdirs());
        assertTrue(new File(metadataAsDir, "blocker").createNewFile());

        ResearchScopeCommitService.ScopeCommitResult result =
                new ResearchScopeCommitService(context).commit(scope());
        assertFalse(result.isSuccess());
        assertEquals(ResearchScopeCommitService.Status.METADATA_FAILED, result.getStatus());
    }

    @Test
    public void aReasonlessRejectionClassifiesAsRevisionConflict() {
        ResearchScopeCommitService.ScopeCommitResult conflict =
                ResearchScopeCommitService.classifyFailure("outline",
                        com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult
                                .conflict("someone else's text", 4L),
                        ResearchScopeCommitService.Status.OUTLINE_FAILED);
        assertEquals(ResearchScopeCommitService.Status.REVISION_CONFLICT, conflict.getStatus());
        ResearchScopeCommitService.ScopeCommitResult error =
                ResearchScopeCommitService.classifyFailure("outline",
                        com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult
                                .error("disk full"),
                        ResearchScopeCommitService.Status.OUTLINE_FAILED);
        assertEquals(ResearchScopeCommitService.Status.OUTLINE_FAILED, error.getStatus());
    }
}
