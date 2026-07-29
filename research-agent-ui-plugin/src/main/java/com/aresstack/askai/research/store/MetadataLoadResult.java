package com.aresstack.askai.research.store;

/**
 * Typed outcome of loading the project metadata. Only {@link Status#MISSING} means "legitimate new
 * project" — every other non-LOADED status marks a DAMAGED project that must be blocked with a
 * repair hint instead of silently starting as an empty research assignment.
 */
public final class MetadataLoadResult {

    public enum Status { MISSING, LOADED, CORRUPT, UNSUPPORTED_SCHEMA, PROJECT_ID_MISMATCH }

    private final Status status;
    private final ResearchProjectMetadata metadata; // non-null only for LOADED
    private final String reason;

    private MetadataLoadResult(Status status, ResearchProjectMetadata metadata, String reason) {
        this.status = status;
        this.metadata = metadata;
        this.reason = reason == null ? "" : reason;
    }

    static MetadataLoadResult missing() {
        return new MetadataLoadResult(Status.MISSING, null, "");
    }

    static MetadataLoadResult loaded(ResearchProjectMetadata metadata) {
        return new MetadataLoadResult(Status.LOADED, metadata, "");
    }

    static MetadataLoadResult failed(Status status, String reason) {
        return new MetadataLoadResult(status, null, reason);
    }

    public Status getStatus() {
        return status;
    }

    /** The metadata for {@link Status#LOADED}; null otherwise. */
    public ResearchProjectMetadata getMetadata() {
        return metadata;
    }

    public String getReason() {
        return reason;
    }

    public boolean isUsableForStart() {
        return status == Status.MISSING || status == Status.LOADED;
    }
}
