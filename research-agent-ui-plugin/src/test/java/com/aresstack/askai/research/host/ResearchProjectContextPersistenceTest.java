package com.aresstack.askai.research.host;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.state.oo.ResearchStateMemento;
import com.aresstack.askai.research.store.ResearchProjectContext;
import com.aresstack.askai.research.store.ResearchProjectMetadata;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Commit 1 of the guided artifact flow: ONE persistent project context is the source of truth.
 * Documents, sources, the research assignment and the state survive a session restart
 * consistently; transitions are persisted BEFORE they become observable; two projects never share
 * state; and the productive factory contains no in-memory artifact store.
 */
public class ResearchProjectContextPersistenceTest {

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("askai-research-persistence").toFile();
    }

    private static ProductiveResearchSessionResources resources(ResearchProjectContext context) {
        // Endpoints/backends are irrelevant for the persistence contract; the tool refresh runs
        // on a background executor and isolates failures, so nulls are safe here.
        return new ProductiveResearchSessionResources(context.getProjectId(),
                new OoResearchStateMachine(context.getProjectId()), null,
                context.getSourceRepository(), null, context, null, null, null, null, null);
    }

    @Test
    public void everythingSurvivesARestartConsistently() throws Exception {
        File projectDir = tempDir();

        // ---- session 1: assignment, documents, state, source ----
        ResearchProjectContext context = ResearchProjectContext.open("proj-1", projectDir);
        context.getMetadataStore().save(new ResearchProjectMetadata(
                ResearchProjectMetadata.SCHEMA_VERSION, "proj-1",
                "How does PF4J plugin isolation work?",
                Arrays.asList("classloading", "extension points"), 1L));
        context.getArtifactStore().replace("concept", 0L, "# Concept\n\n> PF4J isolation\n");
        context.getArtifactStore().replace("outline", 0L, "# Outline — PF4J\n1. Background\n");
        context.getFileSourceRepository().put(ResearchSourceRecord.builder("source-1")
                .title("PF4J docs").url("https://pf4j.org/doc").origin("pf4j.org").build());

        ProductiveResearchSessionResources first = resources(context);
        assertTrue(first.dispatch(ResearchCommandType.START).isAccepted());
        assertTrue(first.dispatch(ResearchCommandType.SUBMIT_SCOPE).isAccepted());
        assertTrue(first.dispatch(ResearchCommandType.PROPOSE_OUTLINE).isAccepted());
        assertEquals(ResearchStateIds.WAITING_APPROVAL, first.currentState().getStateId());
        String pendingApproval = first.currentState().getPendingApprovalId();
        assertNotNull("the approval gate must carry its id", pendingApproval);

        // ---- session 2 over the SAME project directory (fresh context = restart) ----
        ResearchProjectContext restoredContext = ResearchProjectContext.open("proj-1", projectDir);
        ProductiveResearchSessionResources restored = resources(restoredContext);

        ResearchProjectMetadata metadata = restoredContext.getMetadataStore().load();
        assertEquals("How does PF4J plugin isolation work?", metadata.getResearchQuestion());
        assertEquals(Arrays.asList("classloading", "extension points"),
                metadata.getConfirmedFocusAreas());

        assertEquals("# Outline — PF4J\n1. Background\n",
                restoredContext.getArtifactStore().read("outline").getMarkdown());
        assertTrue(restoredContext.getArtifactStore().read("outline").getRevision() > 0);
        assertEquals("# Concept\n\n> PF4J isolation\n",
                restoredContext.getArtifactStore().read("concept").getMarkdown());
        assertEquals(1, restoredContext.getSourceRepository().find(SourceQuery.all()).size());

        ResearchStateMemento state = restored.currentState();
        assertEquals(ResearchStateIds.OUTLINE, state.getPhaseId());
        assertEquals(ResearchStateIds.WAITING_APPROVAL, state.getStateId());
        assertEquals("the SAME pending approval survives the restart",
                pendingApproval, state.getPendingApprovalId());

        // ---- continuation from the restored state — no repeated scoping/outline ceremony ----
        assertTrue(restored.dispatch(ResearchCommandType.APPROVE_OUTLINE).isAccepted());
        assertEquals(ResearchStateIds.RESEARCH, restored.currentState().getPhaseId());
        assertEquals(ResearchStateIds.WAITING, restored.currentState().getStateId());
        assertTrue(restored.dispatch(ResearchCommandType.START_RESEARCH).isAccepted());
        assertEquals(ResearchStateIds.RUNNING, restored.currentState().getStateId());
    }

    @Test
    public void aFreshProjectStartsEmptyAndTwoProjectsNeverShareData() throws Exception {
        File dirA = tempDir();
        File dirB = tempDir();
        ResearchProjectContext a = ResearchProjectContext.open("proj-a", dirA);
        ResearchProjectContext b = ResearchProjectContext.open("proj-b", dirB);
        a.getArtifactStore().replace("outline", 0L, "A outline");
        a.getMetadataStore().save(new ResearchProjectMetadata(1, "proj-a", "question A",
                Arrays.<String>asList(), 1L));
        b.getArtifactStore().replace("outline", 0L, "B outline");

        // Restore A: never B's data. Restore a brand-new project: empty everything, SCOPING/new.
        ResearchProjectContext restoredA = ResearchProjectContext.open("proj-a", dirA);
        assertEquals("A outline", restoredA.getArtifactStore().read("outline").getMarkdown());
        assertEquals("question A", restoredA.getMetadataStore().load().getResearchQuestion());

        ResearchProjectContext fresh = ResearchProjectContext.open("proj-new", tempDir());
        assertEquals("", fresh.getArtifactStore().read("outline").getMarkdown());
        assertTrue(fresh.getSourceRepository().find(SourceQuery.all()).isEmpty());
        assertEquals(null, fresh.getMetadataStore().load());
        ProductiveResearchSessionResources freshResources = resources(fresh);
        assertEquals(ResearchStateIds.SCOPING, freshResources.currentState().getPhaseId());
        assertEquals(ResearchStateIds.NEW, freshResources.currentState().getStateId());
    }

    @Test
    public void aTransitionThatCannotBePersistedNeverBecomesObservable() throws Exception {
        File projectDir = tempDir();
        ResearchProjectContext context = ResearchProjectContext.open("proj-x", projectDir);
        ProductiveResearchSessionResources resources = resources(context);
        ResearchStateMemento before = resources.currentState();

        // Sabotage the state store: replace the state FILE by a NON-EMPTY directory of the same
        // name (an empty one could still be replaced by the atomic move), so the write must fail.
        File stateFile = new File(new File(projectDir, "state"), "research-session.json");
        assertTrue(stateFile.delete());
        assertTrue(stateFile.mkdirs());
        assertTrue(new File(stateFile, "blocker").createNewFile());

        com.aresstack.askai.research.state.oo.ResearchStateTransitionResult result =
                resources.dispatch(ResearchCommandType.START);
        assertFalse("no success result without persistence", result.isAccepted());
        assertTrue(result.getRejectionReason().contains("not persisted"));
        assertEquals("the previous state stays active",
                before.getStateId(), resources.currentState().getStateId());
        assertEquals(before.getRevision(), resources.currentState().getRevision());
    }

    @Test
    public void productiveFactoryUsesNoInMemoryArtifactStore() throws Exception {
        // Architecture guard: the productive path builds on the persistent project context; the
        // in-memory ResearchArtifactStore may only exist in the explicit demo mode.
        File source = new File("src/main/java/com/aresstack/askai/research/host/"
                + "ProductiveResearchBackendFactory.java");
        assertTrue("factory source not found from module dir", source.isFile());
        String code = new String(Files.readAllBytes(source.toPath()), "UTF-8");
        assertFalse("the productive factory must not touch ResearchArtifactStore",
                code.contains("ResearchArtifactStore"));
        assertTrue("the productive factory must build the persistent project context",
                code.contains("ResearchProjectContext.open"));
    }
}
