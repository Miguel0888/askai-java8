package com.aresstack.askai.research.state;

/**
 * Immutable domain event with no UI coupling. Carries at least an eventId, the sessionId, the revision the
 * event belongs to, a timestamp, and a type; {@code detail} is an optional human-neutral note.
 */
public final class ResearchEvent {

    private final String eventId;
    private final String sessionId;
    private final long revision;
    private final long timestamp;
    private final ResearchEventType type;
    private final String detail;

    public ResearchEvent(String eventId, String sessionId, long revision, long timestamp,
                         ResearchEventType type, String detail) {
        this.eventId = eventId;
        this.sessionId = sessionId;
        this.revision = revision;
        this.timestamp = timestamp;
        this.type = type;
        this.detail = detail == null ? "" : detail;
    }

    public String getEventId() {
        return eventId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getRevision() {
        return revision;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public ResearchEventType getType() {
        return type;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return type + "@" + revision + "(" + eventId + ")";
    }
}
