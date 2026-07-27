package com.aresstack.askai.java8.groupchat;

import java.util.List;

/**
 * Transport-layer abstraction for the Partying mode.
 *
 * <p>Implementations (in-memory for G1, JGroups for G2) must not leak their internal types
 * through this interface.</p>
 */
public interface GroupChatTransport {

    /**
     * Join the given room as the given participant.  The listener receives all subsequent events.
     * Calling join on an already-joined transport reconnects cleanly.
     */
    void join(GroupChatRoom room, Participant self, GroupChatListener listener);

    /** Send a message to all participants in the currently joined room. */
    void send(GroupChatMessage message);

    /** Leave the current room and release transport resources. */
    void leave();

    /** @return the current list of known participants, or an empty list if not joined. */
    List<Participant> getParticipants();

    /** @return {@code true} when joined and the transport considers itself connected. */
    boolean isConnected();
}
