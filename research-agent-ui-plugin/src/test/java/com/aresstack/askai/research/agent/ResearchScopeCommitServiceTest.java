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
 * The scope commit is FAIL-CLOSED and — since issue #32 — writes the TYPED METADATA ONLY: the ResearchBrief
 * is the canonical scoping artifact, so the commit produces no concept (and since C5 no outline) Markdown
 * document anymore. Legacy artifact files, however broken, can no longer fail the commit.
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
    public void aSuccessfulCommitPersistsTheMetadataAndWritesNoLegacyArtifacts() throws Exception {
        File dir = tempDir();
        ResearchProjectContext context = context(dir);
        ResearchScopeCommitService.ScopeCommitResult result =
                new ResearchScopeCommitService(context).commit(scope());
        assertTrue(result.getDetail(), result.isSuccess());

        MetadataLoadResult metadata = context.getMetadataStore().load("proj-1");
        assertEquals(MetadataLoadResult.Status.LOADED, metadata.getStatus());
        assertEquals("How does PF4J isolation work?",
                metadata.getMetadata().getResearchQuestion());
        // Issue #32: NO concept document beside the brief; C5: the outline slot is the corpus projection.
        assertEquals("", context.getArtifactStore().read("concept").getMarkdown());
        assertEquals("", context.getArtifactStore().read("outline").getMarkdown());
        assertFalse("no concept.md is created anymore",
                new File(new File(dir, "artifacts"), "concept.md").exists());
    }

    @Test
    public void aLegacyConceptFileIsLeftUntouchedAndCannotFailTheCommit() throws Exception {
        File dir = tempDir();
        ResearchProjectContext context = context(dir);
        // A legacy project: concept.md exists (even as an unwritable sabotage shape). Issue #32: the
        // commit neither reads, rewrites nor deletes it — it can no longer fail anything.
        File artifacts = new File(dir, "artifacts");
        assertTrue(artifacts.mkdirs());
        File conceptAsDir = new File(artifacts, "concept.md");
        assertTrue(conceptAsDir.mkdirs());
        assertTrue(new File(conceptAsDir, "blocker").createNewFile());

        ResearchScopeCommitService.ScopeCommitResult result =
                new ResearchScopeCommitService(context).commit(scope());
        assertTrue(result.getDetail(), result.isSuccess());
        assertTrue("the legacy file shape is preserved verbatim",
                new File(conceptAsDir, "blocker").exists());
    }

    @Test
    public void anUnwritableOutlineSlotDoesNotAffectTheScopeCommit() throws Exception {
        File dir = tempDir();
        ResearchProjectContext context = context(dir);
        File artifacts = new File(dir, "artifacts");
        assertTrue(artifacts.mkdirs());
        File outlineAsDir = new File(artifacts, "outline.md");
        assertTrue(outlineAsDir.mkdirs());
        assertTrue(new File(outlineAsDir, "blocker").createNewFile());

        // C5: scoping never touches the outline slot - even a sabotaged outline.md cannot fail it.
        ResearchScopeCommitService.ScopeCommitResult result =
                new ResearchScopeCommitService(context).commit(scope());
        assertTrue(result.getDetail(), result.isSuccess());
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
}
