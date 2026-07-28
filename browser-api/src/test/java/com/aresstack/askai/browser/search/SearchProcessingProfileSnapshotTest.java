package com.aresstack.askai.browser.search;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The immutable per-session snapshot: lossless persist/restore with digest verification, typed
 * schema migration, and HARD failures for corrupt snapshots — a broken snapshot is never silently
 * replaced by current global defaults.
 */
public class SearchProcessingProfileSnapshotTest {

    @Test
    public void persistsAndRestoresLosslessly() {
        SearchProcessingProfileSnapshot snapshot = SearchProcessingProfileSnapshot.create(
                "session-42", 3L, 1_753_700_000_000L, LegacyBrowserSearchDefaults.create());
        SearchProcessingProfileSnapshot restored =
                SearchProcessingProfileSnapshot.parse(snapshot.toJson());
        assertEquals("session-42", restored.profileId);
        assertEquals(3L, restored.profileRevision);
        assertEquals(1_753_700_000_000L, restored.createdAtEpochMillis);
        assertEquals(snapshot.settingsDigest, restored.settingsDigest);
        assertEquals(snapshot.settings.captcha.challengeProbeIntervalMillis,
                restored.settings.captcha.challengeProbeIntervalMillis);
        // Prompt texts are part of the snapshot — a later AI run stays reproducible.
        assertEquals(snapshot.settings.aiLayoutResolver.userPromptTemplate,
                restored.settings.aiLayoutResolver.userPromptTemplate);
    }

    @Test
    public void corruptDigestFailsHardInsteadOfFallingBackToDefaults() {
        String json = SearchProcessingProfileSnapshot.create(
                        "s", 1L, 1L, LegacyBrowserSearchDefaults.create()).toJson()
                .replace("\"captcha.challengeProbeIntervalMillis\": \"1000\"",
                        "\"captcha.challengeProbeIntervalMillis\": \"9999\"");
        try {
            SearchProcessingProfileSnapshot.parse(json);
            fail("expected digest mismatch");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("digest mismatch"));
        }
    }

    @Test
    public void unknownOlderSchemaHasNoGuessedMigration() {
        String json = SearchProcessingProfileSnapshot.create(
                        "s", 1L, 1L, LegacyBrowserSearchDefaults.create()).toJson()
                .replace("\"schemaVersion\": 1", "\"schemaVersion\": 0");
        try {
            SearchProcessingProfileSnapshot.parse(json);
            fail("expected missing migration path");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no migration path"));
        }
    }
}
