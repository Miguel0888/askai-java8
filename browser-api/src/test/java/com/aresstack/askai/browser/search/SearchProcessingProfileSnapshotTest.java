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
                .replace("\"schemaVersion\": 2", "\"schemaVersion\": 0");
        try {
            SearchProcessingProfileSnapshot.parse(json);
            fail("expected missing migration path");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no migration path"));
        }
    }

    @Test
    public void v1SnapshotsMigrateExplicitlyToV2WithDefaultsForNewFields() {
        // A stored A2-era snapshot: schema 1, WITHOUT the A3 analysis keys and with a digest over
        // the v1 key set. It must migrate (not fail), keep its stored values and give the new
        // fields their central defaults.
        java.util.Map<String, String> v1Values = new java.util.LinkedHashMap<String, String>(
                LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create()));
        for (java.util.Iterator<String> it = v1Values.keySet().iterator(); it.hasNext();) {
            String key = it.next();
            if (key.startsWith("analysis.repeatedBlockWeight")
                    || key.startsWith("analysis.maximumCaptured")
                    || key.startsWith("analysis.maximumLinksPerContainer")
                    || key.startsWith("analysis.maximumContainerDomDepth")
                    || key.startsWith("analysis.maximumStructureSignatureDepth")) {
                it.remove();
            }
        }
        v1Values.put("captcha.challengeProbeIntervalMillis", "3000"); // a real v1 override
        String v1Json = new LegacyBrowserSearchConfigDocument(1, 5L, "v1-digest-not-verified",
                "old-session", 42L, v1Values).toJson();
        SearchProcessingProfileSnapshot migrated = SearchProcessingProfileSnapshot.parse(v1Json);
        assertEquals("old-session", migrated.profileId);
        assertEquals(3000, migrated.settings.captcha.challengeProbeIntervalMillis);
        assertEquals("new A3 fields take their central defaults", 1.2,
                migrated.settings.analysis.repeatedBlockWeight, 0.0001);
        assertEquals(2, migrated.schemaVersion);
    }
}
