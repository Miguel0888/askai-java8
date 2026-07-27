package com.aresstack.askai.java8.groupchat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process, in-memory {@link GroupChatTransport} used for the G1 foundational slice.
 *
 * <p>Multiple instances can share a static "cluster" map keyed by room ID, allowing two
 * {@code InMemoryGroupChatTransport} instances in the same JVM to communicate.  This is useful for
 * unit tests and manual smoke-testing; the real LAN transport ships with G2.</p>
 *
 * <p>Thread safety: all state mutations are synchronized on the instance; listener callbacks are
 * dispatched on the calling thread.</p>
 */
public final class InMemoryGroupChatTransport implements GroupChatTransport {

    /**
     * Shared room registries, keyed by room ID.  All transports that join the same room ID share
     * the same {@link RoomBus} so messages are delivered in-process.
     */
    private static final Map<String, RoomBus> ROOMS =
            Collections.synchronizedMap(new LinkedHashMap<String, RoomBus>());

    private GroupChatRoom currentRoom;
    private Participant self;
    private GroupChatListener listener;
    private boolean connected;

    @Override
    public synchronized void join(GroupChatRoom room, Participant self, GroupChatListener listener) {
        if (connected) {
            leave();
        }
        this.currentRoom = room;
        this.self = self;
        this.listener = listener;
        this.connected = true;

        RoomBus bus = getOrCreateBus(room.getRoomId());
        bus.addTransport(this);

        // Notify all other participants that this peer joined.
        bus.broadcastJoin(self, this);

        // Tell the joining client how many members are now in the room.
        int count = bus.participantCount();
        String status = count == 1 ? "1 party member" : count + " party members";
        listener.onStatusChanged(status);
        listener.onParticipantsChanged(bus.participants());
    }

    @Override
    public synchronized void send(GroupChatMessage message) {
        if (!connected || currentRoom == null) {
            return;
        }
        RoomBus bus = ROOMS.get(currentRoom.getRoomId());
        if (bus != null) {
            bus.broadcast(message);
        }
    }

    @Override
    public synchronized void leave() {
        if (!connected) {
            return;
        }
        RoomBus bus = ROOMS.get(currentRoom.getRoomId());
        if (bus != null) {
            bus.removeTransport(this);
            bus.broadcastLeave(self, this);
        }
        connected = false;
        currentRoom = null;
        self = null;
        listener = null;
    }

    @Override
    public synchronized List<Participant> getParticipants() {
        if (!connected || currentRoom == null) {
            return Collections.emptyList();
        }
        RoomBus bus = ROOMS.get(currentRoom.getRoomId());
        return bus != null ? bus.participants() : Collections.<Participant>emptyList();
    }

    @Override
    public synchronized boolean isConnected() {
        return connected;
    }

    Participant getSelf() {
        return self;
    }

    GroupChatListener getListener() {
        return listener;
    }

    // ------------------------------------------------------------------

    private static RoomBus getOrCreateBus(String roomId) {
        synchronized (ROOMS) {
            RoomBus bus = ROOMS.get(roomId);
            if (bus == null) {
                bus = new RoomBus(roomId);
                ROOMS.put(roomId, bus);
            }
            return bus;
        }
    }

    /** Visible for tests: remove the shared room bus for a given roomId. */
    public static void clearRoom(String roomId) {
        ROOMS.remove(roomId);
    }

    // ------------------------------------------------------------------

    private static final class RoomBus {
        private final String roomId;
        private final List<InMemoryGroupChatTransport> transports =
                new CopyOnWriteArrayList<InMemoryGroupChatTransport>();

        RoomBus(String roomId) {
            this.roomId = roomId;
        }

        void addTransport(InMemoryGroupChatTransport t) {
            transports.add(t);
        }

        void removeTransport(InMemoryGroupChatTransport t) {
            transports.remove(t);
            if (transports.isEmpty()) {
                ROOMS.remove(roomId);
            }
        }

        void broadcast(GroupChatMessage message) {
            for (InMemoryGroupChatTransport t : transports) {
                GroupChatListener l = t.getListener();
                if (l != null) {
                    l.onMessage(message);
                }
            }
        }

        void broadcastJoin(Participant joined, InMemoryGroupChatTransport source) {
            List<Participant> all = participants();
            for (InMemoryGroupChatTransport t : transports) {
                GroupChatListener l = t.getListener();
                if (l == null) {
                    continue;
                }
                if (t == source) {
                    // The joining peer gets the full participant list.
                    l.onParticipantsChanged(all);
                } else {
                    l.onParticipantJoined(joined);
                    l.onParticipantsChanged(all);
                    int count = all.size();
                    l.onStatusChanged(count == 1 ? "1 party member" : count + " party members");
                }
            }
        }

        void broadcastLeave(Participant left, InMemoryGroupChatTransport source) {
            List<Participant> all = participants();
            for (InMemoryGroupChatTransport t : transports) {
                if (t == source) {
                    continue;
                }
                GroupChatListener l = t.getListener();
                if (l == null) {
                    continue;
                }
                l.onParticipantLeft(left);
                l.onParticipantsChanged(all);
                int count = all.size();
                l.onStatusChanged(count == 1 ? "1 party member" : count + " party members");
            }
        }

        List<Participant> participants() {
            List<Participant> result = new ArrayList<Participant>();
            for (InMemoryGroupChatTransport t : transports) {
                Participant p = t.getSelf();
                if (p != null) {
                    result.add(p);
                }
            }
            return Collections.unmodifiableList(result);
        }

        int participantCount() {
            return transports.size();
        }
    }
}
