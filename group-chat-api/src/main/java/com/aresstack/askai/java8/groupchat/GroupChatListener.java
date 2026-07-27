package com.aresstack.askai.java8.groupchat;

import java.util.List;

/**
 * Callback interface for incoming group-chat events.
 *
 * <p>All methods are invoked on the transport thread; callers must dispatch to the EDT before
 * touching Swing components.</p>
 */
public interface GroupChatListener {

    /** A new message has arrived (or been replayed from history). */
    void onMessage(GroupChatMessage message);

    /** A participant has joined the room. */
    void onParticipantJoined(Participant participant);

    /** A participant has left the room. */
    void onParticipantLeft(Participant participant);

    /** The current set of participants has changed (e.g. after a reconnect). */
    void onParticipantsChanged(List<Participant> participants);

    /**
     * The transport status has changed.
     *
     * @param status a short human-readable description, e.g. {@code "3 party members"}
     */
    void onStatusChanged(String status);
}
