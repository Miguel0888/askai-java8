package com.aresstack.askai.java8.groupchat;

/**
 * Immutable identity record for a single Partying participant.
 *
 * <p>A participant is identified by a stable {@link #getParticipantId() participantId} that is
 * persisted locally and does NOT change when the IP address changes. The display name and preferred
 * color are mutable profile attributes that can be updated in Settings.</p>
 */
public final class Participant {

    private final String participantId;
    private final String displayName;
    private final String preferredColor;

    public Participant(String participantId, String displayName, String preferredColor) {
        if (participantId == null || participantId.trim().isEmpty()) {
            throw new IllegalArgumentException("participantId must not be blank");
        }
        this.participantId = participantId;
        this.displayName = displayName != null ? displayName : participantId;
        this.preferredColor = preferredColor;
    }

    /** Stable locally-persisted identity; must not be an IP address. */
    public String getParticipantId() {
        return participantId;
    }

    /** Human-readable display name shown in the transcript and participant list. */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Preferred palette color token (e.g. {@code "violet"}).  May be {@code null} if no preference
     * has been set.  Actual color assignment is determined by the room's color-map, not this field
     * alone.
     */
    public String getPreferredColor() {
        return preferredColor;
    }

    @Override
    public String toString() {
        return "Participant{id=" + participantId + ", name=" + displayName + "}";
    }
}
