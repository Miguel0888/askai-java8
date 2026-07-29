package com.aresstack.askai.browser.search;

/**
 * The IMMUTABLE per-session settings snapshot (A2c). Global settings are only a TEMPLATE for new
 * sessions: at session start one snapshot is created, validated and persisted with the session; the
 * running session reads exclusively from it and NEVER switches behaviour because global settings
 * changed. Resuming a session reuses the stored snapshot exactly; an older schema is migrated
 * typed; an unreadable snapshot is a clear recovery/configuration error — never silently replaced
 * by current global defaults. Prompt texts and model profile ids are part of the snapshot, so a
 * later AI run stays reproducible.
 */
public final class SearchProcessingProfileSnapshot {

    public final int schemaVersion;
    public final String profileId;
    public final long profileRevision;
    public final long createdAtEpochMillis;
    public final String settingsDigest;
    public final LegacyBrowserSearchSettings settings;

    private SearchProcessingProfileSnapshot(int schemaVersion, String profileId, long profileRevision,
                                            long createdAtEpochMillis, String settingsDigest,
                                            LegacyBrowserSearchSettings settings) {
        this.schemaVersion = schemaVersion;
        this.profileId = profileId;
        this.profileRevision = profileRevision;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.settingsDigest = settingsDigest;
        this.settings = settings;
    }

    /** Freeze the given (already validated) settings into a new snapshot; the digest is computed here. */
    public static SearchProcessingProfileSnapshot create(String profileId, long profileRevision,
                                                         long createdAtEpochMillis,
                                                         LegacyBrowserSearchSettings settings) {
        return new SearchProcessingProfileSnapshot(
                LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION, profileId, profileRevision,
                createdAtEpochMillis, LegacyBrowserSearchSettingsCodec.digest(settings), settings);
    }

    public String toJson() {
        return new LegacyBrowserSearchConfigDocument(schemaVersion, profileRevision, settingsDigest,
                profileId, createdAtEpochMillis,
                LegacyBrowserSearchSettingsCodec.toValues(settings)).toJson();
    }

    /**
     * Restore a stored snapshot. HARD failure modes (all {@link IllegalArgumentException} with the
     * concrete reason): malformed JSON, malformed values, digest mismatch (corruption/tampering),
     * unknown newer schema. An OLDER schema goes through the typed migration switch below — schema 1
     * is current, so migration is currently the identity; every future schema bump adds its step
     * here instead of guessing.
     */
    public static SearchProcessingProfileSnapshot parse(String json) {
        LegacyBrowserSearchConfigDocument document = LegacyBrowserSearchConfigDocument.parse(json);
        LegacyBrowserSearchConfigDocument migrated = migrate(document);
        LegacyBrowserSearchSettingsCodec.Decoded decoded =
                LegacyBrowserSearchSettingsCodec.fromValues(migrated.values);
        if (!decoded.violations.isEmpty()) {
            throw new IllegalArgumentException("search profile snapshot has invalid values:\n"
                    + new SettingsValidationResult(decoded.violations).describe());
        }
        String digest = LegacyBrowserSearchSettingsCodec.digest(decoded.settings);
        if (document.schemaVersion == LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION
                && !migrated.settingsDigest.isEmpty() && !migrated.settingsDigest.equals(digest)) {
            throw new IllegalArgumentException("search profile snapshot digest mismatch: stored "
                    + migrated.settingsDigest + " but values hash to " + digest
                    + " — the snapshot is corrupt and will not be silently replaced");
        }
        return new SearchProcessingProfileSnapshot(
                LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION, migrated.profileId,
                migrated.settingsRevision, migrated.createdAtEpochMillis, digest, decoded.settings);
    }

    /** Typed schema migration: one explicit step per historical version, never a heuristic. */
    private static LegacyBrowserSearchConfigDocument migrate(
            LegacyBrowserSearchConfigDocument document) {
        switch (document.schemaVersion) {
            case LegacyBrowserSearchConfigDocument.CURRENT_SCHEMA_VERSION:
                return document;
            case 1:
            case 2:
                // v1 → v2 (A3 analysis fields) and v2 → v3 (A4 layout-repair ticket-cache settings):
                // every older value stays valid and the new keys take their central defaults during
                // decoding. The stored digest covered the older key set, so it is recomputed (digest
                // verification only applies to un-migrated current-version snapshots).
                return document;
            default:
                // parse() already rejects NEWER versions; an unknown older one has no migration path.
                throw new IllegalArgumentException("search profile snapshot schema "
                        + document.schemaVersion + " has no migration path");
        }
    }
}
