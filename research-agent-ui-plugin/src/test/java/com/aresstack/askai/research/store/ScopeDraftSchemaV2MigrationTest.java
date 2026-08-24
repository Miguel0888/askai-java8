package com.aresstack.askai.research.store;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeAnchor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Schema v2 adds the fence ANCHORS to the canonical scope state. A v1 document migrates
 * deterministically on load — every facet yields exactly one anchor (stable id from the facet id,
 * the label as semantic text, the membership from the status), NO AI involved. A v2 roundtrip keeps
 * a richer semantic text, and a document from a NEWER schema still blocks instead of half-reading.
 */
public class ScopeDraftSchemaV2MigrationTest {

    @Test
    public void aV1DocumentMigratesItsFacetsIntoAnchorsDeterministically() {
        String v1 = "{\"schemaVersion\":1,\"revision\":7,\"mission\":\"Wearables am Arbeitsplatz\","
                + "\"facets\":["
                + "{\"facetId\":\"helme\",\"label\":\"Sensorhelme\",\"status\":\"CONFIRMED\","
                + "\"rationale\":\"User: ja\"},"
                + "{\"facetId\":\"fitness\",\"label\":\"Consumer Fitness Tracking\","
                + "\"status\":\"EXCLUDED\",\"rationale\":\"User will das nicht\"},"
                + "{\"facetId\":\"exo\",\"label\":\"Exoskelette\",\"status\":\"PROVISIONAL\","
                + "\"rationale\":\"\"}]}";

        ResearchScopeDraft draft = ResearchScopeDraftCodec.fromJson(v1);

        assertEquals(3, draft.getAnchors().size());
        ScopeAnchor helme = draft.anchorOf("helme");
        assertEquals("anchor-helme", helme.getAnchorId());
        assertEquals("the label becomes the semantic text — the rationale NEVER does",
                "Sensorhelme", helme.getSemanticText());
        assertEquals(ScopeAnchor.Membership.IN, helme.getMembership());
        assertEquals(ScopeAnchor.Membership.OUT, draft.anchorOf("fitness").getMembership());
        assertEquals(ScopeAnchor.Membership.PROVISIONAL, draft.anchorOf("exo").getMembership());
        assertEquals("the revision travels unchanged", 7L, draft.getRevision());
    }

    @Test
    public void aV2RoundtripKeepsARicherSemanticText() {
        ResearchScopeDraft.Builder builder = ResearchScopeDraft.builder().revision(3);
        com.aresstack.askai.research.domain.scope.ScopePatchOperations
                .addFacet("exo", "Exoskelette", "").applyTo(builder);
        ResearchScopeDraft original = builder.build();
        original = original.toBuilder()
                .putAnchor(original.anchorOf("exo").withSemanticText(
                        "industrielle Exoskelette zur ergonomischen Entlastung"))
                .build();

        ResearchScopeDraft reloaded = ResearchScopeDraftCodec.fromJson(
                ResearchScopeDraftCodec.toJson(original));

        assertEquals("industrielle Exoskelette zur ergonomischen Entlastung",
                reloaded.anchorOf("exo").getSemanticText());
        assertEquals(ScopeAnchor.Membership.PROVISIONAL, reloaded.anchorOf("exo").getMembership());
    }

    @Test
    public void aNewerSchemaStillBlocksInsteadOfHalfReading() {
        try {
            ResearchScopeDraftCodec.fromJson("{\"schemaVersion\":3,\"revision\":1}");
            throw new AssertionError("a v3 document must block on this build");
        } catch (ResearchScopeDraftCodec.UnsupportedSchemaException expected) {
            assertTrue(expected.getMessage().contains("newer"));
            assertEquals(3, expected.getDocumentVersion());
        }
    }
}
