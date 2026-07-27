package com.aresstack.askai.java8.groupchat;

/**
 * Immutable identity record for a single Partying participant.
 *
 * <p>A participant is identified by a stable {@link #getParticipantId() participantId} that is
 * persisted locally and does NOT change when the IP address changes. The display name and preferred
 * color are mutable profile attributes that can be updated in Settings.</p>
 *
 * <p>The {@link #getMentionHandle() mentionHandle} is the compact, space-free token used after
 * {@code @} in messages (e.g. {@code @AliceSmith} for display name "Alice Smith"). It is unique
 * within a room and is derived at join time using
 * {@link MentionParser#computeUniqueHandle}.</p>
 */
public final class Participant {

    private final String participantId;
    private final String displayName;
    private final String mentionHandle;
    private final String preferredColor;

    /**
     * Construct a participant with an explicit mention handle.
     *
     * @param participantId  stable UUID-based identity (must not be blank)
     * @param displayName    human-readable display name; falls back to {@code participantId} if null
     * @param mentionHandle  unique, space-free @-mention token; falls back to a derived token if null
     * @param preferredColor preferred palette color token, or {@code null}
     */
    public Participant(String participantId, String displayName, String mentionHandle, String preferredColor) {
        if (participantId == null || participantId.trim().isEmpty()) {
            throw new IllegalArgumentException("participantId must not be blank");
        }
        this.participantId = participantId;
        this.displayName = displayName != null ? displayName : participantId;
        String derived = (displayName != null ? displayName : participantId)
                .replaceAll("[^A-Za-z0-9_]", "");
        if (derived.isEmpty()) {
            derived = "User";
        }
        this.mentionHandle = (mentionHandle != null && !mentionHandle.trim().isEmpty())
                ? mentionHandle
                : derived;
        this.preferredColor = preferredColor;
    }

    /**
     * Construct a participant without an explicit mention handle; the handle is derived from the
     * display name by stripping non-alphanumeric characters.  Use
     * {@link MentionParser#computeUniqueHandle} to guarantee uniqueness across a room.
     */
    public Participant(String participantId, String displayName, String preferredColor) {
        this(participantId, displayName, null, preferredColor);
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
     * Compact, space-free token used after {@code @} in messages.  Always non-null and non-empty.
     * Example: display name "Alice Smith" → handle {@code "AliceSmith"}.
     */
    public String getMentionHandle() {
        return mentionHandle;
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
        return "Participant{id=" + participantId + ", name=" + displayName + ", handle=" + mentionHandle + "}";
    }
}

