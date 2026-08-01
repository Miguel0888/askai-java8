package com.aresstack.askai.research.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The scoping query field is the user's local draft: agent projections must never clobber a manual edit. */
public class ScopingQueryDraftTest {

    @Test
    public void anUntouchedFieldAdoptsTheBestSuggestion() {
        ScopingQueryDraft draft = new ScopingQueryDraft();
        assertTrue(draft.adoptFromProjectionIfUnowned("wearables audio video"));
        assertEquals("wearables audio video", draft.text());
        assertFalse(draft.isUserOwned());
    }

    @Test
    public void aManualEditIsNeverOverwrittenByALaterProjection() {
        ScopingQueryDraft draft = new ScopingQueryDraft();
        draft.adoptFromProjectionIfUnowned("wearables audio video");
        draft.userTyped("wearable camera privacy germany");

        assertFalse("a later projection must not touch the manual draft",
                draft.adoptFromProjectionIfUnowned("smart glasses 2026"));
        assertEquals("wearable camera privacy germany", draft.text());
    }

    @Test
    public void clickingASuggestionReplacesTheDraftLocallyAndTakesOwnership() {
        ScopingQueryDraft draft = new ScopingQueryDraft();
        draft.chooseSuggestion("smart glasses camera microphone applications");

        assertEquals("smart glasses camera microphone applications", draft.text());
        assertTrue(draft.isUserOwned());
        assertFalse("a projection can no longer prefill after a click",
                draft.adoptFromProjectionIfUnowned("something else"));
    }

    @Test
    public void clearingTheFieldReleasesOwnershipSoProjectionsMayPrefillAgain() {
        ScopingQueryDraft draft = new ScopingQueryDraft();
        draft.userTyped("manual");
        assertTrue(draft.isUserOwned());

        draft.userTyped("   ");
        assertFalse(draft.isUserOwned());
        assertTrue(draft.adoptFromProjectionIfUnowned("fresh suggestion"));
        assertEquals("fresh suggestion", draft.text());
    }
}
