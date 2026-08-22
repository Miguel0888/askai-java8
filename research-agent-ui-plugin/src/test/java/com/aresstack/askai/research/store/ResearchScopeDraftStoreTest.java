package com.aresstack.askai.research.store;

import com.aresstack.askai.research.domain.scope.CoverageEmphasis;
import com.aresstack.askai.research.domain.scope.CrossCuttingEmphasis;
import com.aresstack.askai.research.domain.scope.ResearchDeliverable;
import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeFacet;
import com.aresstack.askai.research.domain.scope.SynthesisPolicy;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The scope the user worked out must survive a restart EXACTLY — and a draft this build cannot read must
 * BLOCK rather than silently come back empty, because an empty draft looks like "the user decided nothing".
 */
public class ResearchScopeDraftStoreTest {

    @Rule
    public TemporaryFolder projectDir = new TemporaryFolder();

    private static ResearchScopeDraft richDraft() {
        return ResearchScopeDraft.builder()
                .mission("Wie werden Validierungsstudien für Wearables konzipiert?")
                .domains(Arrays.asList("Wearables", "Medizintechnik"))
                .contexts(Arrays.asList("klinische Validierung"))
                .putFacet(new ScopeFacet("rings", "Smart Rings", ScopeFacet.Status.CONFIRMED, "Formfaktor"))
                .putFacet(new ScopeFacet("ar", "AR-Brillen", ScopeFacet.Status.EXCLUDED, "doch Nebensache"))
                .addExclusion("Kaufberatung")
                .perspectives(Arrays.asList("Patient", "Zulassungsbehörde"))
                .constraints(Arrays.asList("nur frei zugängliche Quellen"))
                .geographicScope("EU und USA")
                .temporalScope("letzte fünf Jahre")
                .addTerminology("HRV")
                .addUnresolvedIssue("Reichen Herstellerangaben?")
                .putCoverageEmphasis(new CoverageEmphasis("rings", CoverageEmphasis.Importance.HIGH,
                        CoverageEmphasis.ResearchDepth.EXHAUSTIVE, 40))
                .putCoverageEmphasis(new CoverageEmphasis("ar", CoverageEmphasis.Importance.LOW,
                        CoverageEmphasis.ResearchDepth.OVERVIEW))
                .putCrossCuttingEmphasis(new CrossCuttingEmphasis("Regulierung",
                        CoverageEmphasis.Importance.HIGH))
                .deliverable(new ResearchDeliverable(20, 30, ResearchDeliverable.LengthUnit.PAGES,
                        new SynthesisPolicy(true, true, SynthesisPolicy.RepetitiveEntityPolicy.GROUP,
                                SynthesisPolicy.ExamplePolicy.REPRESENTATIVE)))
                .build();
    }

    @Test
    public void everyDecisionSurvivesAWriteReadCycleUnchanged() throws Exception {
        FileResearchScopeDraftStore store = new FileResearchScopeDraftStore(projectDir.getRoot());
        assertEquals("nothing scoped yet is not an error",
                ScopeDraftLoadResult.Status.MISSING, store.load().getStatus());
        assertTrue(store.load().isUsableForScoping());
        assertTrue(store.load().draftOrEmpty().isEmpty());

        store.save(richDraft());
        ScopeDraftLoadResult loaded = store.load();
        assertEquals(ScopeDraftLoadResult.Status.LOADED, loaded.getStatus());
        ResearchScopeDraft draft = loaded.getDraft();

        assertEquals("Wie werden Validierungsstudien für Wearables konzipiert?", draft.getMission());
        assertEquals(Arrays.asList("Wearables", "Medizintechnik"), draft.getDomains());
        assertEquals("EU und USA", draft.getGeographicScope());
        assertEquals("letzte fünf Jahre", draft.getTemporalScope());
        assertEquals(Arrays.asList("Patient", "Zulassungsbehörde"), draft.getPerspectives());
        assertEquals(Arrays.asList("nur frei zugängliche Quellen"), draft.getConstraints());
        assertEquals(Arrays.asList("HRV"), draft.getTerminology());
        assertEquals(1, draft.getUnresolvedIssues().size());
        assertEquals("Reichen Herstellerangaben?",
                draft.getUnresolvedIssues().get(0).getDescription());
        assertEquals(com.aresstack.askai.research.domain.scope.UnresolvedScopeIssue.Significance.SIGNIFICANT,
                draft.getUnresolvedIssues().get(0).getSignificance());
        assertEquals(Arrays.asList("Kaufberatung"), draft.getExclusions());

        assertEquals("the excluded facet is persisted too, not dropped", 2, draft.getFacets().size());
        assertEquals(ScopeFacet.Status.EXCLUDED, draft.facet("ar").getStatus());
        assertEquals("doch Nebensache", draft.facet("ar").getRationale());

        CoverageEmphasis rings = draft.emphasisOf("rings");
        assertEquals(CoverageEmphasis.Importance.HIGH, rings.getImportance());
        assertEquals(CoverageEmphasis.ResearchDepth.EXHAUSTIVE, rings.getResearchDepth());
        assertEquals(40, rings.getOutputShareHint());
        assertFalse(draft.emphasisOf("ar").hasShareHint());
        assertEquals("Regulierung", draft.getCrossCuttingEmphasis().get(0).getDimension());

        assertEquals(20, draft.getDeliverable().getTargetLengthMin());
        assertEquals(ResearchDeliverable.LengthUnit.PAGES, draft.getDeliverable().getLengthUnit());
        assertTrue(draft.getDeliverable().getSynthesisPolicy().isCategoryFirst());
        assertTrue(draft.getDeliverable().getSynthesisPolicy().isContrastRequired());
    }

    @Test
    public void everySaveProducesTheNextRevisionAndTheStoreOwnsTheCounter() throws Exception {
        FileResearchScopeDraftStore store = new FileResearchScopeDraftStore(projectDir.getRoot());

        ResearchScopeDraft first = store.save(ResearchScopeDraft.builder().mission("erste Idee").build());
        assertEquals(1L, first.getRevision());
        ResearchScopeDraft second = store.save(first.toBuilder().mission("geschärfte Idee").build());
        assertEquals(2L, second.getRevision());
        assertEquals("the caller works on exactly what is on disk",
                2L, store.load().getDraft().getRevision());
        assertEquals("geschärfte Idee", store.load().getDraft().getMission());
    }

    @Test
    public void aDraftFromANewerSchemaIsRefusedInsteadOfBeingHalfRead() throws Exception {
        File file = new File(projectDir.getRoot(), "scope-draft.json");
        StoreIo.atomicWrite(file, "{\"schemaVersion\":999,\"mission\":\"aus der Zukunft\"}");

        ScopeDraftLoadResult result = new FileResearchScopeDraftStore(projectDir.getRoot()).load();
        assertEquals(ScopeDraftLoadResult.Status.UNSUPPORTED_SCHEMA, result.getStatus());
        assertFalse("a scope that cannot be read must block, never start empty",
                result.isUsableForScoping());
        assertNull(result.getDraft());
        assertTrue(result.getReason(), result.getReason().contains("999"));
    }

    @Test
    public void aCorruptDraftIsReportedWithARepairHintRatherThanSilentlyReplaced() throws Exception {
        File file = new File(projectDir.getRoot(), "scope-draft.json");
        StoreIo.atomicWrite(file, "{ this is not json");

        ScopeDraftLoadResult result = new FileResearchScopeDraftStore(projectDir.getRoot()).load();
        assertEquals(ScopeDraftLoadResult.Status.CORRUPT, result.getStatus());
        assertFalse(result.isUsableForScoping());
        assertTrue(result.getReason(), result.getReason().contains("scope-draft.json"));
    }

    @Test
    public void unknownFieldsAndVocabularyDoNotBreakADraftThisBuildCanStillRead() {
        // Forward tolerance for ADDITIONS (same schema version): unknown fields are ignored and an unknown
        // enum value falls back, so an extended vocabulary never costs the whole scope.
        ResearchScopeDraft draft = ResearchScopeDraftCodec.fromJson(
                "{\"schemaVersion\":1,\"revision\":7,\"mission\":\"m\",\"somethingNew\":{\"a\":1},"
                        + "\"facets\":[{\"facetId\":\"f1\",\"label\":\"L\",\"status\":\"SOMETHING_ELSE\","
                        + "\"rationale\":\"r\",\"extra\":true}]}");

        assertEquals(7L, draft.getRevision());
        assertEquals("m", draft.getMission());
        assertEquals(ScopeFacet.Status.PROVISIONAL, draft.facet("f1").getStatus());
        assertEquals("the standard synthesis contract applies when none was stored",
                SynthesisPolicy.RepetitiveEntityPolicy.GROUP,
                draft.getDeliverable().getSynthesisPolicy().getRepetitiveEntityPolicy());
    }

    @Test
    public void theProjectContextExposesTheScopeDraftStoreOfItsOwnDirectory() {
        ResearchProjectContext context =
                ResearchProjectContext.open("p1", projectDir.getRoot());
        assertEquals(new File(projectDir.getRoot(), "scope-draft.json"),
                context.getScopeDraftStore().getFile());
        assertEquals(ScopeDraftLoadResult.Status.MISSING,
                context.getScopeDraftStore().load().getStatus());
    }
}
